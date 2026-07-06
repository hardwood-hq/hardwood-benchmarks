#!/usr/bin/env bash
#
# Snapshot a benchmark run's artifacts into a publication/run directory, filenames
# unchanged, so the run becomes a self-contained, chartable archive: the throughput
# TSVs, the meta sidecars, and the per-benchmark logs (target/{flat,filter,...}.log).
# It archives every bench-* artifact in target/, which is what a multi-benchmark
# publication wants (e.g. flat + filter together); run `mvn clean` before a fresh
# publication's runs so no stale sibling TSV is swept in (the file list printed
# below makes a stray one obvious). Render each captured benchmark with its
# generator, e.g.:
#   python3 charts/make-flat-chart.py   --results-dir <dir>
#   python3 charts/make-filter-chart.py --results-dir <dir>
#
# Usage: ./capture-run.sh <dir>   e.g.  ./capture-run.sh results/2026-06-25-hardwood-1.0/run-2
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
