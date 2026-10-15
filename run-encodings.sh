#!/usr/bin/env bash
#
# Floating-point encoding benchmark (regression-only): every DOUBLE column of one month of
# NYC taxi, written and read by Hardwood under AUTO, PLAIN, BYTE_STREAM_SPLIT and ALP.
# See ./run-encodings.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='encoding'
BENCH_REGRESSION_PRESET='--codec ZSTD --encoding BYTE_STREAM_SPLIT,ALP'
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS='--rows perf.rows
--data-dir data.dir
--encoding perf.encoding
--codec perf.codec'
BENCH_USAGE="Floating-point encoding benchmark, regression-only (the 11 DOUBLE columns of NYC taxi
2025-01: fares, distances, tips and surcharges, decimal values with two digits).

Usage: ./run-encodings.sh [options]
  --rows N              rows taken from the month (default 1000000)
  --data-dir PATH       taxi data cache (default target/tlc-trip-record-data)
  --encoding LIST       comma-separated encodings: AUTO, PLAIN, BYTE_STREAM_SPLIT, ALP
                        (default: all)
  --codec LIST          comma-separated codecs: UNCOMPRESSED, ZSTD (default: both)
$BENCH_COMMON_USAGE
Regression preset (--regression): --codec ZSTD --encoding BYTE_STREAM_SPLIT,ALP;
contenders matching hardwood

Two contenders per encoding and codec: hardwoodRead scans every column of a file written
under them, hardwoodWrite writes the columns to memory. Before timing, every pair is written,
read back and checked bit for bit against the source values, and its column chunks must
declare the encoding named; --gate does only that. Each pair's compressed column-chunk bytes
are recorded in the meta sidecar (bytes.<ENCODING>_<CODEC>). Needs a Hardwood version with
ALP; an older one fails to build this benchmark and runs nothing of it."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.encoding.FloatEncodingBenchmark
bench_epilogue
