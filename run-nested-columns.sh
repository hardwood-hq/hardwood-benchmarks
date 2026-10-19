#!/usr/bin/env bash
#
# Nested column-reader regression: the column readers over the whole Overture Maps
# places file, which run-nested.sh's regression preset caps at 20,000 rows. See
# ./run-nested-columns.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='nested'
BENCH_REGRESSION_PRESET='--warmup 6 --meas 5 --time 2'
BENCH_REGRESSION_INCLUDE='hardwoodColumnReaders'
BENCH_FLAGS='--file perf.file
--rows perf.rows'
BENCH_USAGE="Nested column-reader regression (Overture Maps places, every leaf column, whole file).

Usage: ./run-nested-columns.sh [options]
  --file PATH       nested parquet file
                    (default target/overture-maps-data/overture_places.zstd.parquet)
  --rows N          stop each scan after N rows (default 0 = the whole file)
$BENCH_COMMON_USAGE
Regression preset (--regression): the whole file, --warmup 6 --meas 5 --time 2 (a read takes about
0.7 to 3 s, longer than the common preset's iterations); contender hardwoodColumnReaders

The benchmark is run-nested.sh's (NestedScanBenchmark); this script only gives it a
second regression preset. run-nested.sh's preset reads 20,000 rows, where the read is
mostly per-read setup and its typed row reader is bound by its consumer thread; over the
whole file the column readers are bound by decoding, so a change to nested decoding or
to the real-items view shows here."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.nested.NestedScanBenchmark
