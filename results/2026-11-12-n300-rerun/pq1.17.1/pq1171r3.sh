#!/usr/bin/env bash
# Repeat 3 of the parquet-java 1.17.1 contenders (same settings as pj runs 1-2), clock pinned.
set -uo pipefail
cd ~/projects/hardwood-benchmarks
F=1.0.0.Final; MACH='Intel N300 (8 cores, fixed 1.5 GHz)'; W=~/scratch/pq1171r3; mkdir -p $W
grep -o '<parquet.version>[^<]*' pom.xml
sudo profiling-setup pin > $W/pin.log 2>&1; echo "pinned rc=$? $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq)"
inv() { local tag=$1 sc=$2 b=$3; shift 3
  echo "STEP $tag $(date +%T)"
  ./$sc --hardwood-version $F --machine "$MACH" --no-pin --forks 1 --time 2 "$@" > $W/$tag.log 2>&1
  echo "  rc=$?"; tail -n +2 target/bench-throughput-$b.tsv > $W/$tag.rows; cp target/bench-meta-$b.tsv $W/$tag.meta; cut -f3,4 $W/$tag.rows | tr '\n' ' '; echo; }
inv flat-base run-flat.sh FlatScanBenchmark --start 2025-01 --end 2025-01 --warmup 3 --meas 5 --include 'parquetJavaColumnar|avroParquetReaderIndexed|avroParquetReaderNamed'
grep -h -o 'parquet-hadoop-[0-9.]*jar' target/build/${F}_flat/cp.txt | head -1
inv filter-base run-filter.sh FilterBenchmark --warmup 3 --meas 5 --include 'parquetJava$'
grep -h -o 'parquet-hadoop-[0-9.]*jar' target/build/${F}_filter/cp.txt 2>/dev/null | head -1
sudo profiling-setup restore > $W/restore.log 2>&1; echo "restored rc=$? $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq)"
echo DONE $(date +%T)
