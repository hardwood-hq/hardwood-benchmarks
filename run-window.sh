#!/usr/bin/env bash
#
# Recent-time-window benchmark (regression-only) on a generated, time-sorted event
# log with many row groups: SELECT amount WHERE event_time >= T through Hardwood's
# column and row readers, with unfiltered reads as controls. See ./run-window.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGES='window filter'
BENCH_REGRESSION_PRESET='--window last25pct'
BENCH_REGRESSION_INCLUDE='hardwood'
BENCH_FLAGS='--window perf.param'
BENCH_USAGE="Time-window benchmark, regression-only (generated time-sorted event log of
10M rows in 16 MB row groups, filter on event_time, projecting amount).

Usage: ./run-window.sh [options]
  --window VALUE        restrict to one window: last5pct | last25pct | last75pct
                        (default: run all three)
$BENCH_COMMON_USAGE
Regression preset (--regression): --window last25pct; contenders matching hardwood

Every contender is Hardwood, so the pinned single-core pass times them all. The
dataset has one size, so runs of any two versions read the same file.

Two modes: --gate checks every reader against parquet-java, then exits (no JMH);
without it, the script benchmarks. Version-comparison flow: gate, then measure and
capture each version, then compare and chart:
  ./run-window.sh --gate                                   # correctness, no timing
  ./run-window.sh --hardwood-version 1.1.0 --forks 3       # measure one version
  ./capture-run.sh <scratch>/1.1.0
  ./run-window.sh --hardwood-version 1.2.0 --forks 3       # measure the next
  ./capture-run.sh <scratch>/1.2.0
  python3 charts/compare-runs.py <scratch>/1.1.0 <scratch>/1.2.0
  python3 charts/make-version-chart.py <scratch>/1.1.0 <scratch>/1.2.0
Two builds of one -SNAPSHOT: install Hardwood at each commit before its run; the
meta sidecar records the commit, and the chart labels each build with it."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.window.TimeWindowBenchmark
bench_epilogue
