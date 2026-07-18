#!/usr/bin/env bash
#
# Bloom-filter point-lookup benchmark on a generated corpus whose probe column is a
# unique, unclustered 64-bit key — the workload bloom filters exist for. The corpus
# is written twice, with and without a bloom filter on `key`, so the same probe
# against both files isolates what the filter buys. Statistics cannot prune (every
# row group's range covers any probe) and the dictionary cannot prune (a fully
# distinct column falls back to plain encoding), leaving the bloom filter as the only
# pruner. Contenders: Hardwood and parquet-java, each reading both files.
# See ./run-bloom.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--rows perf.rows
--probe perf.param'
BENCH_USAGE="Bloom-filter point-lookup benchmark on a generated unique-key corpus
(eq push-down on a 64-bit key).

Usage: ./run-bloom.sh [options]
  --rows N         corpus size (default 84,000,000). At Parquet's default 128 MB
                   row groups and ~16 incompressible bytes/row this spans ~10 row
                   groups, so a present probe keeps one and drops nine. Keyed into
                   the file paths, so a different size generates a fresh pair
                   rather than reusing a stale one.
  --probe VALUE    restrict to one probe: present | absent
                   (default: run both)
$BENCH_COMMON_USAGE

The corpus is generated on first run (no download): keys come from a 64-bit
bijection of the row index, so they are exactly unique, pseudorandomly ordered, and
reproducible from the row count alone. Expect ~2.9 GB for the file pair at the
default row count (the key column is incompressible, plus ~10 MB of bloom filter
per row group).

Two modes: --gate folds every contender and checks they agree (that the present probe
matches exactly one row and the absent probe zero), and asserts the layout
preconditions — several row groups, and a probe column that fell back to plain
encoding so no dictionary filter can prune. Without it, the script benchmarks.
Publish flow — gate, smoke-test, measure, chart:
  ./run-bloom.sh --gate                                        # correctness, no timing
  ./run-bloom.sh --rows 8000000 --warmup 1 --meas 1 --forks 1   # smoke test (throwaway)
  ./run-bloom.sh --forks 5 --meas 10                           # measure
  python3 charts/make-bloom-chart.py --results-dir target      # chart
For publication-grade numbers wrap the measure line in the median-of-3 tmux loop
(README, Publication runs)."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.bloom.BloomFilterBenchmark
bench_epilogue
