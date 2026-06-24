#!/usr/bin/env bash
#
# Nested read benchmark: deeply nested struct/list/map records from an Overture
# Maps places file. Contenders: Hardwood row reader vs AvroParquetReader (the
# like-for-like record path). See ./run-nested.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

declare -A BENCH_FLAGS=(
  [--file]=perf.file
)
BENCH_USAGE="Nested read benchmark (Overture Maps places, full record reconstruction).

Usage: ./run-nested.sh [options]
  --file PATH       nested parquet file
                    (default target/overture-maps-data/overture_places.zstd.parquet)
$BENCH_COMMON_USAGE"

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.NestedScanBenchmark
bench_epilogue
