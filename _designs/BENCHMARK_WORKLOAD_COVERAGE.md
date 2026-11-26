# Benchmark workload coverage

Tracking issue: hardwood-hq/hardwood#1173. Related issues in hardwood-hq/hardwood: #25, #30,
#631, #708, #763, #827, #837, #1015, #1045.

The standalone `hardwood-benchmarks` repository holds Hardwood's benchmark suite. The
suite has two jobs: backing published results with cross-engine numbers, and comparing
one Hardwood version against another to find regressions. This document lists the
benchmarks the suite consists of, the contenders the regression presets time, and the
workload axes they cover; [the regression suite](REGRESSION_SUITE.md) describes the tooling
that builds, measures and records them. Issues are disabled in this repository, so its work is tracked in
hardwood-hq/hardwood, and issue numbers in this document refer to that repository.

## Benchmarks

Every benchmark is a `run-<name>.sh` script, and is one of four kinds. Comparing engines
does not decide the kind: `run-fixedlist.sh` is Hardwood-only and published.

- **Published:** a published result rests on it. Its definition (fixture, parameters,
  plotted contenders) is fixed once published, and a change to it is a deliberate break
  recorded with its date. It has a publication chart generator of its own, the runs a
  publication cites are archived under `publications/`, and every run of it doubles as a
  regression check. It may carry arms that serve regression only, gated but never plotted.
- **Regression-only:** a witness for one scenario, typically one optimization, that no
  publication cites. It has Hardwood contenders only, one fixture size, no archived runs,
  and it changes or goes when the code it witnesses does.
- **Mixed-use:** times Hardwood beside other engines, with a regression preset for its
  Hardwood contenders, without a publication resting on it: no chart generator and no
  archived runs.
- **Engine comparison:** times one process per engine with hyperfine instead of a method
  under JMH, so engines outside the JVM take part. It is never part of a regression run.

| Script | Kind | Workload | Fixture, codec | Contenders | State |
| --- | --- | --- | --- | --- | --- |
| `run-flat.sh` | published | Full scan of 20 flat columns, multi-file | NYC taxi, 2025 layout; ZSTD | Hardwood columnar and row; parquet-java columnar; `AvroParquetReader`; Arrow Dataset (JNI) | built |
| `run-filter.sh` | published | `event_time < T` over a time-clustered file, reading `amount`; `selective` (5 %) and `matchAll` | generated event file; SNAPPY | Hardwood columnar and row, each with an unfiltered control; parquet-java, filtered and unfiltered; `AvroParquetReader` with filter | built |
| `run-bloom.sh` | published | Equality point lookup, bloom-bearing file against a statistics-only twin | generated; SNAPPY | Hardwood and parquet-java, each with and without bloom | built |
| `run-nested.sh` | published | Full record read of deeply nested struct / list / map | Overture Maps places; ZSTD | Hardwood row, typed, and columnar; `AvroParquetReader` | built |
| `run-fixedlist.sh` | published | `LIST<float32>` fast path on and off across vector lengths | generated; UNCOMPRESSED by default, `-Dperf.compression` | Hardwood column and row, fast path and baseline; flat floor | built |
| `run-window.sh` | regression-only | `event_time >= T` for the most recent 5 / 25 / 75 % of the time range: pruned row groups, one straddling the boundary, and row groups proven fully matching, whose `event_time` is not read (#1274) | generated event file, time-sorted, 16 MB row groups; SNAPPY | Hardwood columnar and row, each filtered and unfiltered | built |
| `run-dictfilter.sh` | regression-only | `url IN (5 values)` over a dictionary-encoded column of 1,000 distinct URLs of about 40 bytes, reading each match with `getString`: the predicate is decided once per dictionary entry, and the matches resolve through the entry ids without byte views (#859) | generated, 5M rows; SNAPPY | Hardwood row reader | built |
| `run-nested-columns.sh` | regression-only | Nested decoding of the whole Overture file through the column readers, which over the whole file are bound by decoding rather than per-read setup or the consumer | Overture Maps places, `run-nested.sh`'s file; ZSTD | Hardwood column readers | built |
| `run-narrow.sh` | regression-only | Three numeric columns of one taxi month, one row group of 10.9M rows with about 15 pages per column: how far the reader decodes ahead of the batch it hands out sets its throughput | NYC taxi 2016-01; GZIP | Hardwood column and row readers | built |
| `run-wide.sh` | regression-only | Every column of a 10,000-column file at the default batch size: whatever a read repeats per batch and column is multiplied by the column count (hardwood#1974) | generated, 10,000 `FLOAT` columns × 2,190 rows, one row group, one `BYTE_STREAM_SPLIT` page per chunk; SNAPPY | Hardwood column and row readers | built |
| `run-binary.sh` | regression-only | Full read of two string columns stored `PLAIN`, without a dictionary, one required and one optional with 10 % nulls: each value through the column reader's byte views and as a `String` through the row reader, so the path of a variable-length value's bytes from page to batch is timed alone (#1287) | generated, 5M rows; SNAPPY | Hardwood column and row readers | built |
| `run-1brc.sh` | regression-only | 1BRC: min / mean / max of `measurement` per `station`, a consumer doing a hash-table update per row; aggregated by dictionary entry id on one consumer thread and on one consumer per row-group byte-range split, and through the row reader (#1476) | generated by parquet-java, 413 stations, 100M rows in row groups of 2^20 rows, both columns dictionary-encoded; ZSTD | Hardwood column reader, one consumer and split; row reader | built |
| `run-write.sh` | regression-only | Flat records written to memory; records compressed column-chunk bytes beside time | generated taxi-shaped records; SNAPPY and ZSTD | Hardwood column writer and row writer | built |
| `run-encodings.sh` | regression-only | Every `DOUBLE` column under `AUTO`, `PLAIN`, `BYTE_STREAM_SPLIT` and `ALP`, uncompressed and ZSTD: a column-reader scan of each file and a column-writer write to memory, with each pair's compressed column-chunk bytes (#581) | NYC taxi 2025-01, its 11 `DOUBLE` columns, 1M rows; UNCOMPRESSED and ZSTD | Hardwood column reader and column writer | built |
| `run-s3.sh` | regression-only | Column-reader reads from S3Proxy behind Toxiproxy (30 ms first-byte latency, 80 MB/s per connection): a projected scan, a selective filtered scan, one multi-file reader over twelve files, a selective filtered scan projecting 3 of 200 columns across 40 row groups, whose page index is large against its data (#708), three equality lookups for a key every bloom filter rejects, over 200 row groups with small filters, 6 large row groups with 1 MiB filters read under a 1 MiB index-window budget (#735, #1915), and 20 small row groups with parquet-java's default 1 MiB filters (#1915), and one multi-file reader over 200 small files (#1399); records requests and bytes per read, the wide read's page-index requests and bytes apart, and the small-file read's requests per file and per band of file size | NYC taxi 2025 and the generated event file in a local bucket, ZSTD and SNAPPY; a generated wide table (200 numeric columns, 40 row groups of 20 pages per chunk), SNAPPY; three generated bloom-lookup files (a unique `INT64` key with a bloom filter, no dictionary), SNAPPY; 200 files of 100 KiB to 5 MiB written by pinned PyArrow, one row group each, page index on, SNAPPY | Hardwood column reader | built |
| `run-s3-write.sh` | mixed-use | Writes to S3Proxy behind Toxiproxy (30 ms latency, 80 MB/s upload per connection, in-memory storage), each beside the same write to memory, so the upload's share shows; `run-s3-write-python.sh` adds PyArrow and DuckDB rewriting the same file; records each object's compressed column-chunk bytes | generated taxi-shaped records, 4M rows; ZSTD | Hardwood column writer through `S3Source.outputFile`; parquet-java over S3A; PyArrow; DuckDB | built |
| `run-projection.sh` | published | Projection width 1 / 3 / 10 / 20 of 20 columns, each with and without a range predicate, crossed with null density 0 / 10 / 50 / 90 % on one numeric column | generated | Hardwood columnar and row; parquet-java columnar | planned, 1st |
| codec axis on `run-flat.sh` | published | `-Dperf.codec` over the taxi corpus re-encoded once per codec, reporting each codec's time beside the file size it produced | taxi, re-encoded | Hardwood; parquet-java | planned, 2nd |
| `run-write.sh` | published | Flat and nested writes, row API against columnar API, across codecs and encoding policies, reporting produced bytes beside time | generated records | Hardwood; parquet-java; `AvroParquetWriter` | planned, 3rd |
| `run-strings.sh` | published | Flat string scan across a cardinality sweep, dictionary-encoded and PLAIN | generated | Hardwood columnar and row; parquet-java | planned, 4th |
| `run-open.sh` | published | Open, footer parse and page-index load as latency, over wide schemas and many small files | generated | Hardwood; parquet-java | planned, 4th |
| `run-s3.sh` | published | Reads against a real bucket: footer prefetch, tail cache, range coalescing, the `RangeBacking` modes | taxi in a bucket | Hardwood; parquet-java over `s3a` | planned, 5th |
| `run-mixed.sh` | published | Flat columns beside nested ones in one file, projected to each and to both | generated | Hardwood row and columnar; `AvroParquetReader` | planned, 6th |

`run-1brc-engines.sh`, the one engine comparison, times `run-1brc.sh`'s workload as one
process per engine (Hardwood on the JVM, the DuckDB CLI, Polars, PyArrow) with hyperfine,
start to printed result, because engines outside the JVM cannot run under JMH. Its
contenders' versions are pinned in the script and in `engines/requirements.txt`, and it
is never part of a regression run (#1476).

`run-write.sh` and `run-s3.sh` appear twice: the regression-only form is built first,
and the published form extends it with cross-engine contenders and a publication chart.
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

## Regression contenders

The regression presets time Hardwood's contenders only: the suite compares Hardwood with itself.
Each contender covers a path no other one does, judged on wall time, CPU, allocation and
live-set peak, and the `quick` profile runs the ones marked so. [The regression
suite](REGRESSION_SUITE.md) describes how they are measured, recorded and analysed.

| Script | Preset | Contender | Covers alone | `quick` |
| --- | --- | --- | --- | --- |
| `run-flat.sh` | taxi 2025-01 | `hardwoodColumnar` | full scan of every flat type through the column readers | yes |
| | | `columnarDrain` | the same readers with values fetched but not folded, so that wall time is the readers' alone: `hardwoodColumnar`'s fold on the caller's thread hides decode changes smaller than its slack | |
| | | `hardwoodRowReaderIndexed` | the row reader materialising every column of a flat row | |
| `run-narrow.sh` | taxi 2016-01 | `hardwoodColumnar` | a projection: a few columns fetched and decoded of many | yes |
| | | `hardwoodRowReaderIndexed` | the row reader over a projection | |
| `run-wide.sh` | fixed | `hardwoodColumnar` | every column of a 10,000-column file: what a read repeats per batch and column | |
| `run-nested-columns.sh` | Overture places, whole file | `hardwoodColumnReaders` | nested decoding, decode-bound | yes |
| `run-nested.sh` | Overture places, 20,000 rows | `hardwoodRowReaderTyped` | nested record assembly through the typed row reader | |
| `run-binary.sh` | fixed | `hardwoodColumnViews` | `PLAIN` byte arrays through their byte views | |
| | | `hardwoodRowStrings` | byte arrays materialised as `String`s | |
| `run-filter.sh` | 5M rows | `hardwoodDefault` | statistics and page-index pruning in the column readers, selective (5 %) and matching every row | `selective` |
| | | `hardwoodRowReader` | pruning and row matching in the row reader | `selective` |
| `run-window.sh` | `last25pct` | `hardwoodColumnFiltered` | pruned row groups, the one straddling the bound, and row groups proven to match whose filter column is not read | |
| | | `hardwoodRowFiltered` | the same through the row reader | |
| `run-dictfilter.sh` | fixed | `hardwoodRowIn` | membership decided once per dictionary entry | yes |
| | | `hardwoodRowLtWide` | an ordering decided per entry of a large dictionary | |
| `run-bloom.sh` | 8M rows, absent-key probe | `hardwoodBloom` | bloom-filter pruning of a point lookup | |
| `run-1brc.sh` | 100M rows | `hardwoodColumnIds` | an aggregating consumer by dictionary id | |
| | | `hardwoodColumnSplits` | reading by row-group byte-range splits | |
| `run-fixedlist.sh` | 8M values, `k` = 768 | `columnFast`, `rowFast` | the fixed-size-list fast path in both readers | |
| `run-write.sh` | 500,000 rows, ZSTD | `hardwoodColumnar` | the column writer | yes |
| | | `hardwoodRow` | the row writer | |
| `run-encodings.sh` | 1M rows, ZSTD, `BYTE_STREAM_SPLIT` and `ALP` | `hardwoodRead` | decoding either encoding | |
| | | `hardwoodWrite` | `ALP` encoding; `BYTE_STREAM_SPLIT`'s near-trivial encoding comes with it, since JMH selects methods, not parameter values | |
| `run-s3.sh` | fixed | `hardwoodProjectedScan` | a projected remote fetch | |
| | | `hardwoodWideFilteredScan` | a read whose page index is large against its data | |
| | | `hardwoodLargeBloomLookup` | bloom filters each larger than one page-index window, read remotely | |
| | | `hardwoodFixedSizeBloomLookup` | bloom filters larger than the data they guard, read remotely | |
| | | `hardwoodMultiFileScan` | one reader over twelve remote files | |
| | | `hardwoodRemoteBesideWideRemote` | a read sharing its context's pool with a wide one | |

`run-filter.sh`'s row reader matching every row stays for the same reason as the
`BYTE_STREAM_SPLIT` write: a preset selects methods. `run-bloom.sh`'s statistics-only
twin, `run-window.sh`'s unfiltered reads, `run-dictfilter.sh`'s membership over the large
dictionary, `run-1brc.sh`'s fan-out, `run-nested.sh`'s column readers over 20,000 rows,
`run-write.sh`'s SNAPPY codec and `run-s3.sh`'s filtered scan, small bloom lookup and local
read beside a remote one each repeat a contender above, and run only outside `--regression`.
`run-wide.sh`'s row reader runs only outside it for another reason: on the N300 its time
alternates between two levels about 20 % apart from one iteration to the next, so it never
settles (hardwood#1978 tracks the row reader's cost on wide schemas).


## Fixture durability

Every fixture is generated, and therefore pinned by
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
| Bloom-filter point lookup | `run-bloom.sh`; over S3, `run-s3.sh` | — | — |
| Fixed-size-list fast path | `run-fixedlist.sh` | `FixedSizeListDecodeBenchmark` | — |
| Column count, wide schemas | `run-wide.sh`, a read of every column of 10,000 | `WideSchemaMetadataBenchmark`, metadata only | — |
| Column projection | — | one 3-of-20 point in `FlatPerformanceTest` | `run-projection.sh` |
| Null density | — | — | `run-projection.sh` |
| Compression codec on read | ZSTD, SNAPPY and UNCOMPRESSED as fixed choices | — | codec axis on `run-flat.sh` |
| Encodings beyond PLAIN and dictionary | `run-encodings.sh`, floating point: `BYTE_STREAM_SPLIT` and `ALP` | `DeltaEncodedScanBenchmark` | — |
| Strings and dictionaries, flat | `run-dictfilter.sh`, a filtered read; `run-binary.sh`, a `PLAIN` scan | `DictionaryStringReadBenchmark`, one method; `RecordFilterBenchmarkTest` | `run-strings.sh` |
| Aggregating consumer | `run-1brc.sh` | — | — |
| Flat and nested columns in one file | — | `MixedSchemaReadBenchmark` | `run-mixed.sh` |
| Writes | `run-write.sh`, flat, Hardwood only; `run-s3-write.sh`, flat to S3, cross-engine | `FlatWriteBenchmark`, `WriteEncodingBenchmark` | nested writes, cross-engine: `run-write.sh` published |
| Remote object storage | `run-s3.sh`, reads, and `run-s3-write.sh`, writes, emulated latency | `FlatS3PerformanceTest`, S3Proxy on localhost | a real bucket: `run-s3.sh` published |
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
