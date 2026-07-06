#!/usr/bin/env bash
#
# Fixed-size-list fast-path benchmark: a full scan of a LIST<float32> column of
# fixed-width vectors, fast path on vs off, across a sweep of vector lengths k,
# through both the column reader and the row reader. Every contender is Hardwood
# (fast vs baseline vs flat floor), so the run is all-cores only — no single-core
# pass. The corpus is generated on demand. See ./run-fixedlist.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

BENCH_FLAGS='--data-dir perf.dataDir
--total-values perf.totalValues
--k perf.k'
BENCH_USAGE="Fixed-size-list fast-path benchmark (LIST<float32> vectors, speedup vs k).

Usage: ./run-fixedlist.sh [options]
  --data-dir PATH     corpus cache directory (default target/fixed-size-list-data);
                      files are generated on first run and reused
  --total-values N    leaf floats per file (default 8M; ~128M ~= 512 MB per file)
  --k CSV             restrict to specific k (e.g. 768, or 3,768 for the two
                      headline ~512 MB files); default is the full sweep
$BENCH_COMMON_USAGE

Two modes: --gate generates the corpus, reads each k with the fast path on and
off, and verifies both readers fold to the same sum as the flat floor, then exits
(no JMH); without it, the script generates (if needed) and benchmarks.

Quick publish flow — two runs at different file sizes. Each run truncates target/'s
TSV, so chart (or capture) the sweep before launching the headline run:
  ./run-fixedlist.sh --gate
  # sweep -> speedup-vs-k curve (32 MB files):
  ./run-fixedlist.sh --forks 3 --meas 5
  python charts/make-fixedlist-chart.py --results-dir target
  # two headline ~512 MB files -> absolute-throughput bars:
  ./run-fixedlist.sh --k 3,768 --total-values 128000000 --forks 3 --meas 5
  python charts/make-fixedlist-bars-chart.py --results-dir target

Publication run (evaluation instance) — three runs for the median, detached under
tmux so an SSH drop can't kill it; sweep and headline are captured to separate dirs
(they share a TSV filename). Copy/paste:
  tmux new -d -s fixedlist '
    for i in 1 2 3; do
      ./run-fixedlist.sh --forks 3 --meas 5
      ./capture-run.sh \"results/2026-07-03-fixed-size-list/sweep/run-\$i\"
      ./run-fixedlist.sh --k 3,768 --total-values 128000000 --forks 3 --meas 5
      ./capture-run.sh \"results/2026-07-03-fixed-size-list/headline/run-\$i\"
      [ \"\$i\" -lt 3 ] && sleep 300   # 5-min break between runs, not after the last
    done'
  # reattach:  tmux attach -t fixedlist    (detach: Ctrl-b d)"

bench_parse_args "$@"
# All contenders are Hardwood (fast path vs. baseline), so there is no
# engine-for-engine single-core number to take — this benchmark has no pinned pass
# (and the harness prints no "skipped single-core" note for it).
BENCH_SINGLE_CORE=0
bench_build
bench_run dev.hardwood.benchmarks.fixedlist.FixedSizeListScanBenchmark
bench_epilogue
