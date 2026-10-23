#!/usr/bin/env bash
#
# Quick gate of a head Hardwood version against a base, on the S3 benchmark: the requests and
# bytes of every S3 read, and optionally the time of a few contenders. See ./run-gate.sh --help.
#
set -uo pipefail
cd "$(dirname "$0")"

INCLUDE='hardwood(ProjectedScan|FilteredScan|ManySmallFiles)$'
ITERATIONS=(--warmup 2 --meas 4 --time 2)
MIN_BAND=5

usage() {
  cat <<EOF
Quick gate of a head Hardwood version against a base, on the S3 benchmark.

Usage: ./run-gate.sh [options] BASE HEAD
  BASE, HEAD        hardwood-core versions as --hardwood-version takes them; the same version
                    twice measures the gate's own noise
  --counts-only     only the requests and bytes, from run-s3.sh --gate at 0 ms (about 1 min)
  --rounds N        timed rounds (default 2); each runs BASE, then HEAD
  --out DIR         where the snapshots, logs and verdict go (default target/gate)
  --fail-on-regression
                    exit 1 when a read makes more requests or fetches more bytes, or a timed
                    contender is slower beyond its band

Counts: every read the S3 benchmark checks before timing (projected, filtered, wide filtered,
the two Bloom lookups, the multi-file scan, many small files) records its requests and bytes,
which are exact. Any increase is reported as MORE.

Timed (without --counts-only): $INCLUDE at 30 ms emulated latency, with ${ITERATIONS[*]},
compared by compare-runs.py with a band of at least $MIN_BAND %. About 3.5 min for two rounds.
Latency dominates these reads, so the container times them steadily; local reads are not
timed here, since short runs of them on the container vary by 20 % and more between rounds.
EOF
}

ROUNDS=2
OUT=target/gate
FAIL=""
COUNTS_ONLY=""
VERSIONS=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --counts-only) COUNTS_ONLY=1; shift ;;
    --rounds) ROUNDS="${2:?--rounds needs a value}"; shift 2 ;;
    --out) OUT="${2:?--out needs a value}"; shift 2 ;;
    --fail-on-regression) FAIL="--fail-on-regression"; shift ;;
    -*) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    *) VERSIONS+=("$1"); shift ;;
  esac
done
if (( ${#VERSIONS[@]} != 2 )); then
  echo "Name a base and a head version (try --help)" >&2
  exit 2
fi
BASE=${VERSIONS[0]}
HEAD=${VERSIONS[1]}

if [[ "$(./s3-env.sh status)" == running ]]; then
  echo "The S3 endpoint is already running, perhaps for another run: stop it or wait" >&2
  exit 2
fi
LATENCY=30
[[ -n "$COUNTS_ONLY" ]] && LATENCY=0
S3_LATENCY_MS=$LATENCY ./s3-env.sh start > /dev/null
# s3-env.sh stop can leave an S3Proxy JVM running in the dev container. The gate started this
# endpoint, as it refuses to run beside another, so whatever still runs from its directory is its.
stop_endpoint() {
  ./s3-env.sh stop > /dev/null
  pkill -f "$PWD/target/s3-env/" 2> /dev/null || true
}
trap stop_endpoint EXIT

rm -rf "$OUT"
mkdir -p "$OUT/base" "$OUT/head"
progress="$OUT/progress.log"
started=$(date +%s)

step() {
  echo "$1  exit $2  $3s" | tee -a "$progress"
}

# Counts: the "Gate passed" line of each read, as name<TAB>requests<TAB>bytes
counts() {
  python3 - "$1" <<'PY'
import re, sys
plain = re.compile(r"^Gate passed \[(.+?)\] .*?\(([\d,]+) requests, ([\d,]+) bytes")
small = re.compile(r"^Gate passed \[(many small files)\] .*?; ([\d,]+) requests, [\d.]+ per file .*, ([\d,]+) bytes\)")
seen = {}
for line in open(sys.argv[1]):
    m = small.match(line) or plain.match(line)
    if m:
        seen[m.group(1)] = (m.group(2).replace(",", ""), m.group(3).replace(",", ""))
for name in sorted(seen):
    print("\t".join((name,) + seen[name]))
PY
}

if [[ -n "$COUNTS_ONLY" ]]; then
  for side in base head; do
    v=$BASE
    [[ $side == head ]] && v=$HEAD
    t=$(date +%s)
    BENCH_LOG=0 S3_LATENCY_MS=0 ./run-s3.sh --hardwood-version "$v" --gate > "$OUT/$side/s3.log" 2>&1
    step "$side  s3 --gate" $? $(( $(date +%s) - t ))
    counts "$OUT/$side/s3.log" > "$OUT/$side/counts.tsv"
  done
else
  for round in $(seq 1 "$ROUNDS"); do
    for side in base head; do
      v=$BASE
      [[ $side == head ]] && v=$HEAD
      dest="$OUT/$side/run-$round"
      mkdir -p "$dest"
      rm -f target/bench-throughput-*.tsv target/bench-meta-*.tsv
      t=$(date +%s)
      BENCH_LOG=0 S3_LATENCY_MS=$LATENCY ./run-s3.sh --hardwood-version "$v" --include "$INCLUDE" "${ITERATIONS[@]}" \
        > "$dest/s3.log" 2>&1
      rc=$?
      cp target/bench-throughput-*.tsv target/bench-meta-*.tsv "$dest/" 2>/dev/null
      step "$side/run-$round  s3" $rc $(( $(date +%s) - t ))
    done
    if (( round == 1 )); then
      for side in base head; do
        counts "$OUT/$side/run-1/s3.log" > "$OUT/$side/counts.tsv"
      done
    fi
  done
fi
echo "total $(( $(date +%s) - started ))s" | tee -a "$progress"

status=0
: > "$OUT/verdict.txt"
if [[ -z "$COUNTS_ONLY" ]]; then
  python3 charts/compare-runs.py "$OUT/base" "$OUT/head" --min-band "$MIN_BAND" $FAIL > "$OUT/verdict.txt" \
    || status=1
fi

python3 - "$OUT/base/counts.tsv" "$OUT/head/counts.tsv" >> "$OUT/verdict.txt" <<'EOF' || status=1
import sys

def load(path):
    rows = {}
    with open(path) as f:
        for line in f:
            name, requests, size = line.rstrip("\n").split("\t")
            rows[name] = (int(requests), int(size))
    return rows

base, head = load(sys.argv[1]), load(sys.argv[2])
if not base or set(base) != set(head):
    print("\nThe two runs checked different reads: {} vs {}".format(sorted(base), sorted(head)))
    sys.exit(1)
more = False
print("\nS3 reads, requests and bytes (base -> head)")
for name in sorted(base):
    (br, bb), (hr, hb) = base[name], head[name]
    flag = "  MORE" if hr > br or hb > bb else "  fewer" if hr < br or hb < bb else ""
    more = more or hr > br or hb > bb
    print("  {:<22} {:>6} -> {:<6} requests {:>12} -> {:<12} bytes{}".format(name, br, hr, bb, hb, flag))
sys.exit(1 if more else 0)
EOF

cat "$OUT/verdict.txt"
if [[ -n "$FAIL" ]]; then
  exit $status
fi
