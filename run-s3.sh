#!/usr/bin/env bash
#
# S3 read benchmark (regression-only): Hardwood's column reader over a local S3 endpoint that
# emulates object-storage latency and bandwidth (s3-env.sh). See ./run-s3.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"

# The S3 endpoint runs on one core (s3-env.sh, S3_ENV_CPU, default the last one); the benchmark
# runs on the others, so serving requests does not compete with reading them. The re-exec names
# the script from the directory changed into above, since a relative $0 no longer resolves.
if [[ -z "${BENCH_S3_AFFINITY:-}" ]] && command -v taskset > /dev/null 2>&1; then
  if [[ -z "${BENCH_S3_CPUS:-}" ]]; then
    env_cpu="$(./s3-env.sh cpu)"
    for (( c = 0; c < $(nproc --all); c++ )); do
      if (( c != env_cpu )); then
        BENCH_S3_CPUS="${BENCH_S3_CPUS:+$BENCH_S3_CPUS,}$c"
      fi
    done
    if [[ -z "${BENCH_S3_CPUS:-}" ]]; then
      echo "run-s3.sh needs a core for the benchmark besides the endpoint's (core $env_cpu)" >&2
      exit 2
    fi
  fi
  export BENCH_S3_AFFINITY="$BENCH_S3_CPUS"
  exec taskset -c "$BENCH_S3_AFFINITY" "$PWD/${0##*/}" "$@"
fi

source ./bench-common.sh

BENCH_PACKAGE='s3'
PYARROW_VERSION=24.0.0
NUMPY_VERSION=2.4.6
SMALL_FILES_COUNT=200
SMALL_FILES_MIN_KIB=100
SMALL_FILES_MAX_KIB=5120
SMALL_FILES_SEED=1663
BENCH_REGRESSION_PRESET='--warmup 5'
# hardwoodManySmallFiles stays out: one read of it takes about 8 s at 30 ms, longer than another
# contender's whole preset.
BENCH_REGRESSION_INCLUDE='hardwood(?!ManySmallFiles)'
# Every contender is Hardwood, and the benchmark already runs on all but the endpoint's core.
BENCH_SINGLE_CORE=0
BENCH_FLAGS=''
BENCH_USAGE="S3 read benchmark, regression-only (column reader over an emulated object store).

Usage: ./run-s3.sh [options]
$BENCH_COMMON_USAGE
Regression preset (--regression): --warmup 5 (requests over the emulated latency take
longer to settle); every contender but hardwoodManySmallFiles, whose single read takes
longer than another contender's whole preset (--include hardwoodManySmallFiles times it);
the dataset has one size

The endpoint is S3Proxy behind Toxiproxy, started by ./s3-env.sh unless already running
(S3_LATENCY_MS, default 30; S3_BANDWIDTH_KBPS, default 81920 per connection). A running
endpoint with other settings is an error. Before
timing, each read is checked against the local file, and its request and byte counts
are recorded in the meta sidecar.

hardwoodManySmallFiles reads $SMALL_FILES_COUNT files of ${SMALL_FILES_MIN_KIB} KiB to ${SMALL_FILES_MAX_KIB} KiB, written on first use by
PyArrow $PYARROW_VERSION and NumPy $NUMPY_VERSION in a virtualenv under target/ (needs python3
with venv) on every run outside --regression. One read takes about 8 s at 30 ms and 50 s at
235 ms, where this run takes about 3 min:
  S3_LATENCY_MS=235 ./run-s3.sh --include hardwoodManySmallFiles --warmup 0 --meas 2 --time 1"

bench_parse_args "$@"
# The many-small-files fixture, outside --regression, which leaves its contender out. Its directory
# names every input it is written with: the parameters, the pinned versions and a checksum of the
# generator. The virtualenv, renamed into place once its install
# succeeded, holds the pinned PyArrow and NumPy.
if [[ -z "${BENCH_REGRESSION:-}" ]]; then
  small_files="target/small_files_n${SMALL_FILES_COUNT}_${SMALL_FILES_MIN_KIB}k-${SMALL_FILES_MAX_KIB}k_seed${SMALL_FILES_SEED}_pyarrow${PYARROW_VERSION}_numpy${NUMPY_VERSION}_gen$(cksum < python/small_files.py | cut -d' ' -f1)"
  if [[ ! -f "$small_files/manifest.tsv" ]]; then
    venv="target/py-venv-pyarrow-$PYARROW_VERSION-numpy-$NUMPY_VERSION"
    if [[ ! -x "$venv/bin/python" ]]; then
      rm -rf "$venv.tmp"
      python3 -m venv "$venv.tmp"
      "$venv.tmp/bin/pip" -q install "pyarrow==$PYARROW_VERSION" "numpy==$NUMPY_VERSION"
      mv "$venv.tmp" "$venv"
    fi
    echo "Generating $SMALL_FILES_COUNT small files at $small_files..."
    "$venv/bin/python" -I python/small_files.py "$small_files" "$SMALL_FILES_COUNT" "$SMALL_FILES_MIN_KIB" \
      "$SMALL_FILES_MAX_KIB" "$SMALL_FILES_SEED"
  fi
  ARGS+=("-Dperf.s3.smallFiles=$PWD/$small_files")
fi
if [[ "$(./s3-env.sh status)" != running ]]; then
  ./s3-env.sh start
  bench_on_exit './s3-env.sh stop'
fi
# An endpoint already running may have been started with other settings than this environment
# asks for; the sidecar records what the endpoint applies, and a mismatch is an error.
settings="$(./s3-env.sh settings)"
read -r latency bandwidth <<< "$settings"
if [[ "$latency" != "${S3_LATENCY_MS:-30}" || "$bandwidth" != "${S3_BANDWIDTH_KBPS:-81920}" ]]; then
  echo "The running S3 endpoint applies ${latency} ms and ${bandwidth} KB/s, not the requested" \
    "${S3_LATENCY_MS:-30} ms and ${S3_BANDWIDTH_KBPS:-81920} KB/s; run ./s3-env.sh stop first" >&2
  exit 2
fi
ARGS+=("-Dperf.s3.endpoint=http://127.0.0.1:18081" "-Dperf.s3.dataDir=$PWD/target/s3-env/data"
       "-Dperf.s3.latencyMs=$latency" "-Dperf.s3.bandwidthKBps=$bandwidth")
bench_build
bench_run dev.hardwood.benchmarks.s3.S3ScanBenchmark
bench_epilogue
