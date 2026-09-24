#!/usr/bin/env bash
#
# Regression check: every benchmark under --regression, for two or more Hardwood
# versions, in interleaved rounds; then the verdict (compare-runs.py, first version
# against the last, only what moved beyond the noise band) and the version charts.
# See ./run-regression.sh --help.
#
set -uo pipefail
cd "$(dirname "$0")"

SCRIPTS=(filter bloom window nested flat fixedlist write)
CONTROL='^(parquetJava|avro|arrow)'

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
                    exit 1 when a Hardwood contender is slower beyond the band

Each run is ./run-<script>.sh --regression --hardwood-version VERSION; see a
script's --help for its preset. Per round and version that takes about 3 min for
all six scripts on a 1.50 GHz Intel N300.

Output, under --out:
  <version>/run-<n>/   the snapshot compare-runs.py and the version chart read
  verdict.txt          per benchmark: a tally, and only the contenders that moved
  charts/              one version chart per benchmark
EOF
}

ROUNDS=3
OUT=target/regression
FAIL=""
VERSIONS=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --rounds) ROUNDS="${2:?--rounds needs a value}"; shift 2 ;;
    --only) IFS=, read -r -a SCRIPTS <<< "${2:?--only needs a value}"; shift 2 ;;
    --out) OUT="${2:?--out needs a value}"; shift 2 ;;
    --fail-on-regression) FAIL="--fail-on-regression"; shift ;;
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

mkdir -p "$OUT"
progress="$OUT/progress.log"
: > "$progress"
started=$(date +%s)
for round in $(seq 1 "$ROUNDS"); do
  for v in "${VERSIONS[@]}"; do
    dest="$OUT/$v/run-$round"
    rm -rf "$dest"
    mkdir -p "$dest"
    for s in "${SCRIPTS[@]}"; do
      # A script whose build fails against this version leaves no results, so clear
      # the previous script's before each run: only this run's files are captured.
      rm -f target/bench-throughput-*.tsv target/bench-meta-*.tsv
      t=$(date +%s)
      BENCH_LOG=0 ./run-"$s".sh --regression --hardwood-version "$v" > "$dest/$s.log" 2>&1
      rc=$?
      cp target/bench-throughput-*.tsv target/bench-meta-*.tsv "$dest/" 2>/dev/null
      line="round $round/$ROUNDS  $v  $s  exit $rc  $(( $(date +%s) - t ))s"
      echo "$line" | tee -a "$progress"
    done
  done
done
echo "total $(( $(date +%s) - started ))s" | tee -a "$progress"

first="$OUT/${VERSIONS[0]}"
last="$OUT/${VERSIONS[${#VERSIONS[@]}-1]}"
python3 charts/compare-runs.py "$first" "$last" --changes-only --control "$CONTROL" $FAIL \
  > "$OUT/verdict.txt"
status=$?
python3 charts/make-version-chart.py "${VERSIONS[@]/#/$OUT/}" --control "$CONTROL" --out "$OUT/charts" \
  > /dev/null 2> "$OUT/charts.log" || cat "$OUT/charts.log" >&2

# Contenders a version did not run (its build failed, or the contender threw) are
# listed from the logs, so a "not run" in a chart has its reason next to it.
{
  for v in "${VERSIONS[@]}"; do
    for log in "$OUT/$v"/run-1/*.log; do
      s=$(basename "$log" .log)
      if grep -q "BUILD FAILURE\|COMPILATION ERROR" "$log"; then
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

echo
cat "$OUT/verdict.txt"
if [ -s "$OUT/not-run.txt" ]; then
  echo
  echo "Not run:"
  cat "$OUT/not-run.txt"
fi
echo
echo "Charts: $OUT/charts/"
exit $status
