# Benchmark workload coverage

**Status: in progress.** Tracking issue: hardwood-hq/hardwood#1173. Related issues in
hardwood-hq/hardwood: #25, #30, #631, #763, #827, #837, #1015, #1045.

The standalone `hardwood-benchmarks` repository holds Hardwood's benchmark suite. The
suite has two jobs: backing published results with cross-engine numbers, and comparing
one Hardwood version against another to find regressions. This document lists the
benchmarks the suite consists of, the tooling they share, and the workload axes they
cover. Issues are disabled in this repository, so its work is tracked in
hardwood-hq/hardwood, and issue numbers in this document refer to that repository.

## Benchmarks

Every benchmark is a `run-<name>.sh` script over one JMH class, and is one of two kinds:

- **Mixed-use:** a published result rests on it. Its definition (fixture, parameters,
  plotted contenders) is fixed once published, and a change to it is a deliberate break
  the README records with its date. It has a publication chart generator of its own, the
  runs a publication cites are archived under `results/`, and every run of it doubles as
  a regression check. It may carry arms that serve regression only, gated but never
  plotted. Comparing engines does not decide the kind: `run-fixedlist.sh` is
  Hardwood-only and mixed-use.
- **Regression-only:** a witness for one scenario, typically one optimization, that no
  publication cites. It has Hardwood contenders only, one fixture size, no archived runs,
  and it changes or goes when the code it witnesses does. The README lists these in a
  section of their own.

| Script | Kind | Workload | Fixture, codec | Contenders | State |
| --- | --- | --- | --- | --- | --- |
| `run-flat.sh` | mixed-use | Full scan of 20 flat columns, multi-file | NYC taxi, 2025 layout; ZSTD | Hardwood columnar and row; parquet-java columnar; `AvroParquetReader`; Arrow Dataset (JNI) | built |
| `run-filter.sh` | mixed-use | `event_time < T` over a time-clustered file, reading `amount`; `selective` (5 %) and `matchAll` | generated event file; SNAPPY | Hardwood columnar and row, each with an unfiltered control; parquet-java, filtered and unfiltered; `AvroParquetReader` with filter | built |
| `run-bloom.sh` | mixed-use | Equality point lookup, bloom-bearing file against a statistics-only twin | generated; SNAPPY | Hardwood and parquet-java, each with and without bloom | built |
| `run-nested.sh` | mixed-use | Full record read of deeply nested struct / list / map | Overture Maps places; ZSTD | Hardwood row; `AvroParquetReader` | built |
| `run-fixedlist.sh` | mixed-use | `LIST<float32>` fast path on and off across vector lengths | generated; UNCOMPRESSED by default, `-Dperf.compression` | Hardwood column and row, fast path and baseline; flat floor | built |
| `run-window.sh` | regression-only | `event_time >= T` for the most recent 5 / 25 / 75 % of the time range: pruned row groups, one straddling the boundary, and row groups proven fully matching, whose `event_time` is not read (#1274) | generated event file, time-sorted, 16 MB row groups; SNAPPY | Hardwood columnar and row, each filtered and unfiltered | built |
| `run-write.sh` | regression-only | Flat records written to memory; records compressed column-chunk bytes beside time | generated taxi-shaped records; SNAPPY and ZSTD | Hardwood column writer and row writer | built |
| `run-s3.sh` | regression-only | Column-reader reads from S3Proxy behind Toxiproxy (30 ms first-byte latency, 80 MB/s per connection): a projected scan, a selective filtered scan, and one multi-file reader over twelve files; records requests and bytes per read | NYC taxi 2025 in a local bucket; ZSTD | Hardwood column reader | built |
| `run-projection.sh` | mixed-use | Projection width 1 / 3 / 10 / 20 of 20 columns, each with and without a range predicate, crossed with null density 0 / 10 / 50 / 90 % on one numeric column | generated | Hardwood columnar and row; parquet-java columnar | planned, 1st |
| codec axis on `run-flat.sh` | mixed-use | `-Dperf.codec` over the taxi corpus re-encoded once per codec, reporting each codec's time beside the file size it produced | taxi, re-encoded | Hardwood; parquet-java | planned, 2nd |
| `run-write.sh` | mixed-use | Flat and nested writes, row API against columnar API, across codecs and encoding policies, reporting produced bytes beside time | generated records | Hardwood; parquet-java; `AvroParquetWriter` | planned, 3rd |
| `run-strings.sh` | mixed-use | Flat string scan across a cardinality sweep, dictionary-encoded and PLAIN | generated | Hardwood columnar and row; parquet-java | planned, 4th |
| `run-open.sh` | mixed-use | Open, footer parse and page-index load as latency, over wide schemas and many small files | generated | Hardwood; parquet-java | planned, 4th |
| `run-s3.sh` | mixed-use | Reads against a real bucket: footer prefetch, tail cache, range coalescing, the `RangeBacking` modes | taxi in a bucket | Hardwood; parquet-java over `s3a` | planned, 5th |
| `run-mixed.sh` | mixed-use | Flat columns beside nested ones in one file, projected to each and to both | generated | Hardwood row and columnar; `AvroParquetReader` | planned, 6th |

`run-write.sh` and `run-s3.sh` appear twice: the regression-only form is built first,
and the mixed-use form extends it with cross-engine contenders and a publication chart.
`run-flat.sh` also takes two contenders at no fixture cost, `AvroRowReader` from
`hardwood-avro` and `GroupReadSupport` from `parquet-java-compat`, both migration paths
users take.

What the planned benchmarks are for:

- `run-projection.sh` is the most common query shape in real workloads, and the only
  witness [CROSS_COLUMN_COALESCING.md](https://github.com/hardwood-hq/hardwood/blob/main/_designs/CROSS_COLUMN_COALESCING.md),
  [SEQUENTIAL_FETCH_PLAN_PAGE_MASKING.md](https://github.com/hardwood-hq/hardwood/blob/main/_designs/SEQUENTIAL_FETCH_PLAN_PAGE_MASKING.md) and
  [COALESCED_OFFSET_INDEX_READS.md](https://github.com/hardwood-hq/hardwood/blob/main/_designs/COALESCED_OFFSET_INDEX_READS.md) have. Its generator
  carries the null-density axis, and it takes a thread-count sweep as a flag, since all
  cores against one core is two points and a scaling regression hides between them.
- The codec axis covers GZIP, LZ4_RAW and BROTLI, which no benchmark decompresses, and
  times the libdeflate GZIP path (`hardwood.uselibdeflate`) against the JDK one.
- `run-strings.sh` covers the flat string workload, a low-cardinality dictionary column
  beside a high-cardinality PLAIN one, which is the shape of log, event and dimension
  data. Taxi holds one string column of 23.4 KB in a 52.5 MB row group, and Overture
  reads its strings through deep nesting, where string decode and nested reconstruction
  are not separable.
- `run-open.sh` is the baseline for the reusable-footer work (#837) and the suite's only
  latency-shaped benchmark: in every other one the scan is long enough for footer cost to
  vanish.
- `run-s3.sh` measures request shape rather than decode. The coalescing trade-off (#763)
  is latency against bandwidth against concurrency, which only a clock under real or
  emulated latency can judge; the request and byte counts make a change in the fetch
  plan exact where the clock is noisy.
- `run-mixed.sh` adds the contender and the chart to the shape
  `MixedSchemaReadBenchmark` covers in-repo.

## Regression tooling

All benchmarks of both kinds share the following.

**Version as a run parameter.** `--hardwood-version V` sets the `hardwood.version` pom
property on the build, so a run measures any released or locally installed
`hardwood-core`. A script compiles the shared classes in `dev.hardwood.benchmarks` plus
its own package (`BENCH_PACKAGE`), into `target/build/<version>_<package>`. A build is
reused until a source file, the pom or the resolved `hardwood-core` jar is newer than it.
A benchmark using API the requested version lacks fails its own build and no other, and
the version chart shows it as not run for that version. A contender that compiles but
throws on an older version fails alone under JMH, with the same result in the chart.

**The regression preset.** `--regression` fixes a run's whole configuration so that
regression runs taken at any time are alike: each script's reduced sizes and contender
subset, 3 warmup and 3 measurement iterations of 1 s, and one pass on all cores. A
script's preset may override the iteration counts (`run-s3.sh` warms up for 5, since
requests over the emulated latency take longer to settle). Pinned to one core, the JIT,
the GC and the reader's worker threads share the core and a contender does not settle
within ten iterations; on all cores it settles by the third. Passing a flag the preset
sets is an error. The meta sidecar records the preset, and the comparator and the
version chart treat it as part of the dataset key, so a regression number is never
compared with one measured to a published definition.

| Script | Preset sizes | Contenders |
| --- | --- | --- |
| `run-flat.sh` | taxi 2025-01 | `hardwoodColumnar`, `hardwoodRowReaderIndexed` |
| `run-filter.sh` | 5M rows | `hardwoodDefault`, `hardwoodRowReader`, `parquetJava` |
| `run-bloom.sh` | 8M rows, absent-key probe | `hardwoodBloom`, `hardwoodNoBloom` |
| `run-nested.sh` | 20,000 rows | Hardwood |
| `run-fixedlist.sh` | 8M values, `k` = 768 | the fast-path arms |
| `run-window.sh` | `last25pct` | Hardwood |
| `run-write.sh` | 500,000 rows | Hardwood |
| `run-s3.sh` | fixed | Hardwood |

`run-filter.sh`'s `parquetJava` is the suite's control: its version is pinned in the pom,
so a move in it is machine drift, not a Hardwood change.

**The regression run.** `run-regression.sh V1 V2 [V3 ...]` runs every script under
`--regression` for each version in interleaved rounds (`--rounds`, default 3), so drift
on the machine spreads over all versions, and starts the emulated S3 endpoint once for
the whole run. A round of all eight scripts takes about 4 min per version on a 1.50 GHz
Intel N300. It ends in `verdict.txt`, which compares the first version with the last and
lists per benchmark a tally plus only the contenders that moved beyond the noise band,
with control drift reported separately; `not-run.txt`, which gives the reason for every
benchmark a version could not run; and one version chart per benchmark.
`--fail-on-regression` exits non-zero when a Hardwood contender is slower beyond the band.

**The comparator.** `charts/compare-runs.py BASE NEW` takes two snapshots, each a run
directory or a directory of `run-*` repeats, and prints per contender the base time, new
time, delta and verdict. A delta is called only when it clears a noise band. With repeats
on both sides the band is half of each side's observed spread, `(max - min) / median`,
added together and floored at `--min-band` (default 3 %); with a single run on either
side it is `--threshold` (default 5 %), and the report says which it used. Contenders and
benchmarks on one side only are listed. A differing `machine`, `java`, dataset key or
preset, or two snapshots of the same Hardwood build, draws a warning before any number.
`--control REGEX` marks pinned contenders, whose moves are reported as drift and never as
a regression; `--changes-only`, `--include`, `--format tsv` and `--fail-on-regression`
serve scripts, including the check #25 needs.

**The version chart.** `charts/make-version-chart.py` charts each Hardwood contender
across any number of snapshots in the order given: two for a before/after of a branch,
several for a progression such as 1.0 → 1.1 → 1.2. Each snapshot is labelled with the
Hardwood version its meta sidecar records, and with the commit for a snapshot build;
repeats are drawn as whiskers, and a version that could not run a contender is drawn as
missing. It checks snapshots as the comparator does. Control contenders are not charted;
the chart warns when one moves beyond its noise band. A mixed-use benchmark's publication
chart stays with its own generator.

**Fixture durability.** Every fixture is generated, and therefore pinned by
construction, except two corpora. `run-flat.sh` and `run-s3.sh` read NYC taxi pinned to
the 2025 TLC layout by `--start`/`--end`, since the schema changed under it.
`run-nested.sh` reads the Overture places file from the STAC catalog's latest release,
so once a release rotates out of the bucket the bytes a published number was measured on
are gone. A regression run downloads the file once and reads it for every version, so
the comparison within a run holds; a generated nested fixture, which would also hold
across runs, is planned.

## Coverage by axis

`performance-testing` holds JMH micro-benchmarks and end-to-end tests that gate
implementation work, Hardwood against itself. Few carry a cross-engine contender, none
produces a chart, and several are JUnit tests. The table lists where each axis is
measured in either place.

| Axis | Standalone suite | `performance-testing` | Gap |
| --- | --- | --- | --- |
| Full scan, flat | `run-flat.sh` | `FlatPerformanceTest` | — |
| Full record read, nested | `run-nested.sh` | `NestedPerformanceTest`, `NestedListReadBenchmark`, `NestedLogicalTypeReadBenchmark` | — |
| Predicate pushdown, statistics and page index | `run-filter.sh`, `run-window.sh` | `PageFilterBenchmarkTest` | — |
| Bloom-filter point lookup | `run-bloom.sh` | — | — |
| Fixed-size-list fast path | `run-fixedlist.sh` | `FixedSizeListDecodeBenchmark` | — |
| Column projection | — | one 3-of-20 point in `FlatPerformanceTest` | `run-projection.sh` |
| Null density | — | — | `run-projection.sh` |
| Compression codec on read | ZSTD, SNAPPY and UNCOMPRESSED as fixed choices | — | codec axis on `run-flat.sh` |
| Encodings beyond PLAIN and dictionary | — | `DeltaEncodedScanBenchmark` | `BYTE_STREAM_SPLIT` unmeasured |
| Strings and dictionaries, flat | — | `DictionaryStringReadBenchmark`, one method | `run-strings.sh` |
| Flat and nested columns in one file | — | `MixedSchemaReadBenchmark` | `run-mixed.sh` |
| Writes | `run-write.sh`, flat, Hardwood only | `FlatWriteBenchmark`, `WriteEncodingBenchmark` | nested writes, cross-engine: `run-write.sh` mixed-use |
| Remote object storage | `run-s3.sh`, emulated latency | `FlatS3PerformanceTest`, S3Proxy on localhost | a real bucket: `run-s3.sh` mixed-use |
| Open, footer and metadata latency | — | `WideSchemaMetadataBenchmark`, `TailReadBenchmarkTest` | `run-open.sh` |
| Thread scaling | all cores; one core via `taskset -c 0` outside `--regression` | — | sweep on `run-projection.sh` |
| SIMD | — | `SimdBenchmark` | every published run records `simd=scalar` |
| Cold page cache | — | — | unmeasured |
| `hardwood-avro`, `parquet-java-compat` as subjects | — | — | contenders on `run-flat.sh` |

## Out of scope

- **Variant and geospatial reads.** Both are implemented and neither is a common workload
  yet. They earn a benchmark when they carry weight in real files.
- **Row seek, `skip`, row limit and split-aware reading.** Each is a control on a scan
  the suite measures, not a workload of its own; `run-projection.sh` carries the numbers
  that matter.
- **Encrypted files.** Unsupported in either direction.
- **Cold page cache and SIMD.** Each is an additional pass over existing benchmarks that
  doubles the run time of every benchmark it touches. They follow the planned benchmarks
  above, at which point `run-bloom.sh`'s warm-cache caveat about mmap becomes checkable.
