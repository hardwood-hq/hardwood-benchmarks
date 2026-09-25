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

| Script | Kind | Workload | Contenders |
| --- | --- | --- | --- |
| [`run-flat.sh`](#flat-full-scan--run-flatsh) | published | Full scan of every column of NYC taxi | Hardwood column and row readers; parquet-java, `AvroParquetReader`, Arrow Dataset |
| [`run-filter.sh`](#filtered-scan--run-filtersh) | published | Range predicate over a time-clustered file | Hardwood filtered column and row readers; parquet-java |
| [`run-bloom.sh`](#bloom-filter-point-lookup--run-bloomsh) | published | Equality lookup on a unique key, bloom file against a statistics-only twin | Hardwood; parquet-java |
| [`run-nested.sh`](#nested-scan--run-nestedsh) | published | Full record read of deeply nested Overture Maps places | Hardwood row reader; `AvroParquetReader` |
| [`run-fixedlist.sh`](#fixed-size-list-scan--run-fixedlistsh) | published | `LIST<float32>` vectors, fast path on and off | Hardwood column and row readers |
| [`run-window.sh`](#time-window--run-windowsh) | regression-only | Recent time window over a time-sorted event log | Hardwood column and row readers |
| [`run-write.sh`](#writes--run-writesh) | regression-only | Flat records written to memory, SNAPPY and ZSTD | Hardwood column and row writers |
| [`run-s3.sh`](#s3-reads--run-s3sh) | regression-only | Reads from an emulated object store | Hardwood column reader |

A **published** benchmark backs a post: its definition is fixed once a post cites it,
the cited runs are archived under `results/`, and it has chart generators of its own.
A **regression-only** benchmark witnesses one scenario, times Hardwood alone over a
fixture of one size, and changes or goes with the code it witnesses. Both kinds serve
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
eight benchmarks takes about 4 min per version on a 1.50 GHz Intel N300. It writes to
`target/regression/`:

- `verdict.txt`: per benchmark, a tally and only the contenders that moved beyond the
  noise band, first version against the last, with control drift reported separately.
- `not-run.txt`: every benchmark or contender a version could not run, with the reason.
- `charts/`: one version chart per benchmark.

More than two versions give a progression, such as 1.0 → 1.1 → 1.2; `--only` restricts
the benchmarks, and `--fail-on-regression` sets the exit status.

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

A full read of the Overture Maps places file (struct / list / map), every record down
to the scalar leaves: Hardwood's row reader against `AvroParquetReader`, by name and by
index, with a checksum proving they assemble identical data.

The download resolves the STAC catalog's latest release and records it in the meta
sidecar as `release`; a file passed with `--file` is recorded as `release unknown`. The
catalog serves only recent releases, so archive the file with a run whose numbers are
published.

**Charts** (`make-nested-chart.py`): `nested_record.svg`, throughput in M rows/s, per
access mode Hardwood all-cores, Hardwood single-core (needs the pinned pass) and
`AvroParquetReader`. The footnote gives the schema's leaf count and the share of
compressed bytes in `STRING` columns, which is most of them.

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

### Time window — `run-window.sh`

`event_time >= T` for the most recent 5, 25 and 75 % of a time-sorted event log (10M
rows, 16 MB row groups, SNAPPY), projecting `amount`. Statistics prune the row groups
before `T`, one row group straddles it, and the row groups after it are proven fully
matching, so Hardwood does not read `event_time` in them. `run-filter.sh` reaches only
the two ends of this mix. Hardwood's column and row readers run filtered and
unfiltered; the gate checks them against parquet-java.

### Writes — `run-write.sh`

500K flat, taxi-shaped records (six columns, nulls in two) written to memory through
Hardwood's column writer and row writer, SNAPPY and ZSTD, so the number is encode
throughput. Each file is read back and checked before timing. The meta sidecar records
each codec's compressed column-chunk bytes (`bytes`, `bytesZstd`), so an encoding change
shows between two versions even where the time does not move.

### S3 reads — `run-s3.sh`

Hardwood's column reader against S3Proxy behind Toxiproxy on the loopback interface,
which adds 30 ms first-byte latency and caps each connection at 80 MB/s
(`S3_LATENCY_MS`, `S3_BANDWIDTH_KBPS`):

| Contender | Read |
| --- | --- |
| `hardwoodProjectedScan` | 3 non-adjacent of 20 taxi columns, one month |
| `hardwoodFilteredScan` | a selective range predicate over the filter corpus, with its page index |
| `hardwoodMultiFileScan` | one column across 12 taxi files through one multi-file reader |

Each read is checked against the same read of the local file, and its request and byte
counts go into the meta sidecar (`requests.*`, `bytes.*`), so a change in the fetch
plan shows exactly where the time is noisy.

`./s3-env.sh start | stop | status` runs the endpoint, downloaded into `target/s3-env/`
on first use and pinned to one core where `taskset` exists (`S3_ENV_CPU`, default the
last core). `run-s3.sh` starts the endpoint when it is not running and stops it
afterwards; an endpoint already running with other settings is an error.
