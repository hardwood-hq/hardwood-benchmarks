#!/usr/bin/env bash
#
# Plain binary scan benchmark (regression-only): a full read of two string columns
# stored PLAIN, without a dictionary, through Hardwood's column and row readers.
# See ./run-binary.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='binary'
BENCH_REGRESSION_PRESET=''
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS=''
BENCH_USAGE="Plain binary scan benchmark, regression-only (a generated file of 5M rows, SNAPPY):
text, a required string of 4 to 60 ASCII letters, and note, an optional string of 8 to 24
letters, null in one row of ten, both PLAIN with no dictionary.

Usage: ./run-binary.sh [options]
$BENCH_COMMON_USAGE
Regression preset (--regression): contenders matching hardwood

Contenders: hardwoodColumnViews (column readers, each value through its byte view),
hardwoodRowStrings (row reader, each value as a String). Both need hardwood-core
1.1.0.Beta2 or later. --gate checks that every page of both columns is PLAIN and that
each contender agrees with parquet-java, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.binary.BinaryScanBenchmark
bench_epilogue
