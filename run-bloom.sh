#!/usr/bin/env bash
#
# Bloom-filter point-lookup benchmark on the real NYC TLC taxi data, rewritten
# with a bloom filter on the high-cardinality, unclustered total_amount column
# (plus a statistics-only twin) where statistics can prune nothing and only a
# per-row-group bloom filter can. Contenders: Hardwood and parquet-java, each
# reading both files and pushing down a `total_amount = k` probe.
# See ./run-bloom.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--start perf.start
--end perf.end
--limit perf.limit
--probe perf.param
--data-dir data.dir'
BENCH_USAGE="Bloom-filter point-lookup benchmark on real TLC taxi data, rewritten with a
bloom filter on total_amount (eq push-down).

Usage: ./run-bloom.sh [options]
  --start YYYY-MM  first TLC month to include (default 2025-01)
  --end YYYY-MM    last TLC month to include (default 2025-03)
  --limit N        cap total rows across the window (default 0 = all)
  --probe VALUE    restrict to one probe: present | absent
                   (default: run both)
  --data-dir PATH  taxi data cache (default target/tlc-trip-record-data);
                   point at a persistent dir to avoid re-downloading
$BENCH_COMMON_USAGE

Two modes: --gate folds every contender and checks they agree (and that the absent
probe returns zero rows), then exits (no JMH); without it, the script benchmarks.
Publish flow — gate, smoke-test, measure, chart:
  ./run-bloom.sh --gate                                                 # correctness, no timing
  ./run-bloom.sh --start 2025-01 --end 2025-01 --warmup 1 --meas 1 --forks 1  # smoke test (throwaway)
  ./run-bloom.sh --forks 5 --meas 10                                    # measure
  python3 charts/make-bloom-chart.py --results-dir target               # chart
For publication-grade numbers wrap the measure line in the median-of-3 tmux loop
(README, Publication runs)."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.bloom.BloomFilterBenchmark
bench_epilogue
