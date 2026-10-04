#!/usr/bin/env bash
#
# Dictionary-space filter benchmark (regression-only): SELECT url WHERE url IN (5 values)
# over a dictionary-encoded column of long URLs, through Hardwood's row reader.
# See ./run-dictfilter.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='dictionary'
BENCH_REGRESSION_PRESET=''
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS=''
BENCH_USAGE="Dictionary-space filter benchmark, regression-only (generated file of 5M rows, one
dictionary-encoded string column of 1,000 distinct URLs of about 40 bytes, filtered on
url IN (5 values), reading each match as a String).

Usage: ./run-dictfilter.sh [options]
$BENCH_COMMON_USAGE
Regression preset (--regression): contenders matching hardwood

The one contender is Hardwood's row reader. --gate checks that every page of url is
dictionary-encoded and that the filtered read agrees with parquet-java, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.dictionary.DictionaryFilterBenchmark
bench_epilogue
