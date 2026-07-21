#!/usr/bin/env bash
#
# One-shot capture for the fixed-size-list post. Runs, in order:
#   1. FixedSizeListDecodeBenchmark   (main repo micro-bench — the detector-cost numbers)
#   2. FixedSizeListFallbackBenchmark  (main repo micro-bench — the fallback worst case)
#   3. headline scan, ZSTD             (this repo — the ~512 MB bars chart)
#   4. headline scan, UNCOMPRESSED     (this repo — the "compression barely matters" cross-check)
#   5. sweep scan, ZSTD                (this repo — the speedup-vs-n curve)
# The micro-benches run once; the scan benches (3-5) run 3 rounds for a median.
#
# Everything lands under  <hardwood-benchmarks>/results/<DATE>-fixed-size-list/  :
#   decode-benchmark.{log,json}   fallback-benchmark.{log,json}      (micro-benches, 1 run)
#   headline/run-{1,2,3}/  headline-plain/run-{1,2,3}/  sweep/run-{1,2,3}/
#     (scan benches, 3 rounds — each run-N/: run.log + captured bench-*.tsv; chart the median)
#
# Two modes:
#   ./run-overnight-fixedlist.sh smoke   # tiny & fast — verify the wiring/capture, throwaway numbers
#   ./run-overnight-fixedlist.sh full    # the real run (long — start it in tmux)
#
# smoke writes to results/_smoke-fixed-size-list/ so it never touches the real capture.
#
# Env overrides: HW, HB (repo paths), DATE, MACHINE, PYTHON (needs pyarrow+numpy),
#   SKIP_BUILD=1 (reuse an existing micro-bench jar + installed core),
#   FORCE_GEN=1  (regenerate the micro corpus even if present).
set -euo pipefail

MODE="${1:-}"
case "$MODE" in smoke|full) ;; *) echo "usage: $0 {smoke|full}" >&2; exit 2 ;; esac

HW="${HW:-$HOME/projects/hardwood}"
HB="${HB:-$HOME/projects/hardwood-benchmarks}"
DATE="${DATE:-2026-07-22}"
MACHINE="${MACHINE:-AWS m7i.2xlarge (8 vCPU / 4 physical cores)}"
PYTHON="${PYTHON:-python3}"
MICRO_DATA="${MICRO_DATA:-/tmp/fsl-micro-data}"
HEADLINE_DATA="$HB/target/fsl-headline-data"   # reused (rebuilt) between zstd & plain

if [[ "$MODE" == smoke ]]; then
  BASE="$HB/results/_smoke-fixed-size-list"
  DECODE_ARGS="-f 1 -wi 1 -i 1 -p k=768"
  FALLBACK_ARGS="-f 1 -wi 1 -i 1 -p k=768 -p differPos=last"
  SCAN_ARGS="--forks 1 --warmup 1 --meas 1"
  HEADLINE_VALS=1000000
  SWEEP_ARGS="--k 3,16 --total-values 1000000"
  LABEL="SMOKE (fast wiring check — numbers are throwaway)"
else
  BASE="$HB/results/$DATE-fixed-size-list"
  DECODE_ARGS="-f 5"      # 5 forks to match the post; warmup/iters are baked into the bench
  FALLBACK_ARGS="-f 5"
  SCAN_ARGS="--forks 3 --meas 5"
  HEADLINE_VALS=128000000
  SWEEP_ARGS=""           # full n sweep
  LABEL="FULL (publication run)"
fi

step() { printf '\n=========== %s ===========\n' "$*"; }

# ---------------- preflight ----------------
step "Preflight — $LABEL"
[[ -d "$HW" ]] || { echo "missing hardwood repo: $HW" >&2; exit 1; }
[[ -x "$HB/run-fixedlist.sh" && -x "$HB/capture-run.sh" ]] || { echo "missing runner/capture in $HB" >&2; exit 1; }
"$PYTHON" -c "import pyarrow, numpy" 2>/dev/null || { echo "python '$PYTHON' lacks pyarrow/numpy — set PYTHON=..." >&2; exit 1; }
echo "hardwood:            $HW  ($(git -C "$HW" rev-parse --short HEAD 2>/dev/null || echo '?'))"
echo "hardwood-benchmarks: $HB  ($(git -C "$HB" rev-parse --short HEAD 2>/dev/null || echo '?'))"
echo "results ->           $BASE"
df -h "$HB" | tail -1
[[ "$MODE" == smoke ]] && rm -rf "$BASE"
mkdir -p "$BASE"

# ---------------- 1+2. micro-benchmarks (main repo) ----------------
JAR="$HW/performance-testing/micro-benchmarks/target/benchmarks.jar"
if [[ -z "${SKIP_BUILD:-}" ]]; then
  step "Build hardwood-core + micro-benchmarks"
  ( cd "$HW" && ./mvnw -q -pl core install -DskipTests \
      && ./mvnw -q -pl performance-testing/micro-benchmarks package -Pperformance-test )
fi
[[ -f "$JAR" ]] || { echo "micro-bench jar not built: $JAR (drop SKIP_BUILD)" >&2; exit 1; }

if [[ -n "${FORCE_GEN:-}" || ! -f "$MICRO_DATA/fixed_size_list_k768.parquet" ]]; then
  step "Generate micro corpus -> $MICRO_DATA"
  "$PYTHON" "$HW/performance-testing/generate_fixed_size_list_data.py" "$MICRO_DATA"
fi

step "FixedSizeListDecodeBenchmark"
java -jar "$JAR" FixedSizeListDecodeBenchmark $DECODE_ARGS -p dataDir="$MICRO_DATA" \
  -rf json -rff "$BASE/decode-benchmark.json" 2>&1 | tee "$BASE/decode-benchmark.log"

step "FixedSizeListFallbackBenchmark"
java -jar "$JAR" FixedSizeListFallbackBenchmark $FALLBACK_ARGS -p dataDir="$MICRO_DATA" \
  -rf json -rff "$BASE/fallback-benchmark.json" 2>&1 | tee "$BASE/fallback-benchmark.log"

# ---------------- 3-5. scan benchmarks (this repo) ----------------
# Each scan bench runs in its own loop (all headline runs, then all plain, then all
# sweep) — RUNS times (1 in smoke, 3 for the median), captured to <dir>/run-<i>. Each
# run truncates target/'s shared TSV, so clear, run (tee), then capture before the next;
# capture-run.sh copies (never wipes), so the log survives. Chart the median run.
# The corpus is deterministic, so it's built once per loop (rm-rebuild only when the
# codec changes) and reused across that loop's runs; the read timing is what varies.
cd "$HB"
RUNS=$([[ "$MODE" == smoke ]] && echo 1 || echo 3)
scan() {  # scan <dest-subdir> -- <run-fixedlist args...>
  local dest="$BASE/$1"; shift 2
  mkdir -p "$dest"
  rm -f target/bench-*.tsv target/*.log
  ./run-fixedlist.sh --machine "$MACHINE" $SCAN_ARGS "$@" 2>&1 | tee "$dest/run.log"
  ./capture-run.sh "$dest"
}
cooldown() { if [[ $1 -lt "$RUNS" ]]; then echo "-- 5-min cooldown --"; sleep 300; fi; }

step "Headline, ZSTD — $RUNS run(s)"
rm -rf "$HEADLINE_DATA"
for i in $(seq 1 "$RUNS"); do
  scan "headline/run-$i" -- --k 3,768 --total-values "$HEADLINE_VALS" \
    --data-dir "$HEADLINE_DATA" -Dperf.compression=ZSTD
  cooldown "$i"
done

step "Headline, UNCOMPRESSED — $RUNS run(s)"
rm -rf "$HEADLINE_DATA"   # clear the ZSTD corpus (same filenames — codec not in the name)
for i in $(seq 1 "$RUNS"); do
  scan "headline-plain/run-$i" -- --k 3,768 --total-values "$HEADLINE_VALS" \
    --data-dir "$HEADLINE_DATA" -Dperf.compression=UNCOMPRESSED
  cooldown "$i"
done

step "Sweep, ZSTD — $RUNS run(s)"
for i in $(seq 1 "$RUNS"); do
  scan "sweep/run-$i" -- $SWEEP_ARGS -Dperf.compression=ZSTD --include 'column|row'
  cooldown "$i"
done

step "DONE — $LABEL"
echo "Artifacts:"
find "$BASE" -type f | sort
