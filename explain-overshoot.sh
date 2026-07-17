#!/usr/bin/env bash
#
# explain-overshoot.sh — attribute the fixed-size-list fast-path-vs-flat-floor
# gap on a machine without a usable hardware PMU (e.g. a virtualized cloud VM,
# where perfnorm/perfasm do not work). Everything here is wall-clock only.
#
# It runs three experiments at DRAM-bound scale and prints, for each, the
# columnFast-vs-flatFloor relationship — so the overshoot (fast path landing at
# or past the flat-column decode floor) can be explained rather than asserted:
#
#   A  k-crossover      — fast/floor across vector width k. The gap is a
#                         monotonic function of k (per-record overhead shrinks as
#                         vectors widen), not a constant; it crosses the floor
#                         around embedding widths. This is the core explanation.
#   B  core-count sweep — fast/floor at k=768 as cores (and thus memory-bandwidth
#                         pressure) increase. The winner is set by memory headroom:
#                         with cores idle the fast path's extra memory traffic is
#                         cheap; saturate the bus and the floor pulls ahead.
#   C  batch-size A/B    — fast/floor at k=768 with natural sizing vs. both forced
#                         to the same value-batch size. If the gap survives, it is
#                         structural (rep-levels + row index), not batch shape.
#
# Requires the -Dperf.batchSize knob (for C) — present in this repo's benchmark.
#
# Usage: ./explain-overshoot.sh            # full A+B+C at 128M values
#        TOTAL_VALUES=64000000 ./explain-overshoot.sh
#        KS="3,64,256,768" CORES="0 0-3 0-7" FORKS=5 ./explain-overshoot.sh
#
set -euo pipefail
cd "$(dirname "$0")"

TOTAL_VALUES="${TOTAL_VALUES:-128000000}"   # 512 MB files → DRAM-bound
KS="${KS:-3,16,64,128,256,768,1536}"        # k sweep for experiment A
CORES="${CORES:-0 0-1 0-3 0-7}"             # taskset core sets for experiment B
BATCH_EQUAL="${BATCH_EQUAL:-1000000}"       # equal value-batch size for experiment C
FORKS="${FORKS:-3}"
MEAS="${MEAS:-5}"

TSV="target/bench-throughput-FixedSizeListScanBenchmark.tsv"
ONLY='columnFast|flatFloor'

# ms/op for one contender label (e.g. "columnFast[768]") from the last run's TSV.
ms() { awk -F'\t' -v c="$2" '$3==c {print $4}' "$1"; }

# Run one headline config (optionally under taskset / extra -D) and echo nothing;
# results land in $TSV. Args: <taskset-cores-or-empty> <k-csv> [extra JAVA_TOOL_OPTIONS]
run() {
  local cores="$1" ks="$2" jto="${3:-}"
  local cmd=(./run-fixedlist.sh --k "$ks" --total-values "$TOTAL_VALUES"
             --forks "$FORKS" --meas "$MEAS" --include "$ONLY")
  if [[ -n "$cores" ]]; then
    JAVA_TOOL_OPTIONS="$jto" taskset -c "$cores" "${cmd[@]}" >/dev/null
  else
    JAVA_TOOL_OPTIONS="$jto" "${cmd[@]}" >/dev/null
  fi
}

ratio() { awk -v a="$1" -v b="$2" 'BEGIN { if (b>0) printf "%.3f", a/b; else print "n/a" }'; }

echo "== fixed-size-list fast-path vs flat-floor — overshoot attribution =="
echo "   total_values=$TOTAL_VALUES  forks=$FORKS  meas=$MEAS"
echo

echo "== A. k-crossover (fast/floor vs k; <1.0 = fast beats the floor) =="
run "" "$KS"
printf "   %-8s %12s %12s %10s\n" k columnFast flatFloor fast/floor
IFS=',' read -ra KARR <<< "$KS"
for k in "${KARR[@]}"; do
  cf="$(ms "$TSV" "columnFast[$k]")"; ff="$(ms "$TSV" "flatFloor[$k]")"
  printf "   %-8s %12s %12s %10s\n" "$k" "$cf" "$ff" "$(ratio "$cf" "$ff")"
done
echo "   → expect a monotonic curve crossing 1.0 once; the overshoot is its large-k tail."
echo

echo "== B. core-count sweep at k=768 (fast/floor vs memory-bandwidth pressure) =="
printf "   %-8s %12s %12s %10s\n" cores columnFast flatFloor fast/floor
for c in $CORES; do
  run "$c" 768
  cf="$(ms "$TSV" "columnFast[768]")"; ff="$(ms "$TSV" "flatFloor[768]")"
  printf "   %-8s %12s %12s %10s\n" "$c" "$cf" "$ff" "$(ratio "$cf" "$ff")"
done
echo "   → fast/floor should rise with cores: the fast path wins when memory is cheap,"
echo "     the floor wins once the bus saturates. That machine-balance is the mechanism."
echo

echo "== C. batch-size A/B at k=768 (is the gap batch shape or structural?) =="
printf "   %-16s %12s %12s %10s\n" sizing columnFast flatFloor fast/floor
run "" 768
cf="$(ms "$TSV" "columnFast[768]")"; ff="$(ms "$TSV" "flatFloor[768]")"
printf "   %-16s %12s %12s %10s\n" "natural" "$cf" "$ff" "$(ratio "$cf" "$ff")"
run "" 768 "-Dperf.batchSize=$BATCH_EQUAL"
cf="$(ms "$TSV" "columnFast[768]")"; ff="$(ms "$TSV" "flatFloor[768]")"
printf "   %-16s %12s %12s %10s\n" "equal($BATCH_EQUAL)" "$cf" "$ff" "$(ratio "$cf" "$ff")"
echo "   → if the ratio barely moves, the gap is structural (rep-levels + row index),"
echo "     not batch shape."
