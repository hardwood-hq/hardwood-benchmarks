#!/usr/bin/env bash
#
# Filtered scan (predicate push-down) benchmark on a generated, time-clustered
# event file. Contenders: Hardwood column reader vs parquet-java's low-level
# column API, pushing down a range predicate. See ./run-filter.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--rows perf.rows
--selectivity perf.param'
BENCH_USAGE="Filtered scan benchmark (generated time-clustered event log, range push-down).

Usage: ./run-filter.sh [options]
  --rows N              rows in the generated file (default 50000000)
  --selectivity VALUE   restrict to one selectivity: selective | matchAll
                        (default: run both)
$BENCH_COMMON_USAGE

Two modes: --gate folds every contender and checks they agree, then exits (no JMH);
without it, the script benchmarks. Publish flow — gate, smoke-test, measure, chart:
  ./run-filter.sh --gate                                        # correctness, no timing
  ./run-filter.sh --rows 1000000 --warmup 1 --meas 1 --forks 1  # smoke test (throwaway)
  ./run-filter.sh --forks 5 --meas 10                           # measure
  python3 charts/make-filter-chart.py --results-dir target      # chart
For publication-grade numbers wrap the measure line in the median-of-3 tmux loop
(README, Publication runs)."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.filter.FilterBenchmark
bench_epilogue
