#!/usr/bin/env bash
#
# PyArrow and DuckDB beside the S3 write benchmark: they rewrite the file run-s3-write.sh's gate
# wrote (target/s3-write-fixture.parquet) to memory or a local file and to the emulated object
# store's write port (s3-env.sh, 18082), on the cores the endpoint does not use. Pinned versions
# live in target/py-venv, created on first use.
#
#   ./run-s3-write.sh --gate && ./run-s3-write-python.sh
#
# PERF_WARMUP (default 2) and PERF_MEAS (default 5) set the runs per contender.
set -euo pipefail
cd "$(dirname "$0")"

PYARROW_VERSION=24.0.0
DUCKDB_VERSION=1.5.6
VENV=target/py-venv
if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" -q install "pyarrow==$PYARROW_VERSION" "duckdb==$DUCKDB_VERSION"
fi

started=0
if [[ "$(./s3-env.sh status)" != running ]]; then
  ./s3-env.sh start
  started=1
fi
trap '[[ $started == 1 ]] && ./s3-env.sh stop' EXIT

# The AWS SDK inside PyArrow adds CRC checksums to uploads by default, which S3Proxy rejects;
# S3 accepts uploads without them.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

pin=()
if command -v taskset > /dev/null 2>&1; then
  env_cpu="$(./s3-env.sh cpu)"
  cpus=""
  for (( c = 0; c < $(nproc --all); c++ )); do
    (( c != env_cpu )) && cpus="${cpus:+$cpus,}$c"
  done
  pin=(taskset -c "$cpus")
fi
"${pin[@]+"${pin[@]}"}" "$VENV/bin/python" python/s3_write_compare.py
