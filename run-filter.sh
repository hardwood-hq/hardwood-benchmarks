#!/usr/bin/env bash
#
# Filtered scan (predicate push-down) benchmark on a generated, time-clustered
# event file. Contenders: Hardwood column reader vs parquet-java's low-level
# column API, pushing down a range predicate. See ./run-filter.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

declare -A BENCH_FLAGS=(
  [--rows]=perf.rows
  [--selectivity]=perf.param
)
BENCH_USAGE="Filtered scan benchmark (generated time-clustered event log, range push-down).

Usage: ./run-filter.sh [options]
  --rows N              rows in the generated file (default 50000000)
  --selectivity VALUE   restrict to one selectivity: selective | matchAll
                        (default: run both)
$BENCH_COMMON_USAGE"

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.FilterBenchmark
bench_epilogue
