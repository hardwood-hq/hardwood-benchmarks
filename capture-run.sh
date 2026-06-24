#!/usr/bin/env bash
#
# Snapshot a benchmark run's artifacts into a directory, filenames unchanged, so the
# run becomes a self-contained, chartable archive: the throughput TSVs, the meta
# sidecars, and the per-benchmark logs (target/{flat,filter,...}.log). Render it
# later with:
#   python3 charts/make-charts.py --results-dir <dir>
#
# Usage: ./capture-run.sh <dir>        e.g.  ./capture-run.sh results/run-2
#
set -euo pipefail
cd "$(dirname "$0")"

dest="${1:?usage: ./capture-run.sh <dir>}"

shopt -s nullglob
artifacts=( target/bench-throughput-*.tsv target/bench-meta-*.tsv target/*.log )
if (( ${#artifacts[@]} == 0 )); then
  echo "capture-run: no artifacts under target/ — run a benchmark first." >&2
  exit 1
fi

mkdir -p "$dest"
cp -p "${artifacts[@]}" "$dest"/
echo "Captured ${#artifacts[@]} file(s) into $dest/:"
printf '  %s\n' "${artifacts[@]##*/}"
