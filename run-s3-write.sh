#!/usr/bin/env bash
#
# S3 write benchmark: Hardwood's S3 output against parquet-java over S3A, writing to a local S3
# endpoint that emulates upload latency and bandwidth (s3-env.sh, port 18082). See
# ./run-s3-write.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"

# As in run-s3.sh: the endpoint runs on one core, the benchmark on the others.
if [[ -z "${BENCH_S3_AFFINITY:-}" ]] && command -v taskset > /dev/null 2>&1; then
  if [[ -z "${BENCH_S3_CPUS:-}" ]]; then
    env_cpu="$(./s3-env.sh cpu)"
    for (( c = 0; c < $(nproc --all); c++ )); do
      if (( c != env_cpu )); then
        BENCH_S3_CPUS="${BENCH_S3_CPUS:+$BENCH_S3_CPUS,}$c"
      fi
    done
    if [[ -z "${BENCH_S3_CPUS:-}" ]]; then
      echo "run-s3-write.sh needs a core for the benchmark besides the endpoint's (core $env_cpu)" >&2
      exit 2
    fi
  fi
  export BENCH_S3_AFFINITY="$BENCH_S3_CPUS"
  exec taskset -c "$BENCH_S3_AFFINITY" "$PWD/${0##*/}" "$@"
fi

source ./bench-common.sh

BENCH_PACKAGE='write'
BENCH_REGRESSION_PRESET=''
BENCH_REGRESSION_INCLUDE='hardwood'
# The S3 contenders' upload pools want more than one core, and the benchmark already runs on all
# but the endpoint's.
BENCH_SINGLE_CORE=0
BENCH_FLAGS='--rows perf.rows
--codec perf.param'
BENCH_USAGE="S3 write benchmark (generated taxi-shaped records, written to an emulated object store).

Usage: ./run-s3-write.sh [options]
  --rows N              records per file (default 4000000)
  --codec VALUE         SNAPPY | ZSTD (default ZSTD)
$BENCH_COMMON_USAGE

Contenders: hardwoodS3 (S3Source.outputFile) and parquetJavaS3 (ExampleParquetWriter over S3A),
each beside the same write to memory (hardwoodMemory, parquetJavaMemory). Both S3 contenders use
8 MiB parts. The endpoint is S3Proxy behind Toxiproxy, started by ./s3-env.sh unless already
running (S3_LATENCY_MS, default 30; S3_BANDWIDTH_KBPS, default 81920 per connection, applied to
uploads on the write port). Before timing, both S3 objects are read back and checked."

bench_parse_args "$@"
if [[ "$(./s3-env.sh status)" != running ]]; then
  ./s3-env.sh start
  bench_on_exit './s3-env.sh stop'
fi
settings="$(./s3-env.sh settings)"
read -r latency bandwidth <<< "$settings"
ARGS+=("-Dperf.s3.endpoint=http://127.0.0.1:18082" "-Dperf.s3.latencyMs=$latency" "-Dperf.s3.bandwidthKBps=$bandwidth")
bench_build
bench_run dev.hardwood.benchmarks.write.S3WriteBenchmark
bench_epilogue
