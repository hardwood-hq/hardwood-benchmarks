#!/usr/bin/env bash
#
# Wide-schema scan (regression-only): every column of a generated file of 10,000 FLOAT columns
# and 2,190 rows, one row group, one page per column chunk. Contenders: Hardwood column and row
# readers. See ./run-wide.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='wide'
BENCH_REGRESSION_PRESET='--warmup 15'
BENCH_REGRESSION_INCLUDE='hardwoodColumnar'
BENCH_FLAGS=''
BENCH_USAGE="Wide-schema scan, regression-only: every column of a generated file of 10,000
required FLOAT columns and 2,190 rows, one row group, one page per column chunk,
BYTE_STREAM_SPLIT without a dictionary, SNAPPY. Generated on first run into target/.

Usage: ./run-wide.sh [options]
$BENCH_COMMON_USAGE
Regression preset (--regression): --warmup 15; contenders matching hardwoodColumnar

--gate checks that every chunk is one BYTE_STREAM_SPLIT page without a dictionary and that
both Hardwood readers fold to parquet-java's checksum, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.wide.WideScanBenchmark
bench_epilogue
