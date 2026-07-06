#!/usr/bin/env bash
#
# Flat full-scan benchmark: a full scan of every column of the NYC Yellow Taxi
# monthly files. Contenders: Hardwood column & row readers, parquet-java's
# low-level column API, AvroParquetReader, and Arrow Dataset (Arrow C++ over JNI)
# as a cross-engine columnar reference. See ./run-flat.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--start perf.start
--end perf.end
--data-dir data.dir
--batch-size perf.batchSize'
BENCH_USAGE="Flat full-scan benchmark (NYC Yellow Taxi, every column).

Usage: ./run-flat.sh [options]
  --start YYYY-MM   first month of the schema-stable window (default 2025-01)
  --end YYYY-MM     last month, inclusive                   (default 2025-12)
  --data-dir PATH   taxi data cache (default target/tlc-trip-record-data);
                    point at a persistent dir to avoid re-downloading
  --batch-size N    Hardwood column reader batch size (0/unset = default); shrink
                    it (e.g. 4096) to probe single-core columnar cache behaviour
$BENCH_COMMON_USAGE

Two modes: --gate verifies every contender folds to the same checksum as
parquet-java and exits (no JMH, printing a per-contender confirmation; fails fast
on a mismatch); without it, the script benchmarks (no gate).

Publish flow — gate, smoke-test, measure, chart:
  ./run-flat.sh --gate                                                        # correctness, no timing
  ./run-flat.sh --start 2025-01 --end 2025-01 --warmup 1 --meas 1 --forks 1   # smoke test (throwaway)
  ./run-flat.sh --forks 5 --meas 10 \\
    --include 'hardwood|parquetJava|avroParquetReaderNamed|avroParquetReaderIndexed'   # measure
  python3 charts/make-flat-chart.py --results-dir target                      # chart
The measure --include restricts timing to the published contenders — the gate
already checked Arrow Dataset and the SpecificRecord reader, and timing them feeds
no chart. For publication-grade numbers wrap the measure line in the median-of-3
tmux loop (README, Publication runs)."

bench_parse_args "$@"
bench_build
bench_run dev.hardwood.benchmarks.flat.FlatScanBenchmark
bench_epilogue
