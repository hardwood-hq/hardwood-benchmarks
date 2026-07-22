# Benchmark results, by publication

Archived raw output is organised one directory per publication (a release or blog
post), named `<YYYY-MM-DD>-<slug>`:

```
results/
  2026-06-25-hardwood-1.0/       # the 1.0 flat + filter runs (see its README for provenance)
    run-1/ run-2/ run-3/         # three runs; the median is the published one
  2026-07-22-fixed-size-list/    # the fixed-size-list fast-path post (see its README)
    headline/ headline-plain/    # several experiments, each run-1..3 + a derived median/
    sweep/                       #   snapshot published per point (see below)
```

Each `run-N/` is a self-contained, chartable snapshot — TSVs, `bench-meta`
sidecars, and per-benchmark logs — produced by `./capture-run.sh
results/<publication>/run-N`. A publication may span several benchmarks (1.0
captures flat + filter together in each run). Where three runs are published as a
median, the `median/` snapshot is derived with `charts/median-runs.py` (per-point
median across the runs). Charts are regenerated from the TSVs with the
per-benchmark generators (`charts/make-flat-chart.py`, `make-filter-chart.py`,
`make-fixedlist-chart.py`) and are git-ignored — they rebuild byte-for-byte from
the archived results.

Each publication directory carries its own `README.md` with the exact provenance
(versions, host, dataset, which run is published).
