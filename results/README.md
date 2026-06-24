# Published benchmark runs

Archived raw output of the 1.0 performance runs, kept for transparency and
reproducibility. Each `run-N/` is a self-contained, chartable snapshot — filenames
unchanged — produced by `./capture-run.sh results/run-N`:

```
run-N/bench-throughput-FlatScanBenchmark.tsv     # flat scan, ms/op + throughput, both passes
run-N/bench-throughput-FilterBenchmark.tsv       # filtered scan, ms/op
run-N/bench-meta-FlatScanBenchmark.tsv           # rows / on-disk size / JVM / date window
run-N/bench-meta-FilterBenchmark.tsv
run-N/flat.log  run-N/filter.log                 # full JMH console output (per-iteration values, env block)
```

Regenerate the charts for any run (output lands in `run-N/charts/`, which is
git-ignored — it rebuilds byte-for-byte from the TSVs + sidecars):

```sh
python3 ../charts/make-charts.py --results-dir run-N      # from this directory
# or, from the repo root:
python3 charts/make-charts.py --results-dir results/run-N
```

## Provenance

| | |
|---|---|
| Hardwood | `1.0.0-SNAPSHOT` @ [`fdcc10e`](https://github.com/hardwood-hq/hardwood/commit/fdcc10e52303393fc5a6ae3abe517864746a3404) |
| parquet-java | 1.17.1 |
| JDK | OpenJDK 25 (Temurin / Eclipse Adoptium), 25+36-LTS |
| JMH | 1.37 |
| Host | AWS m7i.2xlarge (8 vCPU / 4 physical cores), warm page cache |
| Date | 2026-06-23/24 (UTC) |
| Config | `--forks 5 --meas 10` (50 measured iterations/contender); per-core pass via `taskset -c 0` |
| Flat dataset | NYC Yellow Taxi 2025-01–2025-12 — 48,722,602 rows, 830 MB |
| Filter dataset | generated time-clustered event log — 50,000,000 rows, 1,038 MB |

Sizes and throughput are decimal (MB = 10⁶ bytes).

## Which run is published

**`run-2` is the published run** — the median of the three. The runs are clustered
runs 1 and 2 with run 3 ~6–11% faster across *every* contender (scalar and parallel
alike): a uniform host-frequency effect on the shared instance, not a Hardwood
change. Because it scales all contenders together, it **cancels in the ratios** —
those hold within ±~3% across the three runs, while absolute throughput varies
~8–11%. `run-1` and `run-3` are kept here as the evidence for that spread.
