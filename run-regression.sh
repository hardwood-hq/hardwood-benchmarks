#!/usr/bin/env bash
#
# Regression run: the benchmarks' --regression presets for one Hardwood version, or for
# several interleaved and compared, in one of three profiles; then the tables, and with
# several versions the verdict (compare-runs.py, first version against the last).
# See ./run-regression.sh --help.
#
set -uo pipefail
cd "$(dirname "$0")"

# Every regression script, as the nightly profile runs them; the quick profile runs those
# that name quick contenders (BENCH_QUICK_INCLUDE).
ALL=(filter bloom window dictfilter 1brc binary nested nested-columns flat narrow wide fixedlist write encodings s3)
QUICK=()
for s in "${ALL[@]}"; do
  grep -q "^BENCH_QUICK_INCLUDE=" "run-$s.sh" && QUICK+=("$s")
done

usage() {
  cat <<EOF
Regression run of one Hardwood version, or several compared.

Usage: ./run-regression.sh [options] VERSION [VERSION ...]
  VERSION           a hardwood-core version as --hardwood-version takes it (a release from
                    Maven Central, or a locally installed -SNAPSHOT); with several, oldest
                    first, interleaved in each round, and the verdict compares the first
                    with the last
  --profile P       quick (default): the contenders of $(IFS=,; echo "${QUICK[*]}") that
                      cover the core read and write paths, for development
                    nightly: every script's contenders: $(IFS=,; echo "${ALL[*]}")
                    smoke: every script's contenders, one 1 s iteration in one fork without
                      warmup and S3 without emulated latency, to check that each version
                      runs what it supports; no verdict
  --rounds N        rounds (default 1); with several versions, each round runs every
                    version once, so drift on the machine spreads over all of them
  --only LIST       comma-separated scripts of the profile to run
  --out DIR         where the snapshots, logs, tables and verdict go
                    (default target/regression)
  --fail-on-regression
                    exit 1 when a Hardwood contender is slower beyond the band, or a
                    file the write benchmark wrote is larger beyond 1%

Each run is ./run-<script>.sh --regression --hardwood-version VERSION; see a script's
--help for its preset. Before a script is timed, its gate (--gate) checks that its
contenders agree, against the last VERSION, unless a gate already passed for the same
sources: the script, bench-common.sh, pom.xml, src/compat, the shared classes and the
packages of the benchmarks it runs, the files git tracks among them. Its fixtures are generated or downloaded by those
sources, so they are covered by the same hash. Passed gates are kept in
\${XDG_CACHE_HOME:-~/.cache}/hardwood-benchmarks/gates. A script whose gate fails is not
timed, and the run exits 1. A preset warms its contenders until they settle on a 1.50 GHz
Intel N300 and runs each in three forks, with RegressionProfiler recording CPU, JIT and GC
time, allocation and the heap-after-collection peak beside each iteration's time. A contender that had
not settled, or whose forks disagree, gets that as its verdict. progress.log records each
script's time. When run-s3.sh is among them, the emulated S3 endpoint (./s3-env.sh) is
started once for the whole run.

A benchmark, contender or parameter value that needs a feature a version predates is
not run for it, and listed as not supported (see src/compat/README.md). A script that
does not build against a version is a defect, listed as such, and the run exits 1.

Output, under --out:
  <version>/run-<n>/   the snapshot compare-runs.py and make-version-chart.py read, with each
                       benchmark's RegressionProfiler metrics (bench-metrics-*.jsonl)
  tables.md            per benchmark, as soon as its rounds are done: each version's median
                       per Hardwood contender, the last version against each earlier one
  not-run.txt          per version, what it did not run and why
  verdict.txt          with several versions, per benchmark: a tally, and only the
                       contenders that moved
EOF
}

PROFILE=quick
ROUNDS=1
OUT=target/regression
FAIL=""
ONLY=""
VERSIONS=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --profile) PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --rounds) ROUNDS="${2:?--rounds needs a value}"; shift 2 ;;
    --only) ONLY="${2:?--only needs a value}"; shift 2 ;;
    --out) OUT="${2:?--out needs a value}"; shift 2 ;;
    --fail-on-regression) FAIL="--fail-on-regression"; shift ;;
    -*) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    *) VERSIONS+=("$1"); shift ;;
  esac
done
SMOKE=""
case "$PROFILE" in
  quick) SCRIPTS=("${QUICK[@]}") ;;
  nightly) SCRIPTS=("${ALL[@]}") ;;
  smoke) SCRIPTS=("${ALL[@]}"); SMOKE=1; ROUNDS=1 ;;
  *) echo "Unknown profile: $PROFILE (quick, nightly or smoke)" >&2; exit 2 ;;
esac
if [ -n "$ONLY" ]; then
  IFS=, read -r -a SCRIPTS <<< "$ONLY"
fi
if (( ${#VERSIONS[@]} < 1 )); then
  echo "Name a version (try --help)" >&2
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
# What the machine was when the run began; record-results.py keys a machine epoch on it.
BENCH_HEAP_FLAGS="$(bash -c 'source ./bench-common.sh && printf %s "$BENCH_HEAP_FLAGS"')" \
python3 - "$OUT/manifest.json" "$PROFILE" "$ROUNDS" <<'PY'
import json, os, platform, subprocess, sys, time

def read(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return None

def first(path, key):
    for line in (read(path) or "").splitlines():
        if line.startswith(key):
            return line.split(":", 1)[1].strip()
    return None

java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip().splitlines()
cpu = "/sys/devices/system/cpu/cpu0/cpufreq/"
json.dump({
    "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "profile": sys.argv[2],
    "rounds": int(sys.argv[3]),
    "host": platform.node(),
    "cpu": first("/proc/cpuinfo", "model name") or platform.machine(),
    "cores": os.cpu_count(),
    "microcode": first("/proc/cpuinfo", "microcode"),
    "kernel": platform.release(),
    "java": " | ".join(java),
    "jvm_flags": os.environ.get("BENCH_HEAP_FLAGS", ""),
    "governor": read(cpu + "scaling_governor"),
    "max_freq_khz": read(cpu + "scaling_max_freq"),
    "no_turbo": read("/sys/devices/system/cpu/intel_pstate/no_turbo"),
    "load": read("/proc/loadavg"),
}, open(sys.argv[1], "w"), indent=2)
PY
progress="$OUT/progress.log"
: > "$progress"
: > "$OUT/files.txt"
: > "$OUT/tables.md"
started=$(date +%s)
GATES="${XDG_CACHE_HOME:-$HOME/.cache}/hardwood-benchmarks/gates"
mkdir -p "$GATES"

# The hash of what a script's gate checks: the script and what it shares with every
# script, and the packages of the benchmark classes it runs. Only the files git tracks count,
# at their working-tree contents, so leftovers in a checkout do not change the key; fails
# outside a git checkout or when a source is missing.
gate_key() {
  local s="$1" pkg files
  files=("run-$s.sh" bench-common.sh pom.xml ":(glob)src/main/java/dev/hardwood/benchmarks/*.java")
  for pkg in $(sed -n 's/^ *bench_run dev\.hardwood\.benchmarks\.\([a-z0-9]*\)\..*/\1/p' "run-$s.sh" | sort -u); do
    files+=("src/main/java/dev/hardwood/benchmarks/$pkg")
  done
  files+=(src/compat)
  git ls-files --error-unmatch -- "${files[@]}" > /dev/null || return 1
  git ls-files -z -- "${files[@]}" | xargs -0 sha256sum | sha256sum | cut -c1-16
}

# Whether script $1's gate passed for its current sources; runs the gate against version $2
# when none did, and records a pass.
gate() {
  local s="$1" v="$2" key log
  if ! key="$(gate_key "$s")"; then
    echo "gate $s: FAILED, cannot hash its sources" | tee -a "$progress"
    return 1
  fi
  if [ -f "$GATES/$s-$key" ]; then
    echo "gate $s: passed before for these sources ($(cat "$GATES/$s-$key"))" | tee -a "$progress"
    return 0
  fi
  log="$OUT/gate-$s.log"
  t=$(date +%s)
  if BENCH_LOG=0 ./run-"$s".sh --gate --hardwood-version "$v" > "$log" 2>&1; then
    echo "$v $(date +%F)" > "$GATES/$s-$key"
    echo "gate $s: passed against $v  $(( $(date +%s) - t ))s" | tee -a "$progress"
    return 0
  fi
  echo "gate $s: FAILED against $v  $(( $(date +%s) - t ))s, see $log" | tee -a "$progress"
  return 1
}

for v in "${VERSIONS[@]}"; do
  for round in $(seq 1 "$ROUNDS"); do
    rm -rf "$OUT/$v/run-$round"
    mkdir -p "$OUT/$v/run-$round"
  done
done
# One benchmark at a time, all its rounds and versions, so its result is in before the next
# starts; within a round the versions alternate, so drift on the machine spreads over all
# of them.
gate_failed=()
for s in "${SCRIPTS[@]}"; do
  if [ -z "$SMOKE" ] && ! gate "$s" "${VERSIONS[${#VERSIONS[@]}-1]}"; then
    gate_failed+=("$s")
    continue
  fi
  for round in $(seq 1 "$ROUNDS"); do
    for v in "${VERSIONS[@]}"; do
      dest="$OUT/$v/run-$round"
      # A script whose build fails against this version leaves no results, so clear
      # the previous script's before each run: only this run's files are captured.
      rm -f target/bench-throughput-*.tsv target/bench-meta-*.tsv target/bench-metrics-*.jsonl
      t=$(date +%s)
      BENCH_LOG=0 BENCH_SMOKE="$SMOKE" BENCH_PROFILE="$PROFILE" ./run-"$s".sh --regression --hardwood-version "$v" > "$dest/$s.log" 2>&1
      rc=$?
      # Each benchmark class belongs to one script: its meta sidecar holds that script's
      # fixture, which recording keys a contender's series on.
      for f in target/bench-throughput-*.tsv target/bench-meta-*.tsv target/bench-metrics-*.jsonl; do
        [ -f "$f" ] || continue
        to="$dest/$(basename "$f")"
        if [ -f "$to" ]; then
          echo "run-$s.sh wrote $(basename "$f"), which another script of this run already wrote:" \
            "a benchmark class must belong to one script" >&2
          exit 2
        fi
        cp "$f" "$to"
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

# What a version did not run is listed from the logs, so a "not run" in a table has its
# reason next to it: a script or parameter value needing a feature the version predates, a
# build that failed, or a contender that threw.
{
  for s in "${gate_failed[@]+"${gate_failed[@]}"}"; do
    echo "  all  $s: its gate failed (gate-$s.log)"
  done
  for v in "${VERSIONS[@]}"; do
    for log in "$OUT/$v"/run-1/*.log; do
      [ -f "$log" ] || continue
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
grep -q "does not build\|its gate failed" "$OUT/not-run.txt" && broken=1

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

if (( ${#VERSIONS[@]} == 1 )); then
  echo
  echo "Tables: $OUT/tables.md"
  if [ -s "$OUT/not-run.txt" ]; then
    echo
    echo "Not run:"
    cat "$OUT/not-run.txt"
  fi
  exit $broken
fi

first="$OUT/${VERSIONS[0]}"
last="$OUT/${VERSIONS[${#VERSIONS[@]}-1]}"
python3 charts/compare-runs.py "$first" "$last" --changes-only $FAIL \
  > "$OUT/verdict.txt"
status=$?

echo
cat "$OUT/verdict.txt"
if [ -s "$OUT/not-run.txt" ]; then
  echo
  echo "Not run:"
  cat "$OUT/not-run.txt"
fi
(( broken )) && exit 1
exit $status
