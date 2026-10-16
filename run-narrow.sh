#!/usr/bin/env bash
#
# Narrow-projection scan (regression-only): three numeric columns of the 2016-01 NYC Yellow
# Taxi file, one row group of 10.9M rows with pages of about 0.7M rows. Contenders: Hardwood
# column and row readers. See ./run-narrow.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='narrow'
BENCH_REGRESSION_PRESET=''
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS='--start perf.start
--end perf.end
--data-dir data.dir'
BENCH_USAGE="Narrow-projection scan, regression-only: passenger_count, trip_distance and
fare_amount of the NYC Yellow Taxi file of 2016-01 by default: one row group of 10.9M rows,
GZIP-compressed, with pages of about 0.7M rows.

Usage: ./run-narrow.sh [options]
  --start YYYY-MM   first month (default 2016-01)
  --end YYYY-MM     last month, inclusive (default 2016-01)
  --data-dir PATH   taxi data cache (default target/tlc-trip-record-data)
$BENCH_COMMON_USAGE
Regression preset (--regression): contenders matching hardwood

--gate checks that both Hardwood readers fold to parquet-java's checksum, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.narrow.NarrowScanBenchmark
bench_epilogue
