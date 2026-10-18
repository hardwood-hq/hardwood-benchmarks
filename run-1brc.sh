#!/usr/bin/env bash
#
# 1BRC over Parquet (regression-only): min/mean/max measurement per station over a
# generated file, aggregated by Hardwood's column reader (one consumer, and one per
# row-group byte-range split) and its row reader. See ./run-1brc.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_PACKAGE='onebrc'
BENCH_REGRESSION_PRESET='--rows 100000000'
BENCH_REGRESSION_INCLUDE='hardwoodColumn'
BENCH_FLAGS='--rows perf.rows'
BENCH_USAGE="1BRC-over-Parquet benchmark, regression-only (generated file: 413 stations, a
dictionary-encoded string column station and a dictionary-encoded double column
measurement, ZSTD; min/mean/max of measurement per station).

Usage: ./run-1brc.sh [options]
  --rows N              rows in the generated file (default 100000000; 1000000000
                        for the full challenge, about 2.4 GB)
$BENCH_COMMON_USAGE
Regression preset (--regression): --rows 100000000; contenders matching hardwoodColumn

Contenders: hardwoodColumnIds (column reader, one consumer, by dictionary id),
hardwoodColumnSplits (the same, one reader and consumer per row-group byte-range
split, one split per available processor), hardwoodColumnFanOut (one column reader
whose loop hands each batch to one of as many aggregating workers as there are
available processors), hardwoodRowReader (row reader into a
HashMap; not in the regression preset, since the per-row HashMap lookup takes most of
its time). The column contenders need hardwood-core 1.1.0.Beta2 or later. --gate
checks that every page is dictionary-encoded and that every contender matches the
aggregate the generator recorded, then exits."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.onebrc.OneBrcBenchmark
bench_epilogue
