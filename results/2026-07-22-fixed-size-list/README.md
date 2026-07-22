# Published benchmark runs — fixed-size-list fast path

Archived raw output behind the blog post *"Faster Fixed-Length List Reading for
Apache Parquet"*, kept for transparency and reproducibility. The post measures
Hardwood's fixed-size-list fast path (which detects effectively-fixed lists from
the encoded rep/def levels and bypasses Dremel reconstruction) against the regular
reconstruction path, with a plain flat-column read as the floor.

Three independent experiments were run; each `run-N/` is a self-contained,
chartable snapshot (TSVs + `bench-meta` sidecars + per-benchmark logs):

```
headline/            scan of a LIST<float32> column at n=3 and n=768, ZSTD, 128M values (512 MB)
  run-1/ run-2/ run-3/   columnBaseline/Fast, rowBaseline/Fast, flatFloor
  median/                per-point median of the three runs — THE PUBLISHED figures (bars chart)

headline-plain/      the same, UNCOMPRESSED — the "also ran it without compression" comparison
  run-1/ run-2/ run-3/

sweep/               fast-path speedup swept across n = 1..1536 (columnBaseline/Fast, rowBaseline/Fast)
  run-1/ run-2/ run-3/          exploratory 8M-value files (32 MB) — superseded, kept for history
  run-128M-1/ -2/ -3/           128M-value files (512 MB), matching the headline
  median-128M/                  per-point median of the three 128M runs — THE PUBLISHED sweep chart

decode-benchmark.{json,log}     detector cost when detection SUCCEEDS (isolated rep-scan vs full decode)
fallback-benchmark.{json,log}   detector cost when detection FAILS (wasted scan then fallback)
```

The two micro-benchmarks (`decode-`, `fallback-`) come from the
`performance-testing/micro-benchmarks` module in the main Hardwood repo, not from
this repo's scan harness; their raw JMH JSON + console logs are archived here
because the post links them.

`median/` and `median-128M/` are derived — regenerate them from the runs with
`charts/median-runs.py` (see below). The `run-N/charts/` and `median*/charts/`
directories are git-ignored; they rebuild byte-for-byte from the TSVs + sidecars.

## Regenerate the medians and charts

```sh
# from the repo root — rebuild the published median snapshots:
python3 charts/median-runs.py results/2026-07-22-fixed-size-list/headline/median \
    results/2026-07-22-fixed-size-list/headline/run-1 \
    results/2026-07-22-fixed-size-list/headline/run-2 \
    results/2026-07-22-fixed-size-list/headline/run-3
python3 charts/median-runs.py results/2026-07-22-fixed-size-list/sweep/median-128M \
    results/2026-07-22-fixed-size-list/sweep/run-128M-1 \
    results/2026-07-22-fixed-size-list/sweep/run-128M-2 \
    results/2026-07-22-fixed-size-list/sweep/run-128M-3

# render the published charts (bars from the headline median, speedup-vs-n from the sweep median):
python3 charts/make-fixedlist-bars-chart.py --results-dir results/2026-07-22-fixed-size-list/headline/median
python3 charts/make-fixedlist-chart.py      --results-dir results/2026-07-22-fixed-size-list/sweep/median-128M
```

## Provenance

| | |
|---|---|
| Hardwood | `1.1.0-SNAPSHOT` @ [`1ad9b7a`](https://github.com/hardwood-hq/hardwood/commit/1ad9b7a) |
| parquet-java | 1.17.1 (corpus writer; files are read by Hardwood) |
| JDK | OpenJDK 25 (Temurin / Eclipse Adoptium) |
| JMH | 1.37 |
| Host | AWS m7i.2xlarge (8 vCPU / 4 physical cores; 32 GB), warm page cache |
| Date | 2026-07-22 (UTC) |
| Scan config | `--forks 3 --meas 5` (all-cores pass); fast path toggled via `hardwood.fixed-list-fast-path` |
| Micro config | JMH `@Fork(5)`, 5 measurement iterations |
| Dataset | generated float32 vectors, deterministic (`Random(1234 + k)`); one file per width, `totalValues` leaf floats each |

Sizes are decimal (MB = 10⁶ bytes). A bit-identical correctness gate runs before
any timing: the fast path and the regular path must decode every element
identically (through both readers) or the run fails.

## Which run is published

**The per-point median across the three runs is published**, not a single run —
computed by `charts/median-runs.py`, which takes the median at each
contender/width independently. This is the robust choice because the
reconstruction **baseline** swings run-to-run (`columnBaseline[768]` spans
301–437 ms across the three headline runs, ~30%), while the fast path and the flat
floor are stable to <1%. The median settles the noisy baseline without disturbing
the rest; the individual `run-N/` are kept as the evidence for that spread.

The `sweep/run-{1,2,3}` (8M-value) snapshots are the earlier, smaller-file
exploration; the post's sweep chart uses `sweep/median-128M`, whose file size
matches the headline so the two charts are directly comparable.
