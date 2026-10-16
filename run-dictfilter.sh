#!/usr/bin/env bash
#
# Dictionary-space filter benchmark (regression-only): SELECT url WHERE url IN (...) over
# dictionary-encoded columns of long URLs, through Hardwood's row reader.
# See ./run-dictfilter.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='dictionary'
BENCH_REGRESSION_PRESET=''
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS=''
BENCH_USAGE="Dictionary-space filter benchmark, regression-only (generated files of 5M rows,
reading each match as a String): hardwoodRowIn filters a dictionary-encoded string column of
1,000 distinct URLs of about 40 bytes on url IN (5 values); hardwoodRowInWide filters one of
200,000 distinct URLs on url IN (50 values), and hardwoodRowLtWide filters it on url < a literal
about 2 % of its rows sort below.

Usage: ./run-dictfilter.sh [options]
$BENCH_COMMON_USAGE
Regression preset (--regression): contenders matching hardwood

Every contender is Hardwood's row reader. --gate checks that every page of url is
dictionary-encoded and that each filtered read agrees with parquet-java, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.dictionary.DictionaryFilterBenchmark
bench_epilogue
