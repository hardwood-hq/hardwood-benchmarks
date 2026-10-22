# hardwood-benchmarks

Benchmarks for [Hardwood](https://github.com/hardwood-hq/hardwood): Hardwood against
[parquet-java](https://github.com/apache/parquet-java) and other readers for published
results, and one Hardwood version against another for regressions. Depends only on
`hardwood-core` and the contenders' libraries as Maven artifacts.

## Quick start

Needs JDK 21 or newer (tested on 25); the Maven wrapper is bundled.

```sh
./run-flat.sh --help     # flags, the benchmark's recipe, its regression preset
./run-flat.sh --gate     # check every contender produces the same result, no timing
./run-flat.sh            # benchmark
```

Each benchmark acquires its data on first run and reuses it after. Data lives under
`target/`, which `./mvnw clean` wipes; `run-flat.sh --data-dir` and
`run-nested.sh --file` point at a persistent location instead.

`hardwood-core` resolves at `<hardwood.version>` in `pom.xml`, or at
`--hardwood-version`. Releases come from Maven Central; a `-SNAPSHOT` must be installed
from a Hardwood checkout first with `./mvnw -pl core -am install -Dquick`.

## Benchmarks

| Script | Kind | Workload | Purpose | Contenders |
| --- | --- | --- | --- | --- |
| [`run-flat.sh`](#flat-full-scan--run-flatsh) | published | Full scan of every column of NYC taxi | Measures full-scan decode throughput against parquet-java, Avro and Arrow | Hardwood column and row readers; parquet-java, `AvroParquetReader`, Arrow Dataset |
| [`run-filter.sh`](#filtered-scan--run-filtersh) | published | Range predicate over a time-clustered file | Measures what page-level filtering saves on a selective predicate, and what it costs when every row matches | Hardwood filtered column and row readers; parquet-java |
| [`run-bloom.sh`](#bloom-filter-point-lookup--run-bloomsh) | published | Equality lookup on a unique key, bloom file against a statistics-only twin | Measures what a bloom filter saves where only it can rule out row groups | Hardwood; parquet-java |
| [`run-nested.sh`](#nested-scan--run-nestedsh) | published | Full record read of deeply nested Overture Maps places | Measures record assembly throughput for structs, lists and maps | Hardwood row reader and column readers; `AvroParquetReader` |
| [`run-nested-columns.sh`](#nested-column-readers--run-nested-columnssh) | regression-only | `run-nested.sh`'s column readers over the whole Overture Maps places file | Shows a change to nested decoding or the real-items view, which `run-nested.sh`'s 20,000-row preset does not | Hardwood column readers |
| [`run-fixedlist.sh`](#fixed-size-list-scan--run-fixedlistsh) | published | `LIST<float32>` vectors, fast path on and off | Measures how close the fixed-size-list fast path comes to flat-column speed | Hardwood column and row readers |
| [`run-narrow.sh`](#narrow-projection-scan--run-narrowsh) | regression-only | Three numeric columns of the 2016-01 NYC taxi file: one row group of 10.9M rows, about 15 pages per column | Shows that a long read of few columns keeps the decode pool busy | Hardwood column and row readers |
| [`run-window.sh`](#time-window--run-windowsh) | regression-only | Recent time window over a time-sorted event log | Shows that row groups before the window are skipped and fully matching ones are not filtered row by row | Hardwood column and row readers |
| [`run-dictfilter.sh`](#dictionary-filter--run-dictfiltersh) | regression-only | `IN` over a dictionary-encoded string column | Shows that a string predicate is decided once per dictionary entry, and that reading the matches as strings builds no byte views | Hardwood row reader |
| [`run-binary.sh`](#plain-binary-scan--run-binarysh) | regression-only | Full read of two `PLAIN` string columns, no dictionary | Times a variable-length value's bytes on their way from the page into the batch | Hardwood column and row readers |
| [`run-encodings.sh`](#floating-point-encodings--run-encodingssh) | regression-only | Taxi's `DOUBLE` columns under `AUTO`, `PLAIN`, `BYTE_STREAM_SPLIT` and `ALP`, uncompressed and ZSTD | Measures each encoding's read and write time beside the bytes it produces | Hardwood column reader and column writer |
| [`run-1brc.sh`](#1brc-over-parquet--run-1brcsh) | regression-only | 1BRC: min, mean and max per station | Measures a scan whose consumer does work per row, on one consumer thread and on one per row-group split | Hardwood column and row readers |
| [`run-1brc-engines.sh`](#1brc-across-engines--run-1brc-enginessh) | engine comparison | `run-1brc.sh`'s workload, one process per run | Compares Hardwood's fastest 1BRC configuration with other engines, start to printed result | Hardwood; DuckDB CLI; Polars; PyArrow |
| [`run-write.sh`](#writes--run-writesh) | regression-only | Flat records written to memory, SNAPPY and ZSTD | Measures encode throughput and compressed size | Hardwood column and row writers |
| [`run-s3.sh`](#s3-reads--run-s3sh) | regression-only | Reads from an emulated object store | Shows that a remote read fetches only what it needs, in few requests: projected columns, surviving pages, page-index and bloom-filter slices per window | Hardwood column reader |
| [`run-s3-write.sh`](#s3-writes--run-s3-writesh) | mixed-use | Flat records written to an emulated object store | Measures what writing to S3 costs beside the encode alone | Hardwood `S3Source.outputFile`; parquet-java over S3A; PyArrow and DuckDB (`run-s3-write-python.sh`) |

An **engine comparison** times whole programs with hyperfine rather than methods with
JMH, so engines outside the JVM take part. It is never part of a regression run.
A **published** benchmark backs a post: its definition is fixed once a post cites it,
the cited runs are archived under `results/`, and it has chart generators of its own.
A **regression-only** benchmark witnesses one scenario, times Hardwood alone over a
fixture of one size, and changes or goes with the code it witnesses. A **mixed-use**
benchmark times Hardwood beside other engines without backing a post. All kinds serve
[version comparison](#comparing-versions). The plan for the suite, including the
benchmarks still to come, is in
[`_designs/BENCHMARK_WORKLOAD_COVERAGE.md`](_designs/BENCHMARK_WORKLOAD_COVERAGE.md).

## Running a benchmark

**Gate and benchmark.** `--gate` runs every contender once, checks their results
against the reference, and exits. Without it, the script benchmarks. Gate-check before
any long run.

**Passes.** A benchmark runs two passes: all cores, timing every contender, then one
core (`taskset -c 0`, Linux only), re-timing the Hardwood contenders only, since pinning
does not change the single-threaded ones. `--no-pin` skips the single-core pass,
`--pin-only` the all-cores one. `run-fixedlist.sh` and `--regression` run all cores
only.

**Flags.** Every script takes `--warmup`, `--meas`, `--forks`, `--time`, `--include`,
`--prof`, `--machine`, `--no-pin`, `--pin-only`, `--gate`, `--hardwood-version` and
`--regression`, plus its own; its `--help` documents all of them. Any `-D…` passes
through to the JVM.

**Output.** JMH reports average time per op (`ms/op`, lower is better); some benchmarks
also print throughput (`M rows/s`, `MB/s`). A run writes to `target/`:

- `bench-throughput-<Benchmark>.tsv`: the numbers.
- `bench-meta-<Benchmark>.tsv`: the dataset parameters, plus `java`, `hardwood`
  (version and commit), `machine`, `preset` and `simd`. `simd` is `scalar` unless
  `--add-modules jdk.incubator.vector` reaches the JVM from the environment, which the
  scripts do not pass; quote SIMD and scalar numbers separately.
- `<bench>.log`: the console output (`BENCH_LOG=0` disables it).

`./capture-run.sh <dir>` copies that set into a self-contained snapshot, which the
comparison and chart tools read.

## Comparing versions

`./run-regression.sh BASE NEW` checks for regressions between two Hardwood versions,
for example a release against a local build:

```sh
./run-regression.sh 1.0.0.Final 1.1.0-SNAPSHOT
```

It runs every benchmark under `--regression` for each version in interleaved rounds
(`--rounds`, default 3), so machine drift spreads over all versions. One round of all
ten benchmarks takes about 5 min per version on a 1.50 GHz Intel N300. It writes to
`target/regression/`:

- `verdict.txt`: per benchmark, a tally and only the contenders that moved beyond the
  noise band, first version against the last, with control drift reported separately.
  The write benchmark adds the size of each file it wrote, called larger or smaller
  beyond 1%; sizes do not vary between runs, so they have no noise band.
- `not-run.txt`: every benchmark or contender a version could not run, with the reason.
- `charts/`: one version chart per benchmark, and `regression-overview.svg`: every Hardwood
  contender of the first version and the last on one page, relative to the first, and
  the write benchmark's written sizes, with `verdict.txt`'s verdicts
  (`charts/make-regression-chart.py <out>` redraws it).

More than two versions give a progression, such as 1.0 → 1.1 → 1.2; `--only` restricts
the benchmarks, and `--fail-on-regression` sets the exit status: 1 when a Hardwood contender
is slower beyond its band or a written file larger beyond 1%.

**`--hardwood-version`** selects the version for one run. It sets a pom property, so it
reaches Maven; a bare `-Dhardwood.version=…` would reach the JVM instead and measure the
pom's version. Each version builds into its own `target/build/` directory, compiling
the shared classes and the benchmark's own package, so a benchmark using API a version
lacks fails its own build and no other (against 1.0.0.Final, `run-fixedlist.sh` does not
build). A contender that builds but throws on an older version fails alone under JMH and
is reported as not run.

**`--regression`** fixes the run's configuration so that regression runs taken at any
time are alike:

- The benchmark's preset sizes and contenders, listed at the end of its `--help`. The
  only non-Hardwood contender is `run-filter.sh`'s parquet-java scan, a control: its
  version is pinned, so a move in it is the machine drifting, not Hardwood changing.
- 3 warmup and 3 measurement iterations of 1 s, unless the preset says otherwise
  (`run-s3.sh` warms up for 5).
- One pass, on all cores. Pinned to one core, the JIT, the GC and the reader's worker
  threads share the core, and a contender is still about 15 % off its steady state
  after ten iterations; on all cores it settles by the third.

Passing a flag the preset sets is an error. Regression numbers are compared only with
each other.

**`-Dperf.threads=N`** sizes the pool of every Hardwood contender's context
(`BenchContext`), reads and writes alike; without it the pool has Hardwood's default size.
`run-regression.sh` passes no `-D` to its runs, so set it for a whole regression run
through the environment, which every JVM of the run picks up:

```sh
JAVA_TOOL_OPTIONS=-Dperf.threads=6 ./run-regression.sh BASE NEW
```

The meta sidecar records the pool size (`threads`), and `compare-runs.py` warns when two
snapshots differ in it.

**The tools underneath.** `charts/compare-runs.py BASE NEW` differences two snapshots,
each a run directory or a directory of `run-*` repeats, and calls a change only beyond a
noise band measured from the repeats. `charts/make-version-chart.py` charts every
Hardwood contender across any number of snapshots. Both warn when snapshots differ in
machine, Java, dataset or preset. Each documents its options in `--help`.

## Publication runs

A published number is the median of three full runs, taken with 5-minute breaks between
them on a machine with a fixed clock (see [Profiling](#profiling)). Gate-check first,
then paste this loop into a `tmux` session, with the measure line from the benchmark's
`--help` recipe (tmux mangles the quoting if the loop is passed to `tmux new` directly):

```sh
tmux new -s bench        # then paste:
for i in 1 2 3; do
  <measure line>                     # e.g. ./run-flat.sh --forks 5 --meas 10 --include "…"
  ./capture-run.sh results/<YYYY-MM-DD>-<slug>/run-$i
  [ $i -lt 3 ] && sleep 300
done
```

`results/<YYYY-MM-DD>-<slug>/` holds one publication, with a README of its own.
`charts/median-runs.py` writes a snapshot of the per-contender medians of the three runs.

Each published benchmark has `charts/make-<benchmark>-*chart.py` generators (stdlib
Python, sharing `charts/chartlib.py`). They read a snapshot (`--results-dir`, default
`target/`), write SVGs to its `charts/` directory, and rasterize each to PNG when
`rsvg-convert`, `resvg`, `inkscape` or `cairosvg` is on `PATH`. The hardware label comes
from the meta sidecar's `machine`; `--machine` overrides it.

## Profiling

Attach a JMH profiler with `--prof`, narrowed with `--include`:

```sh
./run-flat.sh --include "hardwoodColumnar|hardwoodRowReaderIndexed" --prof gc           # allocation per op
./run-flat.sh --include hardwoodColumnar --prof stack                                   # sampled stacks
./run-flat.sh --include hardwoodColumnar --prof perfnorm --forks 3                      # CPU counters
./run-flat.sh --include hardwoodColumnar --prof "async:output=flamegraph;event=itimer"  # async-profiler
```

`gc`, `stack` and async-profiler (`itimer`, `alloc`) work anywhere. `perfnorm` and
`perfasm` need Linux `perf` and a host-exposed PMU, which most cloud VMs lack;
`perfasm` also needs `kernel.perf_event_paranoid` ≤ 1 and hsdis in the JDK's `lib/`.
Comparable timings need a fixed clock: governor `performance`, `scaling_max_freq`
capped at the clock the package sustains with all cores busy, and no background timers
firing mid-run.
[`profiling-setup`](https://github.com/gunnarmorling/cloud-boxes/blob/master/ansible/roles/bench_host/files/profiling-setup)
applies and reverts these settings for one session.

## Benchmark reference

### Flat full scan — `run-flat.sh`

Reads every column of the monthly NYC Yellow Taxi files, folding each into a per-file
checksum, through two API pairs:

- **Columnar:** Hardwood's column reader against parquet-java's low-level column API,
  with Arrow Dataset (Arrow C++ over JNI) as a cross-engine reference.
- **Record:** Hardwood's row reader against `AvroParquetReader`, each by name
  (`getDouble("fare_amount")`) and by index. An `AvroParquetReader` `SpecificRecord`
  contender confirms the gap is not a `GenericRecord` artifact.

Reads are specific to the 2025 TLC schema (20 columns), so `--start`/`--end` must stay
within the 2025 layout; another schema fails the gate. `--data-dir` (or `-Ddata.dir`)
relocates the download cache. `--batch-size` overrides the Hardwood column reader's
batch size.

**Charts** (`make-flat-chart.py`): `flat_chart1_columnar.svg` and
`flat_chart2_record.svg`, throughput in M rows/s. Arrow Dataset and `SpecificRecord`
are gated but not plotted.

### Filtered scan — `run-filter.sh`

A generated, time-clustered event file (column index, no bloom filters) read with
`event_time < T`, projecting `amount`: Hardwood's filtered column and row readers
against parquet-java's column API over `readNextFilteredRowGroup()`. Two selectivities:
`selective` (5 % of rows) and `matchAll` (the overhead floor). Unfiltered controls read
`amount` alone (both Hardwood readers, parquet-java) and both columns (parquet-java),
separating decode speed from what filtering costs or saves. The file is keyed on
`--rows`.

**Charts** (`make-filter-chart.py`): `filtered_chart.svg`, ms/op, the two selectivities
on a broken axis. The row reader and the controls are gated but not plotted.

### Bloom-filter point lookup — `run-bloom.sh`

`key = k` on a generated unique, pseudorandomly ordered 64-bit key, which neither
statistics, the column index nor a dictionary can prune, against a bloom-bearing file
and a statistics-only twin with identical rows. Hardwood and parquet-java each probe
both files. Two probes: `present` matches one row, so the bloom filter keeps one row
group; `absent` is in range everywhere, so it drops every row group. Both files are
written by parquet-java, with `parquet.bloom.filter.max.bytes` raised to 10 MB, since
the 1 MB default clamps the filter to ~99.7 % false positives. The default 84M rows
span ~10 row groups, ~2.9 GB for the pair.

Hardwood probes the memory-mapped filter in place, so its numbers depend on the warm
page cache these runs use.

**Charts** (`make-bloom-chart.py`): `bloom_chart.svg`, ms/op, per probe the four
reader × file combinations plus a single-core bar beside each Hardwood bar, which needs
the pinned pass.

### Nested scan — `run-nested.sh`

A full read of the Overture Maps places file (struct / list / map), every leaf of every
record: Hardwood's row reader with typed getters, field positions resolved from the
schema once, and Hardwood's column readers over every leaf column, against
`AvroParquetReader` by name and by index, with a checksum proving they read identical
data.

The download resolves the STAC catalog's latest release and records it in the meta
sidecar as `release`; a file passed with `--file` is recorded as `release unknown`. The
catalog serves only recent releases, so archive the file with a run whose numbers are
published.

**Charts** (`make-nested-chart.py`): `nested_record.svg`, throughput in M rows/s, for
the typed row reader and the column readers each Hardwood all-cores, Hardwood
single-core (needs the pinned pass) and `AvroParquetReader` by index. The footnote gives the schema's leaf count and the share of
compressed bytes in `STRING` columns, which is most of them.

### Nested column readers — `run-nested-columns.sh`

`run-nested.sh`'s benchmark under a second regression preset: `hardwoodColumnReaders` over
the whole file. `run-nested.sh`'s preset reads 20,000 rows, where the read is mostly
per-read setup and the typed row reader is bound by its consumer thread, so neither shows
a change to nested decoding: a 28% faster column-reader read of the whole file left the
20,000-row read unchanged within its noise. Over the whole file the column readers are
bound by decoding. The script takes `run-nested.sh`'s flags and file.

### Fixed-size-list scan — `run-fixedlist.sh`

A full scan of a `LIST<float32>` column of fixed-width vectors (embeddings, 3-D points)
across a sweep of vector lengths `k`, through the column and row readers, with the
fixed-size-list fast path on and off. `flatFloor` reads the same values as a plain float
column, the fastest those bytes move, and the run prints each reader as a multiple of
it. The speedup (baseline ÷ fast) is size-independent, so the `k` sweep runs on 32 MB
files; absolute throughput is not, so the headline points (`k` = 3 and 768) run on
~512 MB files.

The generated file is uncompressed and without dictionary by default;
`-Dperf.compression` and `-Dperf.pageVersion=v1` change that.
`run-overnight-fixedlist.sh` captures the complete set behind the published post, and
`explain-overshoot.sh` attributes the fast path's gap to the flat floor on a machine
without a usable PMU.

**Charts:** `make-fixedlist-bars-chart.py` → `fixedlist_bars.svg`, throughput at one
`k` with the flat floor as a reference line; `make-fixedlist-chart.py` →
`fixedlist_speedup.svg`, speedup against `k`, one line per reader.

### Narrow-projection scan — `run-narrow.sh`

Reads `passenger_count`, `trip_distance` and `fare_amount` of the NYC Yellow Taxi file of
2016-01, folding their present values into a checksum, through Hardwood's column reader and
row reader. The month is one row group of 10.9M rows, GZIP-compressed, with pages of about
0.7M rows: about 15 pages per column. Three columns give few pages in flight at a time, and
the read is long enough that how far the reader decodes ahead of the batch it hands out sets
its throughput. A three-column read of a 2025 month, 3.5M rows in two pages per column and
row group, is a short read, and the flat full scan reads twenty columns. The gate checks both
readers against parquet-java's low-level column API.

### Time window — `run-window.sh`

`event_time >= T` for the most recent 5, 25 and 75 % of a time-sorted event log (10M
rows, 16 MB row groups, SNAPPY), projecting `amount`. Statistics prune the row groups
before `T`, one row group straddles it, and the row groups after it are proven fully
matching, so Hardwood does not read `event_time` in them. `run-filter.sh` reaches only
the two ends of this mix. Hardwood's column and row readers run filtered and
unfiltered; the gate checks them against parquet-java.

### Dictionary filter — `run-dictfilter.sh`

`url IN (5 values)` over a generated file of 5M rows whose one column, `url`, holds
1,000 distinct URLs of about 40 bytes, every page dictionary-encoded (SNAPPY). The
predicate selects about 0.5 % of the rows. Hardwood's row reader projects `url` and
reads each match with `getString`. A version that decides the predicate per entry and
reads matches through the entry ids takes a fraction of the time of one that compares
every row's bytes, or that builds each value's byte view. The gate checks that every
page is dictionary-encoded and that the read agrees with parquet-java.

### Plain binary scan — `run-binary.sh`

A full read of a generated file of 5M rows with two string columns stored `PLAIN`,
without a dictionary (SNAPPY): `text`, required, of 4 to 60 ASCII letters, and `note`,
optional, null in one row of ten, of 8 to 24 letters. `hardwoodColumnViews` reads every
value through the column readers' byte views, `hardwoodRowStrings` every value as a
`String` through the row reader. The gate checks that every page of both columns is
`PLAIN` and that both contenders agree with parquet-java. Needs hardwood-core
1.1.0.Beta2 or later.

### Floating-point encodings — `run-encodings.sh`

The 11 `DOUBLE` columns of NYC taxi 2025-01 (fares, distances, tips, tolls and
surcharges, two-digit decimals, some nullable), the first 1M rows in row groups of 16 MB, under each encoding a
`DOUBLE` column can take: `AUTO` (dictionary or `PLAIN` per chunk, the default),
`PLAIN`, `BYTE_STREAM_SPLIT` and `ALP`, each uncompressed and with ZSTD. Per pair,
`hardwoodRead` scans every column of a file written under it with the column reader, and
`hardwoodWrite` writes the columns to memory with the column writer. The read files are
written by the first Hardwood version that runs, so every version of a regression run
reads the same bytes. The gate writes every pair, reads it back bit for bit against the
source values, and checks that its column chunks declare the encoding named. Each pair's
compressed column-chunk bytes go into the meta sidecar (`bytes.<ENCODING>_<CODEC>`). A
Hardwood version without `ALP` fails to build this benchmark and runs nothing of it.
### 1BRC over Parquet — `run-1brc.sh`

The [One Billion Row Challenge](https://github.com/gunnarmorling/1brc) over Parquet:
min, mean and max of `measurement` per `station`, over a generated file of 100M rows
(`--rows 1000000000` for the full challenge). parquet-java writes it from a seeded
generator with the original challenge's 413 stations, in row groups of 2^20 rows with
both columns dictionary-encoded (ZSTD). `hardwoodColumnIds` aggregates by dictionary
entry id on one consumer thread; `hardwoodColumnSplits` does the same over contiguous
row-group byte ranges, one reader and consumer per available processor, and merges the
tables; `hardwoodColumnFanOut` advances one column reader and hands each batch's arrays to
one of as many aggregating workers as there are available processors, and merges their
tables; `hardwoodRowReader` reads each row with `getString` and `getDouble` into a
`HashMap`; the regression preset leaves it out, since the per-row lookup takes most of its
time. The column contenders need `hardwood-core` 1.1.0.Beta2 or later. The
generator records the aggregate of the values it draws next to the file, and the gate
checks that every page is dictionary-encoded and that every contender prints that
aggregate.

### 1BRC across engines — `run-1brc-engines.sh`

`run-1brc.sh`'s file and aggregate, computed by one program per engine and timed by
[hyperfine](https://github.com/sharkdp/hyperfine) from process start to printed result,
with the OS page cache warm (10 runs after 2 warmup runs; `--runs`, `--warmup`). Hardwood
runs `OneBrc` on the JVM with a class path of `hardwood-core` and `zstd-jni` only, and an
AOT cache the script writes from one training run over the file: the column reader by
dictionary id, one row-group byte-range split per processor. DuckDB runs its CLI, downloaded at the version the script pins; Polars and
PyArrow run a script each from `engines/`, in a venv with the versions
`engines/requirements.txt` pins. Every engine uses all cores. Before timing, the script
checks every output against the aggregate the generator recorded. Results go to
`target/engines/`, with the engine, JVM and machine versions beside them. Needs
hyperfine and `python3`.

### Writes — `run-write.sh`

500K flat, taxi-shaped records (six columns, nulls in two) written to memory through
Hardwood's column writer and row writer, SNAPPY and ZSTD, so the number is encode
throughput. Each file is read back and checked before timing. The meta sidecar records
each codec's compressed column-chunk bytes (`bytes.SNAPPY`, `bytes.ZSTD`), so a version that
writes larger files shows there even where the time does not move.

### S3 reads — `run-s3.sh`

Hardwood's column reader against S3Proxy behind Toxiproxy on the loopback interface,
which adds 30 ms first-byte latency and caps each connection at 80 MB/s
(`S3_LATENCY_MS`, `S3_BANDWIDTH_KBPS`):

| Contender | Read |
| --- | --- |
| `hardwoodProjectedScan` | 3 non-adjacent of 20 taxi columns, one month |
| `hardwoodFilteredScan` | a selective range predicate over the filter corpus, with its page index |
| `hardwoodMultiFileScan` | one column across 12 taxi files through one multi-file reader |
| `hardwoodWideFilteredScan` | a range predicate keeping the first page of every row group, projecting 3 of 200 columns, over a generated file of 40 row groups with 20 pages per column chunk (a 5.3 MB page index); each row group's data is under the coalescing gap, so the read fetches it whole |
| `hardwoodBloomLookup` | an equality probe for a key no row holds and every bloom filter rejects (chosen when the file is generated, and checked on every run), over a generated file of 200 row groups with a unique, unclustered `INT64` key and a bloom filter of tens of KB per row group; statistics keep every row group and its bloom filter drops it |
| `hardwoodLargeBloomLookup` | the same kind of probe over 6 row groups whose bloom filters are 16 MB each |
| `hardwoodLocalBesideRemote` | the projected scan of the local copy of the first month, on a context whose pool a remote projected scan of the same month shares, repeated on a thread of its own for the whole iteration |
| `hardwoodRemoteBesideWideRemote` | the projected scan of the first month, on a context whose pool a remote scan of every column of the same month shares, repeated on a thread of its own for the whole iteration |

Each read is checked against the same read of the local file, and its request and byte
counts go into the meta sidecar (`requests.*`, `bytes.*`), so a change in the fetch
plan shows exactly where the time is noisy. For the wide read, the requests
overlapping the page-index region and their bytes are also recorded apart
(`indexRequests.*`, `indexBytes.*`), beside the file's page-index size and the
index bytes the read needs (`wide.indexRegionBytes`, `wide.neededIndexBytes`).
The two bloom lookups read nothing but the footer and the bloom filters, so their
request counts are the footer reads plus the bloom-filter reads.

`./s3-env.sh start | stop | status` runs the endpoint, downloaded into `target/s3-env/`
on first use and pinned to one core where `taskset` exists (`S3_ENV_CPU`, default the
last core). `run-s3.sh` starts the endpoint when it is not running and stops it
afterwards; an endpoint already running with other settings is an error.

### S3 writes — `run-s3-write.sh`

4M flat, taxi-shaped records (the fixture of `run-write.sh`, about 52 MB under ZSTD) written
through the endpoint's write port, 18082, which adds the 30 ms latency to each response and
caps the bandwidth of each connection's uploads at 80 MB/s. Behind it is a second S3Proxy holding
objects in memory, so completing a multipart upload costs no on-disk copy of the object, which S3
does not pay either:

| Contender | Sink |
| --- | --- |
| `hardwoodS3` | Hardwood's column writer into `S3Source.outputFile`: 8 MiB parts, up to four uploading in the background while the writer fills the next |
| `parquetJavaS3` | parquet-java's `ExampleParquetWriter` into S3A, heap-buffered: 8 MiB parts (`fs.s3a.multipart.size`, lowered from 64 MiB), up to four uploading while writing continues (`fs.s3a.fast.upload.active.blocks`). Creating the file with overwrite first probes the key, one round trip more than `hardwoodS3` makes |
| `hardwoodMemory`, `parquetJavaMemory` | the same writes to memory |

Both writers use the same page targets (1 MiB or 20,000 rows), row-group target, dictionary page
limit and page checksums. An S3 contender's time less its writer's memory time is what the upload
adds. Before timing, both objects are read back through Hardwood and every column checked against
the fixture, as `run-write.sh` checks its files; the meta sidecar records each object's compressed
column-chunk bytes (`bytes.hardwood`, `bytes.parquetJava`), the part size and the endpoint's
latency and bandwidth. The benchmark is in a package of its own, `s3write`, and builds on the
`write` package's classes, so it needs a Hardwood version with `S3Source.outputFile` while
`run-write.sh` does not.

`run-s3-write-python.sh` runs PyArrow and DuckDB beside it, in a virtualenv of pinned versions
under `target/`: each rewrites the file the gate wrote (`target/s3-write-fixture.parquet`, so every
writer encodes the same values) to memory or a local file and to the write port, ZSTD, with the
fixture file's row-group size. PyArrow runs at its default ZSTD level (1) and at level 3 and writes
the page index and page checksums, as the Java writers do; it uploads in parts of its own fixed
size, in the background. DuckDB runs on one thread and on all but the endpoint's core, with 8 MB
upload parts (from its default of about 80 MB, which would send this file as one part after
encoding ends). Every output is read back and compared with the source table; the script prints
each contender's mean and median. Run `./run-s3-write.sh --gate` first.
