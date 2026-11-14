# The 1.0 flat and filter runs, repeated on an Intel N300

The flat full scan and the filtered scan from the 1.0 announcement
([`2026-06-25-hardwood-1.0`](../2026-06-25-hardwood-1.0/)), run again on an Intel N300
with Hardwood 1.0.0.Final next to a later Hardwood. The same runs are kept against two
parquet-java versions: 1.18.2, the latest, and 1.17.1, the version the announcement
used.

```
pq1.18.2/1.0.0.Final/run-1..3, median/   # Hardwood 1.0.0.Final, parquet-java 1.18.2
pq1.18.2/main/run-1..3, median/          # Hardwood main b7ffc87, parquet-java 1.18.2
pq1.17.1/1.0.0.Final/run-1..3, median/   # Hardwood 1.0.0.Final, parquet-java 1.17.1
pq1.17.1/main/run-1..3, median/          # Hardwood main b7ffc87, parquet-java 1.17.1
gates/                                   # correctness gates
pq1.18.2/pinned-parquet-java/            # parquet-java under taskset -c 0, one run, not charted (see below)
claims.tsv                               # the published post's claims, for charts/check-publication.py
```

Each `run-N/` holds both benchmarks' TSVs and `bench-meta` sidecars, plus the JMH
console output of each invocation: `flat-hw-all.log` and `filter-hw-all.log` (Hardwood,
all cores), `flat-hw-pin.log` and `filter-hw-pin.log` (Hardwood under `taskset -c 0`),
and `flat-base.log` and `filter-base.log` (parquet-java and AvroParquetReader).
`median/` is the per-point median of the runs, made with `charts/median-runs.py`.

## Charts

The published charts show both Hardwood versions side by side and take parquet-java's
bars from the newer snapshot. From the repository root:

```sh
P=results/2026-11-12-n300-rerun/pq1.18.2
python3 charts/make-two-version-charts.py $P/1.0.0.Final/median $P/main/median \
    --old-label 1.0.0.Final --new-label b7ffc871 --old-short 1.0 --new-short main \
    --parquet-java-label 1.18.2
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
| Date | 2026-11-12 (run 3 of the parquet-java 1.17.1 contenders: 2026-11-13) |
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
(flat) and `hardwoodDefault$` (filter). The parquet-java contenders are
`parquetJavaColumnar|avroParquetReaderIndexed|avroParquetReaderNamed` (flat) and
`parquetJava$` (filter). The clock was pinned with `profiling-setup pin` for the whole
run, so `--no-pin` keeps the scripts from changing it.

The Hardwood contenders do not load parquet-java, so a Hardwood measurement is the same
under either parquet-java version. Each Hardwood run was measured once and appears in both
`pq1.17.1/` and `pq1.18.2/`: runs 1 and 2 with parquet-java 1.17.1 on the classpath,
run 3 with 1.18.2. Every parquet-java contender was measured with the version its
directory names; run 3 of the 1.17.1 contenders was measured on 2026-11-13, on the same
host and pinned clock.

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

## parquet-java on one core

The charts' 1-core ratios compare Hardwood under `taskset -c 0` with parquet-java on all
cores, its fastest setting. parquet-java's readers are single-threaded, but their JVM
runs its GC and JIT threads on the other cores, and under `taskset -c 0` it uses SerialGC
instead of G1. To measure how much that helps parquet-java, `pinned-parquet-java/` times
its five contenders under `taskset -c 0` (one run, warm-up 5, measured 5, parquet-java
1.18.2, the same pinned clock; `pinbase.sh` beside it). Each is slower pinned, so a
1-core ratio against parquet-java on all cores understates Hardwood:

| parquet-java contender | all cores (median of 3) | `taskset -c 0` | main's 1-core ratio: against all cores → against 1 core |
|---|---|---|---|
| column API | 2,893 ms | 3,270 ms (+13 %) | 2.8× → 3.2× |
| AvroParquetReader, indexed | 6,763 ms | 7,869 ms (+16 %) | 6.3× → 7.4× |
| AvroParquetReader, by name | 7,718 ms | 9,037 ms (+17 %) | 4.2× → 4.9× |
| filter, selective | 341 ms | 405 ms (+19 %) | 2.5× → 2.9× |
| filter, match-all | 5,978 ms | 6,119 ms (+2 %) | 16.2× → 16.5× |

The flat contenders settle by the third warm-up iteration. The filter contenders do not:
the selective iterations spread from 370 to 456 ms and match-all was still falling at
the end of the run, so their pinned values are indicative only.
