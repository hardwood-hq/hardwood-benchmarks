#!/usr/bin/env bash
#
# Flat write benchmark (regression-only): taxi-shaped records written to memory through
# Hardwood's column writer and row writer, SNAPPY and ZSTD. See ./run-write.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGES='write'
BENCH_REGRESSION_PRESET='--rows 500000'
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS='--rows perf.rows
--codec perf.param'
BENCH_USAGE="Write benchmark, regression-only (generated taxi-shaped records, written to memory).

Usage: ./run-write.sh [options]
  --rows N              records per file (default 500000)
  --codec VALUE         restrict to one codec: SNAPPY | ZSTD (default: both)
$BENCH_COMMON_USAGE
Regression preset (--regression): --rows 500000; contenders matching hardwood

Every contender is Hardwood. Before timing, each API's file is read back and its row count
and fare sum checked; --gate does only that. The produced compressed column-chunk bytes of
each codec's file are recorded in the meta sidecar (bytes, bytesZstd), so an encoding change shows up as a
dataset difference between two versions."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.write.WriteBenchmark
bench_epilogue
