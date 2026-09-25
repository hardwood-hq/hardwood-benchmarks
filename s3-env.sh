#!/usr/bin/env bash
#
# A local, latency-emulating S3 endpoint for run-s3.sh: S3Proxy (filesystem-backed, the
# version the Hardwood S3 tests run as a container) behind Toxiproxy, which adds a first-byte
# latency and a per-connection bandwidth cap to every response. Both run as plain processes on
# the loopback interface, pinned to one core where taskset exists, and live under
# target/s3-env/ (downloaded on first use). Nothing is installed.
#
#   ./s3-env.sh start | stop | status | settings | cpu
#
# S3_LATENCY_MS (default 30) and S3_BANDWIDTH_KBPS (default 81920, i.e. 80 MB/s per connection)
# set the emulated storage; S3_ENV_CPU (default: the machine's last core) the core both
# processes run on. `settings` prints the latency and bandwidth the running endpoint applies,
# `cpu` the core it runs on.
set -euo pipefail
cd "$(dirname "$0")"

S3PROXY_VERSION=3.1.0
TOXIPROXY_VERSION=2.12.0
DIR="$PWD/target/s3-env"
DATA="$DIR/data"
S3PROXY_PORT=18080
TOXI_API_PORT=18474
S3_PORT=18081
LATENCY_MS="${S3_LATENCY_MS:-30}"
BANDWIDTH_KBPS="${S3_BANDWIDTH_KBPS:-81920}"

# A command prefix rather than a function: a backgrounded function runs in a subshell, and $!
# would name that subshell instead of the server, which `stop` then could not reach. `nproc
# --all` rather than `nproc`: run-s3.sh calls this with its own affinity already narrowed.
PIN=()
CPU=""
if command -v taskset > /dev/null 2>&1; then
  CPU="${S3_ENV_CPU:-$(( $(nproc --all) - 1 ))}"
  PIN=(taskset -c "$CPU")
fi

toxiproxy_platform() {
  local os arch
  case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=darwin ;;
    *) echo "s3-env: no Toxiproxy build for $(uname -s)" >&2; return 1 ;;
  esac
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) echo "s3-env: no Toxiproxy build for $(uname -m)" >&2; return 1 ;;
  esac
  echo "$os-$arch"
}

fetch() {
  mkdir -p "$DIR" "$DATA/test-bucket"
  if [ ! -f "$DIR/s3proxy-$S3PROXY_VERSION.jar" ]; then
    curl -fsSL -o "$DIR/s3proxy-$S3PROXY_VERSION.jar" \
      "https://github.com/gaul/s3proxy/releases/download/s3proxy-$S3PROXY_VERSION/s3proxy"
  fi
  if [ ! -x "$DIR/toxiproxy-server-$TOXIPROXY_VERSION" ]; then
    local platform
    platform="$(toxiproxy_platform)"
    curl -fsSL -o "$DIR/toxiproxy-server-$TOXIPROXY_VERSION.part" \
      "https://github.com/Shopify/toxiproxy/releases/download/v$TOXIPROXY_VERSION/toxiproxy-server-$platform"
    chmod +x "$DIR/toxiproxy-server-$TOXIPROXY_VERSION.part"
    mv "$DIR/toxiproxy-server-$TOXIPROXY_VERSION.part" "$DIR/toxiproxy-server-$TOXIPROXY_VERSION"
  fi
}

running() {
  curl -fs "http://127.0.0.1:$TOXI_API_PORT/version" > /dev/null 2>&1
}

wait_for() {
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null "$1"; then return 0; fi
    sleep 0.5
  done
  echo "s3-env: $1 did not come up" >&2
  return 1
}

start() {
  if running; then
    echo "s3-env: already running"
    return 0
  fi
  fetch
  cat > "$DIR/s3proxy.conf" <<CONF
s3proxy.endpoint=http://127.0.0.1:$S3PROXY_PORT
s3proxy.authorization=aws-v2-or-v4
s3proxy.identity=access
s3proxy.credential=secret
jclouds.provider=filesystem
jclouds.filesystem.basedir=$DATA
CONF
  "${PIN[@]+"${PIN[@]}"}" java -jar "$DIR/s3proxy-$S3PROXY_VERSION.jar" --properties "$DIR/s3proxy.conf" > "$DIR/s3proxy.log" 2>&1 &
  echo $! > "$DIR/s3proxy.pid"
  "${PIN[@]+"${PIN[@]}"}" "$DIR/toxiproxy-server-$TOXIPROXY_VERSION" -host 127.0.0.1 -port "$TOXI_API_PORT" > "$DIR/toxiproxy.log" 2>&1 &
  echo $! > "$DIR/toxiproxy.pid"
  wait_for "http://127.0.0.1:$S3PROXY_PORT/"
  wait_for "http://127.0.0.1:$TOXI_API_PORT/version"
  api="http://127.0.0.1:$TOXI_API_PORT"
  curl -fsS -X POST "$api/proxies" -o /dev/null \
    -d "{\"name\":\"s3\",\"listen\":\"127.0.0.1:$S3_PORT\",\"upstream\":\"127.0.0.1:$S3PROXY_PORT\"}"
  curl -fsS -X POST "$api/proxies/s3/toxics" -o /dev/null \
    -d "{\"name\":\"latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$LATENCY_MS,\"jitter\":0}}"
  curl -fsS -X POST "$api/proxies/s3/toxics" -o /dev/null \
    -d "{\"name\":\"bandwidth\",\"type\":\"bandwidth\",\"stream\":\"downstream\",\"attributes\":{\"rate\":$BANDWIDTH_KBPS}}"
  echo "s3-env: http://127.0.0.1:$S3_PORT (latency ${LATENCY_MS} ms, ${BANDWIDTH_KBPS} KB/s per connection, core ${CPU:-unpinned})"
}

# The latency (ms) and bandwidth (KB/s) the running endpoint's toxics apply, read back from
# Toxiproxy rather than taken from the environment, which may differ from the one it was
# started in.
settings() {
  curl -fsS "http://127.0.0.1:$TOXI_API_PORT/proxies/s3/toxics" | python3 -c '
import json, sys
toxics = {t["type"]: t["attributes"] for t in json.load(sys.stdin)}
print(toxics["latency"]["latency"], toxics["bandwidth"]["rate"])'
}

stop() {
  for p in toxiproxy s3proxy; do
    if [ -f "$DIR/$p.pid" ]; then
      kill "$(cat "$DIR/$p.pid")" 2> /dev/null || true
      rm -f "$DIR/$p.pid"
    fi
  done
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) if running; then echo running; else echo stopped; fi ;;
  settings) settings ;;
  cpu) echo "$CPU" ;;
  *) echo "usage: $0 start | stop | status | settings | cpu" >&2; exit 2 ;;
esac
