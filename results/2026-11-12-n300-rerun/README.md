# The 1.0 flat and filter runs, repeated on an Intel N300

The flat full scan and the filtered scan from the 1.0 announcement
([`2026-06-25-hardwood-1.0`](../2026-06-25-hardwood-1.0/)), run again on an Intel N300
with Hardwood 1.0.0.Final next to a later Hardwood. The same runs are kept against two
parquet-java versions: 1.18.2, the latest, and 1.17.1, the version the announcement
used.

```
pq1.18.2/1.0.0.Final/run-1..3, median/   # Hardwood 1.0.0.Final, baselines on parquet-java 1.18.2
pq1.18.2/main/run-1..3, median/          # Hardwood main b7ffc87, same baselines
pq1.17.1/1.0.0.Final/run-1..2, median/   # Hardwood 1.0.0.Final, baselines on parquet-java 1.17.1
pq1.17.1/main/run-1..2, median/          # Hardwood main b7ffc87, same baselines
gates/                                   # correctness gates
```

Each `run-N/` holds both benchmarks' TSVs and `bench-meta` sidecars, plus the JMH
console output of each invocation: `flat-hw-all.log` and `filter-hw-all.log` (Hardwood,
all cores), `flat-hw-pin.log` and `filter-hw-pin.log` (Hardwood under `taskset -c 0`),
and `flat-base.log` and `filter-base.log` (parquet-java and AvroParquetReader).
`median/` is the per-point median of the runs, made with `charts/median-runs.py`.

## Charts

The published charts show both Hardwood versions side by side and take the baselines
from the newer snapshot. From the repository root:

```sh
P=results/2026-11-12-n300-rerun/pq1.18.2
python3 charts/make-two-version-charts.py $P/1.0.0.Final/median $P/main/median \
    --old-label 1.0.0.Final --new-label b7ffc871 --old-short 1.0 --new-short main \
    --baseline-label 1.18.2
```

The charts land in `$P/main/median/charts/two-version/` (git-ignored). Each snapshot
also renders on its own with `charts/make-flat-chart.py` and
`charts/make-filter-chart.py`.

## Provenance

| | |
|---|---|
| Hardwood | [`1.0.0.Final`](https://github.com/hardwood-hq/hardwood/commit/cb41af6d068792dc1c2bcd43ca7afbffa190aa36) and main @ [`b7ffc87`](https://github.com/hardwood-hq/hardwood/commit/b7ffc8710d200dc8279d5889bcc35fb6ef137231) |
| parquet-java | 1.18.2 (`pq1.18.2/`) and 1.17.1 (`pq1.17.1/`); Avro 1.11.5 under both |
| JDK | Temurin 25.0.4+7-LTS |
| JMH | 1.37 |
| Host | Intel N300 (8 Gracemont cores, no SMT), maximum frequency pinned at 1.5 GHz (performance governor), warm page cache |
| Date | 2026-11-12 |
| Flat dataset | NYC Yellow Taxi 2025-01: 3,475,226 rows, 59 MB |
| Filter dataset | generated time-clustered event log: 50,000,000 rows, 1,038 MB |

Sizes and throughput are decimal (MB = 10⁶ bytes).

Every invocation uses one fork and 2 s iterations:

| Invocation | Warm-up | Measured |
|---|---|---|
| Hardwood, all cores | 5 | 5 |
| Hardwood, `taskset -c 0` (`-Dperf.pass=pinned`) | 15 | 5 |
| parquet-java and AvroParquetReader | 3 | 5 |

```sh
./run-flat.sh   --hardwood-version <v> --machine 'Intel N300 (8 cores, fixed 1.5 GHz)' --no-pin \
    --forks 1 --time 2 --start 2025-01 --end 2025-01 --warmup <w> --meas 5 --include <contenders>
./run-filter.sh --hardwood-version <v> --machine 'Intel N300 (8 cores, fixed 1.5 GHz)' --no-pin \
    --forks 1 --time 2 --warmup <w> --meas 5 --include <contenders>
```

The Hardwood contenders are `hardwoodColumnar$|hardwoodRowReaderIndexed|hardwoodRowReaderNamed`
(flat) and `hardwoodDefault$` (filter). The baselines are
`parquetJavaColumnar|avroParquetReaderIndexed|avroParquetReaderNamed` (flat) and
`parquetJava$` (filter). The clock was pinned with `profiling-setup pin` for the whole
run, so `--no-pin` keeps the scripts from changing it.

The Hardwood contenders do not load parquet-java, so a Hardwood measurement is the same
under either parquet-java version. Runs 1 and 2 of Hardwood were measured once, with
parquet-java 1.17.1 on the classpath, and appear in both `pq1.17.1/` and `pq1.18.2/`.
Run 3 of Hardwood and every `pq1.18.2/` baseline were measured with parquet-java 1.18.2.

## Gates

- `gates/flat-pq1.18.2-1.0.0.Final.log` and `gates/flat-pq1.17.1-main.log`: every flat
  contender agrees on the checksum.
- `gates/filter-pq1.18.2-main.log`: every filtered contender agrees with parquet-java at
  both selectivities, and the unfiltered controls pass.
- `gates/filter-pq1.18.2-1.0.0.Final.log`: the filtered column reader (the charted
  contender) and AvroParquetReader agree with parquet-java at both selectivities, and the
  unfiltered controls pass. 1.0.0.Final's filtered row reader, which is not charted,
  fails with `Column not in projection: event_time`. These two filter logs come from a
  gate that checks each contender separately; the gate in this repository stops at the
  first contender that fails.

## Reading the results

- Match-all on main: row-group statistics prove that every row matches, so main does
  not read the filter column. 1.0.0.Final reads it. On one core this is 2,928 ms for
  1.0.0.Final against 370 ms for main.
- Run 3 of main's single-core match-all scan has two slow iterations (444 and 461 ms
  against 359–367 ms for the other three); the median of the three runs is 370 ms.
