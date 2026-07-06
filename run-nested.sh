#!/usr/bin/env bash
#
# Nested read benchmark: deeply nested struct/list/map records from an Overture
# Maps places file. Contenders: Hardwood row reader vs AvroParquetReader (the
# like-for-like record path). See ./run-nested.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--file perf.file'
BENCH_USAGE="Nested read benchmark (Overture Maps places, full record reconstruction).

Usage: ./run-nested.sh [options]
  --file PATH       nested parquet file
                    (default target/overture-maps-data/overture_places.zstd.parquet)
$BENCH_COMMON_USAGE

Two modes: --gate checks the row reader and AvroParquetReader assemble identical
records, then exits (no JMH); without it, the script benchmarks. Publish flow —
gate, smoke-test, measure (numbers only, no chart):
  ./run-nested.sh --gate                         # correctness, no timing
  ./run-nested.sh --warmup 1 --meas 1 --forks 1  # smoke test (throwaway)
  ./run-nested.sh --forks 5 --meas 10            # measure
For publication-grade numbers wrap the measure line in the median-of-3 tmux loop
(README, Publication runs)."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.nested.NestedScanBenchmark
bench_epilogue
