#!/usr/bin/env bash
#
# Regression check: every benchmark under --regression, for two or more Hardwood
# versions, in interleaved rounds; then the verdict (compare-runs.py, first version
# against the last, only what moved beyond the noise band), the version charts and
# the overview chart.
# See ./run-regression.sh --help.
#
set -uo pipefail
cd "$(dirname "$0")"

# Every regression script. Each preset warms its contenders until they settle, so a run
# measures steady state.
SCRIPTS=(filter bloom window dictfilter 1brc binary nested nested-columns flat narrow fixedlist write encodings s3)

usage() {
  cat <<EOF
Regression check across Hardwood versions.

Usage: ./run-regression.sh [options] VERSION VERSION [VERSION ...]
  VERSION           a hardwood-core version as --hardwood-version takes it, oldest
                    first (a release from Maven Central, or a locally installed
                    -SNAPSHOT); the verdict compares the first with the last
  --rounds N        rounds (default 3); each round runs every version once, so
                    drift on the machine spreads over all of them, and the rounds
                    give compare-runs.py a measured noise band
  --only LIST       comma-separated scripts to run (default: $(IFS=,; echo "${SCRIPTS[*]}"))
  --out DIR         where the snapshots, logs, verdict and charts go
                    (default target/regression)
  --fail-on-regression
                    exit 1 when a Hardwood contender is slower beyond the band, or a
                    file the write benchmark wrote is larger beyond 1%
  --smoke           check that every version runs what it supports, not how fast: one
                    round of each preset's contenders, one 1 s iteration and no warmup
                    each, S3 without emulated latency (S3_LATENCY_MS=0), and no verdict
                    or charts; lists what each version did not run

Each run is ./run-<script>.sh --regression --hardwood-version VERSION; see a
script's --help for its preset, whose warmup and contenders decide how long it takes;
progress.log records each script's time. Each version's first round adds its build. When
run-s3.sh is among them, the emulated S3 endpoint (./s3-env.sh) is started once for the
whole run.

A benchmark, contender or parameter value that needs a feature a version predates is
not run for it, and listed as not supported (see src/compat/README.md). A script that
does not build against a version is a defect, listed as such, and the run exits 1.

Output, under --out:
  <version>/run-<n>/   the snapshot compare-runs.py and the version chart read
  tables.md            per benchmark, as soon as its rounds are done: each version's median
                       per Hardwood contender, the last version against each earlier one
  not-run.txt          per version, what it did not run and why
  verdict.txt          per benchmark: a tally, and only the contenders that moved
  charts/              one version chart per benchmark, and regression-overview.svg:
                       every Hardwood contender, the last version relative to the first
EOF
}

ROUNDS=3
OUT=target/regression
FAIL=""
SMOKE=""
VERSIONS=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --rounds) ROUNDS="${2:?--rounds needs a value}"; shift 2 ;;
    --only) IFS=, read -r -a SCRIPTS <<< "${2:?--only needs a value}"; shift 2 ;;
    --out) OUT="${2:?--out needs a value}"; shift 2 ;;
    --fail-on-regression) FAIL="--fail-on-regression"; shift ;;
    --smoke) SMOKE=1; ROUNDS=1; shift ;;
    -*) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    *) VERSIONS+=("$1"); shift ;;
  esac
done
if (( ${#VERSIONS[@]} < 2 )); then
  echo "Name at least two versions (try --help)" >&2
  exit 2
fi
for s in "${SCRIPTS[@]}"; do
  [ -x "./run-$s.sh" ] || { echo "No script run-$s.sh" >&2; exit 2; }
done

# A smoke run checks wiring, not time: without emulated latency, the S3 gates and reads take
# seconds instead of minutes.
if [ -n "$SMOKE" ]; then
  export S3_LATENCY_MS="${S3_LATENCY_MS:-0}"
fi

# run-s3.sh starts the emulated S3 endpoint when it is not running and stops it again. Started
# here instead, it serves every round of the run, and its startup is paid once.
if [[ " ${SCRIPTS[*]} " == *" s3 "* && "$(./s3-env.sh status)" != running ]]; then
  ./s3-env.sh start
  trap './s3-env.sh stop' EXIT
fi

mkdir -p "$OUT"
progress="$OUT/progress.log"
: > "$progress"
: > "$OUT/files.txt"
: > "$OUT/tables.md"
started=$(date +%s)
for v in "${VERSIONS[@]}"; do
  for round in $(seq 1 "$ROUNDS"); do
    rm -rf "$OUT/$v/run-$round"
    mkdir -p "$OUT/$v/run-$round"
  done
done
# One benchmark at a time, all its rounds and versions, so its result is in before the next
# starts; within a round the versions alternate, so drift on the machine spreads over all
# of them.
for s in "${SCRIPTS[@]}"; do
  for round in $(seq 1 "$ROUNDS"); do
    for v in "${VERSIONS[@]}"; do
      dest="$OUT/$v/run-$round"
      # A script whose build fails against this version leaves no results, so clear
      # the previous script's before each run: only this run's files are captured.
      rm -f target/bench-throughput-*.tsv target/bench-meta-*.tsv
      t=$(date +%s)
      BENCH_LOG=0 BENCH_SMOKE="$SMOKE" ./run-"$s".sh --regression --hardwood-version "$v" > "$dest/$s.log" 2>&1
      rc=$?
      for f in target/bench-throughput-*.tsv target/bench-meta-*.tsv; do
        [ -f "$f" ] || continue
        cp "$f" "$dest/"
        echo "$s $(basename "$f")" >> "$OUT/files.txt"
      done
      line="round $round/$ROUNDS  $v  $s  exit $rc  $(( $(date +%s) - t ))s"
      echo "$line" | tee -a "$progress"
    done
  done
  if [ -z "$SMOKE" ]; then
    echo
    python3 charts/version-table.py "$OUT" --script "$s" "${VERSIONS[@]}" | tee -a "$OUT/tables.md"
    echo
  fi
done
echo "total $(( $(date +%s) - started ))s" | tee -a "$progress"

# What a version did not run is listed from the logs, so a "not run" in a chart has its
# reason next to it: a script or parameter value needing a feature the version predates, a
# build that failed, or a contender that threw.
{
  for v in "${VERSIONS[@]}"; do
    for log in "$OUT/$v"/run-1/*.log; do
      s=$(basename "$log" .log)
      grep -h "^Not supported by" "$log" | sed "s/^/  $v  $s: /"
      if grep -q "BUILD FAILURE\|COMPILATION ERROR\|^No adapter in src/compat" "$log"; then
        echo "  $v  $s: does not build against this version"
      fi
      # JMH prints a failed contender's exception on the first non-blank line after
      # <failure>; the contender and its parameters are the last ones announced.
      awk -v prefix="  $v  $s: " '
        /^# Benchmark:/  { name = $3; sub(/.*\./, "", name); params = "" }
        /^# Parameters:/ { params = $0; sub(/^# Parameters: \(/, "[", params); sub(/\)$/, "]", params) }
        /<failure>/      { pending = 1; next }
        pending && NF    { print prefix name params ": " $0; pending = 0 }
      ' "$log" | cut -c1-200
    done
  done
} > "$OUT/not-run.txt"
broken=0
grep -q "does not build" "$OUT/not-run.txt" && broken=1

if [ -n "$SMOKE" ]; then
  echo
  echo "Contenders run, per version:"
  python3 - "$OUT" "${VERSIONS[@]}" <<'PY'
import csv, glob, os, sys
out, versions = sys.argv[1], sys.argv[2:]
ran = {}
for v in versions:
    for tsv in glob.glob(os.path.join(out, v, "run-1", "bench-throughput-*.tsv")):
        with open(tsv) as f:
            for row in csv.DictReader(f, delimiter="\t"):
                ran.setdefault((row["benchmark"], row["contender"]), set()).add(v)
width = max([len(f"{b}.{c}") for b, c in ran] + [9])
print("  " + "contender".ljust(width) + "".join(f"  {v}" for v in versions))
for (b, c) in sorted(ran):
    print("  " + f"{b}.{c}".ljust(width)
          + "".join("  " + ("ran" if v in ran[(b, c)] else "-").ljust(len(v)) for v in versions))
PY
  echo
  echo "Not run:"
  if [ -s "$OUT/not-run.txt" ]; then cat "$OUT/not-run.txt"; else echo "  nothing"; fi
  exit $broken
fi

first="$OUT/${VERSIONS[0]}"
last="$OUT/${VERSIONS[${#VERSIONS[@]}-1]}"
python3 charts/compare-runs.py "$first" "$last" --changes-only $FAIL \
  > "$OUT/verdict.txt"
status=$?
python3 charts/make-version-chart.py "${VERSIONS[@]/#/$OUT/}" --out "$OUT/charts" \
  > /dev/null 2> "$OUT/charts.log" || cat "$OUT/charts.log" >&2
python3 charts/make-regression-chart.py "$first" "$last" --out "$OUT/charts" \
  > /dev/null 2>> "$OUT/charts.log" || cat "$OUT/charts.log" >&2

echo
cat "$OUT/verdict.txt"
if [ -s "$OUT/not-run.txt" ]; then
  echo
  echo "Not run:"
  cat "$OUT/not-run.txt"
fi
echo
echo "Charts: $OUT/charts/"
(( broken )) && exit 1
exit $status
