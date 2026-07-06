# fixed-size-list fast path — results (WIP)

Awaiting a clean measurement run (the n300 bare-metal box) for the blog post. Two
runs, captured separately — they use different file sizes and write the same
`target/` TSV, so the headline run overwrites the sweep unless it is captured first:

    # sweep → speedup-vs-k curve (32 MB files)
    ./run-fixedlist.sh --forks 3 --meas 5
    ./capture-run.sh results/2026-07-03-fixed-size-list/sweep
    python3 charts/make-fixedlist-chart.py --results-dir results/2026-07-03-fixed-size-list/sweep

    # two headline points → absolute-throughput bars (512 MB files)
    ./run-fixedlist.sh --k 3,768 --total-values 128000000 --forks 3 --meas 5
    ./capture-run.sh results/2026-07-03-fixed-size-list/headline
    python3 charts/make-fixedlist-bars-chart.py --results-dir results/2026-07-03-fixed-size-list/headline

Rename this directory to the actual publication date/slug when the post ships.
