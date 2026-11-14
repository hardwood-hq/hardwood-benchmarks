#!/usr/bin/env bash
# The parquet-java contenders (column API, AvroParquetReader, filter) under taskset -c 0, to compare with their unpinned runs.
set -uo pipefail
cd ~/projects/hardwood-benchmarks
F=1.0.0.Final; MACH='Intel N300 (8 cores, fixed 1.5 GHz)'; W=~/scratch/pinbase; mkdir -p $W
grep -o '<parquet.version>[^<]*' pom.xml
inv() { local tag=$1 sc=$2 b=$3; shift 3
  echo "STEP $tag $(date +%T)"
  taskset -c 0 ./$sc --hardwood-version $F --machine "$MACH" --no-pin --forks 1 --time 2 "$@" -Dperf.pass=pinned > $W/$tag.log 2>&1
  echo "  rc=$?"; tail -n +2 target/bench-throughput-$b.tsv > $W/$tag.rows; cut -f3,4 $W/$tag.rows | tr '\n' ' '; echo; }
inv flat-base-pin run-flat.sh FlatScanBenchmark --start 2025-01 --end 2025-01 --warmup 5 --meas 5 --include 'parquetJavaColumnar|avroParquetReaderIndexed|avroParquetReaderNamed'
inv filter-base-pin run-filter.sh FilterBenchmark --warmup 5 --meas 5 --include 'parquetJava$'
echo DONE $(date +%T)
