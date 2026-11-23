# Benchmark reference

Lists every benchmark: what it reads, its contenders, what its gate checks, what it needs
and the charts it draws. Each script's `--help` lists its flags, its measure recipe and its
regression preset.

Related documents:

- [README](README.md): running a benchmark, comparing versions, publication runs, profiling.
- [Benchmark workload coverage](_designs/BENCHMARK_WORKLOAD_COVERAGE.md): the suite's plan,
  the regression contenders and the path each covers alone.
- [Regression suite](_designs/REGRESSION_SUITE.md): how versions are measured against each
  other.

| Script | Kind | Measures |
| --- | --- | --- |
| [`run-flat.sh`](#flat-full-scan--run-flatsh) | published | Full-scan decode throughput, every column of NYC taxi |
| [`run-filter.sh`](#filtered-scan--run-filtersh) | published | What page-level filtering saves on a selective predicate, and costs when every row matches |
| [`run-bloom.sh`](#bloom-filter-point-lookup--run-bloomsh) | published | What a bloom filter saves where only it can rule out row groups |
| [`run-nested.sh`](#nested-scan--run-nestedsh) | published | Record assembly of structs, lists and maps (Overture Maps places) |
| [`run-fixedlist.sh`](#fixed-size-list-scan--run-fixedlistsh) | published | How close the fixed-size-list fast path comes to flat-column speed |
| [`run-nested-columns.sh`](#nested-column-readers--run-nested-columnssh) | regression-only | Nested decoding over the whole Overture file |
| [`run-narrow.sh`](#narrow-projection-scan--run-narrowsh) | regression-only | A long read of three columns keeping the decode pool busy |
| [`run-window.sh`](#time-window--run-windowsh) | regression-only | Row groups before a time window skipped, fully matching ones not filtered row by row |
| [`run-dictfilter.sh`](#dictionary-filter--run-dictfiltersh) | regression-only | A string `IN` predicate decided once per dictionary entry |
| [`run-binary.sh`](#plain-binary-scan--run-binarysh) | regression-only | `PLAIN` string bytes from page to batch |
| [`run-encodings.sh`](#floating-point-encodings--run-encodingssh) | regression-only | Read and write time of each `DOUBLE` encoding beside its size |
| [`run-1brc.sh`](#1brc-over-parquet--run-1brcsh) | regression-only | A scan whose consumer works per row, one thread and one per split |
| [`run-write.sh`](#writes--run-writesh) | regression-only | Encode throughput and compressed size |
| [`run-s3.sh`](#s3-reads--run-s3sh) | regression-only | Remote reads fetching only what they need, in few requests |
| [`run-s3-write.sh`](#s3-writes--run-s3-writesh) | mixed-use | What writing to S3 costs beside the encode alone |
| [`run-1brc-engines.sh`](#1brc-across-engines--run-1brc-enginessh) | engine comparison | Hardwood's fastest 1BRC configuration against other engines, process start to result |

## Kinds

- **Published:** backs a post. Its definition is fixed once a post cites it, the cited runs
  are archived under [`publications/`](publications/README.md), and it has chart generators
  of its own.
- **Regression-only:** witnesses one scenario, times Hardwood alone over a fixture of one
  size, and changes or goes with the code it witnesses.
- **Mixed-use:** times Hardwood beside other engines without backing a post.
- **Engine comparison:** times whole programs with hyperfine rather than methods with JMH,
  so engines outside the JVM take part. Never part of a regression run.

Every JMH benchmark, whatever its kind, has a `--regression` preset and serves
[version comparison](README.md#comparing-versions).

## Flat full scan — `run-flat.sh`

Reads every column of the monthly NYC Yellow Taxi files, folding each into a per-file
checksum, through two API pairs:

- **Columnar:** Hardwood's column reader against parquet-java's low-level column API, with
  Arrow Dataset (Arrow C++ over JNI) as a cross-engine reference.
- **Record:** Hardwood's row reader against `AvroParquetReader`, each by name
  (`getDouble("fare_amount")`) and by index. An `AvroParquetReader` `SpecificRecord`
  contender confirms the gap is not a `GenericRecord` artifact.

Reads are specific to the 2025 TLC schema (20 columns), so `--start`/`--end` must stay within
the 2025 layout; another schema fails the gate. `--data-dir` (or `-Ddata.dir`) relocates the
download cache. `--batch-size` overrides the Hardwood column reader's batch size.

**Charts** (`make-flat-chart.py`): `flat_chart1_columnar.svg` and `flat_chart2_record.svg`,
throughput in M rows/s. Arrow Dataset and `SpecificRecord` are gated but not plotted.

## Filtered scan — `run-filter.sh`

A generated, time-clustered event file (column index, no bloom filters) read with
`event_time < T`, projecting `amount`: Hardwood's filtered column and row readers against
parquet-java's column API over `readNextFilteredRowGroup()`. Two selectivities: `selective`
(5 % of rows) and `matchAll` (the overhead floor). Unfiltered controls read `amount` alone
(both Hardwood readers, parquet-java) and both columns (parquet-java), separating decode
speed from what filtering costs or saves. The file is keyed on `--rows`.

**Charts** (`make-filter-chart.py`): `filtered_chart.svg`, ms/op, the two selectivities on a
broken axis. The row reader and the controls are gated but not plotted.

## Bloom-filter point lookup — `run-bloom.sh`

`key = k` on a generated unique, pseudorandomly ordered 64-bit key, which neither
statistics, the column index nor a dictionary can prune, against a bloom-bearing file and a
statistics-only twin with identical rows. Hardwood and parquet-java each probe both files.
Two probes: `present` matches one row, so the bloom filter keeps one row group; `absent` is
in range everywhere, so it drops every row group.

Both files are written by parquet-java, with `parquet.bloom.filter.max.bytes` raised to
10 MB, since the 1 MB default clamps the filter to ~99.7 % false positives. The default 84M
rows span ~10 row groups, ~2.9 GB for the pair. Hardwood probes the memory-mapped filter in
place, so its numbers depend on the warm page cache these runs use.

**Charts** (`make-bloom-chart.py`): `bloom_chart.svg`, ms/op, per probe the four reader ×
file combinations plus a single-core bar beside each Hardwood bar, which needs the pinned
pass.

## Nested scan — `run-nested.sh`

A full read of the Overture Maps places file (struct / list / map), every leaf of every
record: Hardwood's row reader with typed getters, field positions resolved from the schema
once, and Hardwood's column readers over every leaf column, against `AvroParquetReader` by
name and by index. A checksum proves they read identical data.

The download resolves the STAC catalog's latest release and records it in the meta sidecar
as `release`; a file passed with `--file` is recorded as `release unknown`. The catalog
serves only recent releases, so archive the file with a run whose numbers are published.

**Charts** (`make-nested-chart.py`): `nested_record.svg`, throughput in M rows/s, for the
typed row reader and the column readers, each Hardwood all-cores, Hardwood single-core
(needs the pinned pass) and `AvroParquetReader` by index. The footnote gives the schema's
leaf count and the share of compressed bytes in `STRING` columns.

## Nested column readers — `run-nested-columns.sh`

`NestedColumnScanBenchmark`: `hardwoodColumnReaders` over the whole Overture file, read as
`run-nested.sh`'s column readers read it, under a regression preset of its own.
`run-nested.sh`'s preset reads 20,000 rows, where the read is mostly per-read setup and the
typed row reader is bound by its consumer thread, so neither shows a change to nested
decoding; over the whole file the column readers are bound by decoding. Takes
`run-nested.sh`'s flags and file.

## Fixed-size-list scan — `run-fixedlist.sh`

A full scan of a `LIST<float32>` column of fixed-width vectors (embeddings, 3-D points)
across a sweep of vector lengths `k`, through the column and row readers, with the
fixed-size-list fast path on and off. `flatFloor` reads the same values as a plain float
column, and the run prints each reader as a multiple of it. The `k` sweep runs on 32 MB
files, since the speedup (baseline ÷ fast) does not depend on size; the headline points
(`k` = 3 and 768) run on ~512 MB files for absolute throughput.

The generated file is uncompressed and without dictionary by default;
`-Dperf.compression` and `-Dperf.pageVersion=v1` change that. Runs all cores only. The
scripts behind the published post are kept with its runs, under
[`publications/2026-07-22-fixed-size-list/scripts/`](publications/2026-07-22-fixed-size-list/README.md#scripts).

**Charts:** `make-fixedlist-bars-chart.py` → `fixedlist_bars.svg`, throughput at one `k`
with the flat floor as a reference line; `make-fixedlist-chart.py` → `fixedlist_speedup.svg`,
speedup against `k`, one line per reader.

## Narrow-projection scan — `run-narrow.sh`

Reads `passenger_count`, `trip_distance` and `fare_amount` of the NYC Yellow Taxi file of
2016-01, folding their present values into a checksum, through Hardwood's column reader and
row reader. The month is one row group of 10.9M rows, GZIP-compressed, with pages of about
0.7M rows: about 15 pages per column. Three columns give few pages in flight at a time, and
the read is long enough that how far the reader decodes ahead of the batch it hands out sets
its throughput. The gate checks both readers against parquet-java's low-level column API.

## Time window — `run-window.sh`

`event_time >= T` for the most recent 5, 25 and 75 % of a time-sorted event log (10M rows,
16 MB row groups, SNAPPY), projecting `amount`. Statistics prune the row groups before `T`,
one row group straddles it, and the row groups after it are proven fully matching, so
Hardwood does not read `event_time` in them. Hardwood's column and row readers run filtered
and unfiltered; the gate checks them against parquet-java.

## Dictionary filter — `run-dictfilter.sh`

`url IN (5 values)` over a generated file of 5M rows whose one column, `url`, holds 1,000
distinct URLs of about 40 bytes, every page dictionary-encoded (SNAPPY). The predicate
selects about 0.5 % of the rows. Hardwood's row reader projects `url` and reads each match
with `getString`. The gate checks that every page is dictionary-encoded and that the read
agrees with parquet-java.

## Plain binary scan — `run-binary.sh`

A full read of a generated file of 5M rows with two string columns stored `PLAIN`, without a
dictionary (SNAPPY): `text`, required, of 4 to 60 ASCII letters, and `note`, optional, null
in one row of ten, of 8 to 24 letters. `hardwoodColumnViews` reads every value through the
column readers' byte views, `hardwoodRowStrings` every value as a `String` through the row
reader. The gate checks that every page of both columns is `PLAIN` and that both contenders
agree with parquet-java. Needs hardwood-core 1.1.0.Beta2 or later.

## Floating-point encodings — `run-encodings.sh`

The 11 `DOUBLE` columns of NYC taxi 2025-01 (fares, distances, tips, tolls and surcharges,
two-digit decimals, some nullable), the first 1M rows in row groups of 16 MB, under each
encoding a `DOUBLE` column can take: `AUTO` (dictionary or `PLAIN` per chunk, the default),
`PLAIN`, `BYTE_STREAM_SPLIT` and `ALP`, each uncompressed and with ZSTD. Per pair,
`hardwoodRead` scans every column of a file written under it with the column reader, and
`hardwoodWrite` writes the columns to memory with the column writer.

The read files are written by the first Hardwood version that runs, so every version of a
regression run reads the same bytes. The gate writes every pair, reads it back bit for bit
against the source values, and checks that its column chunks declare the encoding named.
Each pair's compressed column-chunk bytes go into the meta sidecar
(`bytes.<ENCODING>_<CODEC>`). Needs Hardwood's writer (1.1.0.Beta1 and later); `ALP` runs
from 1.1.0.CR1 on, and is left out on an older version.

## 1BRC over Parquet — `run-1brc.sh`

The [One Billion Row Challenge](https://github.com/gunnarmorling/1brc) over Parquet: min,
mean and max of `measurement` per `station`, over a generated file of 100M rows
(`--rows 1000000000` for the full challenge). parquet-java writes it from a seeded generator
with the original challenge's 413 stations, in row groups of 2^20 rows with both columns
dictionary-encoded (ZSTD).

| Contender | Read |
| --- | --- |
| `hardwoodColumnIds` | aggregates by dictionary entry id on one consumer thread |
| `hardwoodColumnSplits` | the same over contiguous row-group byte ranges, one reader and consumer per available processor, merging the tables |
| `hardwoodColumnFanOut` | one column reader handing each batch's arrays to one of as many aggregating workers as there are available processors, merging their tables |
| `hardwoodRowReader` | each row with `getString` and `getDouble` into a `HashMap`; not in the regression preset, since the per-row lookup takes most of its time |

The column contenders need `hardwood-core` 1.1.0.Beta2 or later. The generator records the
aggregate of the values it draws next to the file, and the gate checks that every page is
dictionary-encoded and that every contender prints that aggregate.

## 1BRC across engines — `run-1brc-engines.sh`

`run-1brc.sh`'s file and aggregate, computed by one program per engine and timed by
[hyperfine](https://github.com/sharkdp/hyperfine) from process start to printed result, with
the OS page cache warm (10 runs after 2 warmup runs; `--runs`, `--warmup`). Every engine uses
all cores.

- **Hardwood** runs `OneBrc` on the JVM with a class path of `hardwood-core` and `zstd-jni`
  only, and an AOT cache the script writes from one training run over the file: the column
  reader by dictionary id, one row-group byte-range split per processor.
- **DuckDB** runs its CLI, downloaded at the version the script pins.
- **Polars** and **PyArrow** run a script each from `engines/`, in a venv with the versions
  `engines/requirements.txt` pins.

Before timing, the script checks every output against the aggregate the generator recorded.
Results go to `target/engines/`, with the engine, JVM and machine versions beside them.
Needs hyperfine and `python3`.

## Writes — `run-write.sh`

500K flat, taxi-shaped records (six columns, nulls in two) written to memory through
Hardwood's column writer and row writer, SNAPPY and ZSTD, so the number is encode
throughput. Each file is read back and checked before timing. The meta sidecar records each
codec's compressed column-chunk bytes (`bytes.SNAPPY`, `bytes.ZSTD`), so a version that
writes larger files shows there even where the time does not move. Needs Hardwood's writer
(1.1.0.Beta1 and later).

## S3 reads — `run-s3.sh`

Hardwood's column reader against S3Proxy behind Toxiproxy on the loopback interface, which
adds 30 ms first-byte latency and caps each connection at 80 MB/s (`S3_LATENCY_MS`,
`S3_BANDWIDTH_KBPS`):

| Contender | Read |
| --- | --- |
| `hardwoodProjectedScan` | 3 non-adjacent of 20 taxi columns, one month |
| `hardwoodFilteredScan` | a selective range predicate over the filter corpus, with its page index |
| `hardwoodMultiFileScan` | one column across 12 taxi files through one multi-file reader |
| `hardwoodManySmallFiles` | every column of 200 files of 100 KiB to 5 MiB through one multi-file reader: one row group each, written by PyArrow (SNAPPY, dictionary, page index on), sizes spread log-uniformly and shuffled |
| `hardwoodWideFilteredScan` | a range predicate keeping the first page of every row group, projecting 3 of 200 columns, over a generated file of 40 row groups with 20 pages per column chunk (a 5.3 MB page index); each row group's data is under the coalescing gap, so the read fetches it whole |
| `hardwoodBloomLookup` | an equality probe for a key no row holds and every bloom filter rejects (chosen when the file is generated, and checked on every run), over a generated file of 200 row groups with a unique, unclustered `INT64` key and a bloom filter of tens of KB per row group; statistics keep every row group and its bloom filter drops it |
| `hardwoodLargeBloomLookup` | the same kind of probe over 6 row groups of 800,000 rows (about 13 MB of data each) whose bloom filters are 1 MiB each, sized for their keys at 1 % FPP, read under an index-window budget of 1 MiB, so each row group's filter is larger than one window's budget |
| `hardwoodFixedSizeBloomLookup` | the same kind of probe over 20 row groups of 20,000 rows (about 320 KB of data each) whose bloom filters are 1 MiB each, parquet-java's default size when the writer is given no NDV, so the filters are about three times the size of the data they guard |
| `hardwoodLocalBesideRemote` | the projected scan of the local copy of the first month, on a context whose pool a remote projected scan of the same month shares, repeated on a thread of its own for the whole iteration |
| `hardwoodRemoteBesideWideRemote` | the projected scan of the first month, on a context whose pool a remote scan of every column of the same month shares, repeated on a thread of its own for the whole iteration |

**Request counts.** Each read is checked against the same read of the local file, and its
request and byte counts go into the meta sidecar:

| Keys | Holds |
| --- | --- |
| `requests.*`, `bytes.*` | every read's requests and bytes |
| `indexRequests.*`, `indexBytes.*` | the wide read's requests overlapping the page-index region, and their bytes |
| `wide.indexRegionBytes`, `wide.neededIndexBytes` | the wide file's page-index size, and the index bytes the read needs |
| `manySmallFiles.requestsPerFile`, `manySmallFiles.requestsPerFileDistribution` | the many-small-files read's requests per file, and their distribution as `requests:files` |
| `manySmallFiles.band.<band>.*` | its files, requests and bytes per band of file size, bounded at 64 KiB, 256 KiB and 1 MiB |

The three bloom lookups read nothing but the footer and the bloom filters, so their request
counts are the footer reads plus the bloom-filter reads.

**Many small files.** `hardwoodManySmallFiles` is left out of `--regression`: one read takes
about 8 s at 30 ms, longer than another contender's whole preset, and about 50 s at 235 ms,
where this run takes about 3 min:

```sh
S3_LATENCY_MS=235 ./run-s3.sh --include hardwoodManySmallFiles --warmup 0 --meas 2 --time 1
```

Outside `--regression`, `run-s3.sh` writes its files on first use, with PyArrow and NumPy
pinned in a virtualenv under `target/` (needs `python3` with `venv`), and gates the contender
only when the run includes it. The gate compares the row count with the manifest and a sum
per column with the local read's, exactly.

**The endpoint.** `./s3-env.sh start | stop | status` runs it, downloaded into
`target/s3-env/` on first use and pinned to one core where `taskset` exists (`S3_ENV_CPU`,
default the last core). `run-s3.sh` starts the endpoint when it is not running and stops it
afterwards; an endpoint already running with other settings is an error.

## S3 writes — `run-s3-write.sh`

4M flat, taxi-shaped records (the fixture of `run-write.sh`, about 52 MB under ZSTD) written
through the endpoint's write port, 18082, which adds the 30 ms latency to each response and
caps the bandwidth of each connection's uploads at 80 MB/s. Behind it is a second S3Proxy
holding objects in memory, so completing a multipart upload costs no on-disk copy of the
object, which S3 does not pay either:

| Contender | Sink |
| --- | --- |
| `hardwoodS3` | Hardwood's column writer into `S3Source.outputFile`: 8 MiB parts, up to four uploading in the background while the writer fills the next |
| `parquetJavaS3` | parquet-java's `ExampleParquetWriter` into S3A, heap-buffered: 8 MiB parts (`fs.s3a.multipart.size`, lowered from 64 MiB), up to four uploading while writing continues (`fs.s3a.fast.upload.active.blocks`). Creating the file with overwrite first probes the key, one round trip more than `hardwoodS3` makes |
| `hardwoodMemory`, `parquetJavaMemory` | the same writes to memory |

Both writers use the same page targets (1 MiB or 20,000 rows), row-group target, dictionary
page limit and page checksums. An S3 contender's time less its writer's memory time is what
the upload adds. Before timing, both objects are read back through Hardwood and every column
checked against the fixture, as `run-write.sh` checks its files; the meta sidecar records
each object's compressed column-chunk bytes (`bytes.hardwood`, `bytes.parquetJava`), the
part size and the endpoint's latency and bandwidth. The benchmark is in a package of its own,
`s3write`, builds on the `write` package's classes, and needs `S3Source.outputFile`
(1.1.0.CR1 and later).

**PyArrow and DuckDB.** `run-s3-write-python.sh` runs them beside it, in a virtualenv of
pinned versions under `target/`. Run `./run-s3-write.sh --gate` first: each engine rewrites
the file the gate wrote (`target/s3-write-fixture.parquet`, so every writer encodes the same
values) to memory or a local file and to the write port, ZSTD, with the fixture file's
row-group size.

- PyArrow runs at its default ZSTD level (1) and at level 3, and writes the page index and
  page checksums, as the Java writers do. It uploads in parts of its own fixed size, in the
  background.
- DuckDB runs on one thread and on all but the endpoint's core, with 8 MB upload parts (from
  its default of about 80 MB, which would send this file as one part after encoding ends).

Every output is read back and compared with the source table; the script prints each
contender's mean and median.
