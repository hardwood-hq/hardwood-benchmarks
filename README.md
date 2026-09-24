# hardwood-benchmarks

Performance benchmarks comparing [Hardwood](https://github.com/hardwood-hq/hardwood)
and [parquet-java](https://github.com/apache/parquet-java) on Parquet read
workloads, and comparing Hardwood versions with each other. Depends only on
`hardwood-core` and `parquet-*` as Maven artifacts.

## Benchmark overview

Each benchmark is a script at the repo root; run it directly (e.g. `./run-flat.sh`),
with its own flags plus the [common flags](#common-flags) and `--help` for the full
list. Data is acquired on first run — no manual setup. A detailed description of
each lives in [The Benchmarks](#the-benchmarks).

| Script | Workload | Contenders |
| --- | --- | --- |
| `run-flat.sh` | Full scan of every column (NYC Yellow Taxi) | Hardwood columnar + record readers ↔ parquet-java / Avro, Arrow reference |
| `run-filter.sh` | Range predicate over a time-clustered file | Hardwood filtered column + row readers ↔ parquet-java |
| `run-bloom.sh` | Equality point-lookup on a unique, unclustered key (generated) | Hardwood ↔ parquet-java, bloom file vs statistics-only twin |
| `run-nested.sh` | Full read of deeply nested struct/list/map records (Overture Maps) | Hardwood row reader ↔ `AvroParquetReader` |
| `run-fixedlist.sh` | Fixed-width vector column (embeddings, points) read with the fast path on vs. off | Hardwood column & row readers, fast path ↔ baseline |

**Two modes.** By default a script **benchmarks** (see [Output](#output) for the
one or two timed passes). With `--gate` it runs a **gate check** instead: it folds
every contender, verifies they all match the reference checksum, prints a
per-contender confirmation, and exits — no timing, no results file. Gate-check
before a measurement run to prove agreement.

## Prerequisites

- JDK 21 or newer (`java -version`); tested on 25.
- Bundled Maven wrapper (`./mvnw`) and run scripts — no separate Maven install.
- `dev.hardwood:hardwood-core` resolves at `<hardwood.version>` in `pom.xml`, or
  at `--hardwood-version` when a run passes one. Released versions (e.g.
  `1.0.0.CR1`) come from Maven Central; a `-SNAPSHOT` must first be installed
  locally from a Hardwood checkout:
  ```sh
  ./mvnw -pl core -am install -Dquick
  ```

## Publication runs

Each benchmark's `--help` carries its exact recipe — gate-check, smoke test,
measurement command, and chart (`./run-flat.sh --help`, and so on). This section
covers only what is common to a publication-grade run of any of them: run the
measurement command from that recipe inside the loop below.

A single timed run isn't publication-grade: on a shared host run-to-run variance is
~5–10%, so quote the **median of several runs**. Do **three full runs with a
5-minute break between them** — sampling across a span of time rather than one
momentary machine state — in a `tmux` session so an SSH drop can't kill them.
Gate-check first (the gate line in the benchmark's `--help` recipe) so environment
problems surface before the long run.

Start the session, then **paste** the loop into it (don't cram it into `tmux new -d
<cmd>` — tmux mangles the embedded quoting) with the benchmark's measurement command
— the measure line of its `--help` recipe — dropped in. Each run self-logs to
`target/<bench>.log` and `capture-run.sh` archives it into its own directory; detach
with `Ctrl-b d` and the loop keeps running:

```sh
tmux new -s bench        # then paste:
for i in 1 2 3; do
  <benchmark measurement command>              # e.g. ./run-flat.sh --forks 5 --meas 10 --include "…"
  ./capture-run.sh results/2026-06-25-hardwood-1.0/run-$i
  [ $i -lt 3 ] && sleep 300   # 5-min break between runs, not after the last
done
```

Results are filed one directory per publication, `results/<YYYY-MM-DD>-<slug>/run-N/`
— use a fresh dated dir for a new post/release. Take the per-contender **median**
across the three runs and quote the run-to-run spread as the error bar; re-chart a
captured run by pointing its generator at the dir with `--results-dir` (see
[Charts](#charts)). Run the pinned single-core pass on a Linux host where `taskset`
works (see [Output](#output)).

## The Benchmarks

Each benchmark acquires its data on first run and skips it once present; `target/`
is wiped by `mvn clean`, so point a benchmark's data flag at a persistent directory
to keep large downloads across cleans.

### Flat full scan — `run-flat.sh`

Reads every column of the monthly taxi files, folding each into a per-file
checksum. Two API pairs:

- **Columnar** — Hardwood column reader ↔ parquet-java low-level column API, with
  **Arrow Dataset** (Arrow C++ over JNI, in the same harness) as a cross-engine
  reference.
- **Record** — Hardwood row reader ↔ `AvroParquetReader`, each in **both access
  modes**: named (`getDouble("fare_amount")`) and indexed (positional). A typed
  `AvroParquetReader` **`SpecificRecord`** contender runs alongside as an internal
  reference (gated, never plotted), confirming the lead is not a `GenericRecord`
  artifact.

Field access is materialized to the 2025 TLC schema with monomorphic,
schema-specific reads, so the timings reflect decode work. Consequently
`--start`/`--end` must stay within the 2025 layout (20 columns) — another schema
folds the wrong types and fails the gate.

**Run:** `./run-flat.sh --help` — gate, smoke test, measure (`--include` the
published contenders), chart.

**Data.** Downloads the NYC Yellow Taxi files on first run. `--data-dir` (or
`-Ddata.dir=…`, honoured by the benchmark and its fork) relocates the cache; point
it at a persistent directory (e.g. `~/.cache/tlc-trip-record-data`) to survive
`mvn clean` and avoid re-downloading.

**Charts** (`make-flat-chart.py`) — `flat_chart1_columnar.svg` (columnar pair) and
`flat_chart2_record.svg` (record pair), both throughput (M rows/s, **higher is
better**). The Arrow Dataset and `SpecificRecord` contenders are gated but never
plotted.

### Filtered scan — `run-filter.sh`

A generated, time-clustered `event_time` file (column index, no bloom filters)
read with a range predicate: Hardwood's filtered column reader vs parquet-java's
low-level column API over `readNextFilteredRowGroup()`, with Hardwood's filtered
row reader (projecting `amount`) beside them. Two selectivities —
**selective** (threshold `rows/20`) and **matchAll** (the overhead floor).
Unfiltered controls read `amount` alone (both Hardwood readers, parquet-java) and both columns
(parquet-java), separating decode speed from what filtering costs or saves.

**Run:** `./run-filter.sh --help` — gate, smoke test, measure, chart.

**Data.** Generated under `target/` on first run, keyed on the row count so a
different `--rows` regenerates rather than reusing a stale file.

**Charts** (`make-filter-chart.py`) — `filtered_chart.svg`, ms/op (**lower is
better**), the two selectivity groups on a broken axis so the match-all bar stays
readable next to the selective one. The row reader and the controls are gated but not plotted.

### Bloom-filter point lookup — `run-bloom.sh`

An equality push-down (`key = k`) on a generated **unique, unclustered 64-bit key**
— the workload bloom filters exist for — against a bloom-filter-bearing file and a
statistics-only twin holding identical rows. Because the key is unique and
pseudorandomly ordered, neither row-group statistics, the column index, nor a
dictionary can prune it, leaving the bloom filter as the only pruner. Each reader
(Hardwood, parquet-java) probes both files, isolating what a bloom filter buys
(`hardwoodBloom` vs `hardwoodNoBloom`) and Hardwood's bloom against parquet-java's on
the same file. Two probes: `present` (matches one row, so the bloom keeps one row
group) and `absent` (in range everywhere, so the bloom drops every row group — the
case statistics cannot catch). Hardwood does not yet *write* bloom filters, so both
files are written by parquet-java; this is a read-path comparison only.

On the absent probe both readers prune identically and read byte-identical ranges, so
the gap is filter *materialization*, not pruning: Hardwood probes the mmapped filter
in place (~19 KB/op) while parquet-java copies each filter onto the heap (~63 MB/op).
Note that mmap benefits from the warm page cache these runs use.

**Run:** `./run-bloom.sh --help` — gate, smoke test, measure, chart.

**Data.** Generated on first run, no download: keys come from a 64-bit bijection of
the row index, so they are exactly unique and reproducible from the row count alone.
The default 84M rows spans ~10 row groups (~8.4M rows each); expect ~2.9 GB for the
file pair, keyed on row count so a different size generates a fresh pair. The
benchmark raises `parquet.bloom.filter.max.bytes` to 10 MB — Parquet's 1 MB default
silently clamps the filter to ~99.7% FPP, which prunes nothing.

**Charts** (`make-bloom-chart.py`) — `bloom_chart.svg`, ms/op (**lower is better**),
the two probe groups (present, absent), each with the four all-cores read paths
(Hardwood/parquet-java × bloom/no-bloom) plus a hatched single-core (`taskset -c 0`)
bar beside each Hardwood bar. The single-core bars need the pinned pass, so this
requires a full run — not `--no-pin`.

### Nested scan — `run-nested.sh`

A full read of the single-file Overture Maps places dataset — deeply nested
struct / list / map — comparing the record pair: Hardwood's row reader against
`AvroParquetReader`. Both reconstruct every record down to the scalar leaves, so
neither skips work the other performs; a representation-stable checksum proves they
assemble identical data before any timing counts. The file is single, so there is
no cross-file asymmetry — the parallel advantage is purely within-file concurrent
decode. (Not part of the 1.0 publication; kept as the like-for-like nested record
comparison.)

**Run:** `./run-nested.sh --help` — gate, smoke test, measure, chart.

**Data.** Downloads the Overture places file on first run to the default path
`target/overture-maps-data/overture_places.zstd.parquet`; `--file` points at an
existing file instead. The download resolves the STAC catalog's latest release and
writes its identifier to a `.release` sidecar beside the file, which the run records
in its `bench-meta` sidecar as `release`. The catalog serves only the most recent
releases, so keep the file itself with an archived run — once its release rotates out
of the bucket the exact bytes a published number was measured on are gone. A file
supplied with `--file` has no sidecar and is recorded as `release unknown`.

**Charts** (`make-nested-chart.py`) — `nested_record.svg`, throughput in M rows/s
(**higher is better**), the two access groups (named, indexed), each with Hardwood
all-cores, Hardwood single-core (`taskset -c 0`), and `AvroParquetReader`. The
single-core bars need the pinned pass, so this requires a full run — not `--no-pin`.
The footnote carries the schema composition from the run's meta (leaf count, and the
share of compressed bytes held by `STRING` columns): the schema is deeply nested in
shape, but most of its bytes are strings, and a reader of the chart is owed that.

### Fixed-size-list scan — `run-fixedlist.sh`

A full scan of a `LIST<float32>` column of fixed-width vectors (embeddings, 3-D
points) with the fixed-size-list fast path **on and off**, across a sweep of vector
lengths `k`, through both the column reader and the row reader. Every contender is
the Hardwood reader — fast path vs. reconstruction baseline, plus a flat-column
decode floor — so the run is all-cores only. This is the macro, whole-file
counterpart to core's micro `FixedSizeListDecodeBenchmark`, producing the
speedup-vs-`k` curve and the headline embedding / 3-D-point numbers for the blog
post. The `flatFloor` contender is a single columnar read of the same values as a
plain float column — the fastest these bytes move — and `main` prints each reader's
time as a multiple of it (`column/floor`, `row/floor`); a row-read of the flat
column is not a floor (`k`× more rows, so per-row overhead dominates).

Two file sizes feed the two charts: the speedup **ratio** (baseline ÷ fast) is
size-robust — per-file fixed costs cancel between fast and baseline — so the `k`
sweep runs on cheap 32 MB files; absolute **throughput** is size-sensitive, so the
two headline points (`k = 3` 3-D points, `k = 768` embeddings) are measured on
realistic ~512 MB files, out of cache and past per-file overhead. All-cores only —
every contender is Hardwood, so there is no pinned single-core pass.

**Run:** `./run-fixedlist.sh --help` — gate, smoke test, the two sweep/headline
runs (captured separately), and the publication tmux loop.

**Data.** Generated on demand by `FixedSizeListFileGenerator` as a 3-level
compliant required `LIST<float32>` (the shape the reader accelerates), no dictionary
and no compression, so a bare run needs no pyarrow venv. `-Dperf.pageVersion=v1`
selects DataPageV1 (default V2); both are fast-pathed.

**Charts** — two generators, both reading this benchmark's TSV:

- `make-fixedlist-bars-chart.py` → `fixedlist_bars.svg` (the lead visual): absolute
  read throughput (M float32 values/s) at one `k` (`--k`, default 768) — column and
  row readers, baseline vs. fast, with the flat-column floor as a dashed reference
  line. Uses the `values` denominator from the `bench-meta` sidecar.
- `make-fixedlist-chart.py` → `fixedlist_speedup.svg`: fast-path speedup
  (baseline ÷ fast) vs. vector length `k`, one line per reader — shows the win holds
  across vector lengths.

## Comparing versions

Beyond backing a post, a run is evidence about one build of Hardwood against
another — a regression, or an improvement. Two pieces make that comparison:

**Pick the version at run time.** `--hardwood-version` overrides
`<hardwood.version>` from the command line, so a version switch is not a `pom.xml`
edit:

```sh
./run-flat.sh --hardwood-version 1.0.0.Final       # a release, from Maven Central
./run-flat.sh --hardwood-version 1.1.0-SNAPSHOT    # a local install, see Prerequisites
```

It reaches Maven, not the JVM — `hardwood.version` is a pom property, so a bare
`-Dhardwood.version=…` on the command line would land on `java` where nothing
reads it, and the run would quietly measure the pom's version instead. The
benchmark classes are recompiled whenever the requested version changes, since
they track the current API. Each script compiles only the shared classes and its
own benchmark's package (`BENCH_PACKAGES`), so a benchmark using something a
version does not have fails its own build and no other: against 1.0.0.Final,
`run-fixedlist.sh` does not build, and every other script does. A contender that
builds but throws on an older version (1.0.0.Final's row reader rejects a filter
on a column outside the projection) fails in JMH, which carries on with the rest;
the contender is missing from that version's results, and the version chart shows
it as not run.

**Run the quick regression configuration.** `--regression` fixes everything a run
could vary, so regression runs taken at any time are alike and take minutes, not
hours:

- **Sizes and contenders:** each script's preset, listed at the end of its `--help`:
  a one-month taxi window, the filter corpus at 5M rows, bloom at 8M, the nested scan
  at a 20K-row prefix, fixed-size lists at `k` = 768, and one contender per Hardwood
  read path. The only non-Hardwood
  contender is `run-filter.sh`'s parquet-java scan, a control whose drift tells a
  moving machine from a changed Hardwood.
- **Iterations:** 3 warmup and 3 measurement iterations of 1 s each.
- **One pass, on all cores.** Pinned to one core, the JIT, the GC and the reader's
  worker threads share that core, and a contender is still about 15 % off its steady
  state after ten 1 s iterations. On all cores it settles by the third.

Passing a flag the preset sets is an error. The meta sidecar records `preset`
(`regression` or `none`), and both `compare-runs.py` and the version chart warn when
two snapshots differ in it. Regression numbers are not measured to a benchmark's
published definition and are compared only with each other.

**Check for regressions.** `./run-regression.sh BASE NEW` runs every script under
`--regression` for each version, in interleaved rounds (`--rounds`, default 3), then
writes the verdict: per benchmark, a tally and only the contenders that moved beyond
the noise band, first version against the last, with control drift called out. The
contenders a version did not run are listed with the reason from the logs, and the
version charts are rendered beside it. `--fail-on-regression` makes the exit status
say whether a Hardwood contender got slower.

**Difference the two snapshots.** `charts/compare-runs.py` takes a base and a new
snapshot — each a run directory, or a directory of `run-*` repeats — and prints
what moved:

```sh
python3 charts/compare-runs.py results/2026-06-25-hardwood-1.0 results/2026-09-10-hardwood-1.1
```

```
FlatScanBenchmark
  base  1.0.0.Final (a1b2c3d)   Java 25 (Eclipse Adoptium)   AWS m7i.2xlarge   3 runs
  new   1.1.0-SNAPSHOT (7d283f5)   Java 25 (Eclipse Adoptium)   AWS m7i.2xlarge   3 runs

  pass      contender                    base ms      new ms     delta     band  verdict
  unpinned  hardwoodColumnar            2945.454    2415.272    -18.0%     8.0%  faster
  unpinned  hardwoodRowReaderIndexed    3255.397    3613.491    +11.0%    10.5%  slower

2 contenders compared, 1 slower
```

Times are `ms_per_op`, so a negative delta is faster. A delta is only called when
it clears a noise band: with repeats on both sides the band is each side's own
observed spread, `(max - min) / median`, halved and added — the data sets the bar.
With a single run on either side there is nothing in the snapshots that measures
noise, so the band falls back to `--threshold` (default 5%, the low end of the
~5–10% run-to-run variance a shared host shows) and the report says which it used.
This is the reason a comparison worth acting on uses the median-of-three loop under
[Publication runs](#publication-runs) on both sides.

Contenders and benchmarks present on only one side are listed rather than dropped,
and the meta sidecars are checked before any number is read: a differing `machine`,
`java`, or dataset key, or two snapshots recording the same Hardwood build, each
draw a warning. `--include REGEX` narrows to some contenders, `--format tsv` emits
the table for a script, and `--fail-on-regression` exits non-zero if anything got
slower.

**Chart them.** `charts/make-version-chart.py` takes two or more snapshots, oldest
first, and charts every Hardwood contender across them, one chart per benchmark and
pass. Two snapshots give a before/after; more give a progression, such as 1.0 → 1.1 →
1.2:

```sh
python3 charts/make-version-chart.py <base> <new> [<newer> ...] [--out DIR]
```

Each snapshot is labelled with the Hardwood build its meta sidecar records (or
`--label`), drawn at its median with its repeats' spread as a whisker, and a
contender a snapshot lacks is drawn as not run. Beside each contender is its time in
the earliest snapshot that ran it over its time in the last. Contenders matching
`--control` (default `^(parquetJava|avro|arrow)`) are not charted: they are pinned
in the pom, so they serve as a control: the chart warns when one moves beyond its noise band
between two consecutive snapshots, as well as on the sidecar mismatches
`compare-runs.py` warns about. Output goes to `target/version-charts/` unless `--out`
names a directory.

### Common flags

Every script shares a set of flags — `--warmup`/`--meas`/`--forks`, `--prof`,
`--include`, `--time`, `--no-pin`, `--gate`, `--hardwood-version`, `--regression`, `--help` — each documented
by the script's own `--help`. Any `-Dperf.*=…` (or other `-D…`) passes straight through to the JVM.

## Output

JMH reports **average time per op** (`ms/op`, lower is better); some benchmarks also
print a derived **throughput** table (`M rows/s`, `MB/s`) where a stable denominator
exists.

Some benchmarks run two passes: **all cores** (out of the box) times every contender,
and **single core** (`taskset -c 0`, Linux only) re-times just the Hardwood
contenders for a per-core figure — the single-threaded baselines aren't re-timed,
since pinning doesn't change them. `run-fixedlist.sh` runs the all-cores pass only. Both passes run only when benchmarking; `--gate` runs
neither.

Each run writes per-benchmark TSVs to `target/` — `bench-throughput-<Benchmark>.tsv`
(the numbers, also echoed as an ASCII bar chart) and `bench-meta-<Benchmark>.tsv`
(dataset parameters the chart generator reads for its subtitles) — and tees its
console output to `target/<bench>.log` (`BENCH_LOG=0` to disable). `./capture-run.sh
<dir>` snapshots that set into a self-contained, chartable archive.

Every meta sidecar carries `java`, `hardwood` (version and commit), `machine`, and
`simd` alongside the benchmark's own dataset keys. `simd` is `scalar` or
`simd-<N>bit`: Hardwood engages its vectorized paths only on a JVM launched with
`--add-modules jdk.incubator.vector`, which the run scripts deliberately do not pass,
so a run records `scalar` unless that flag reaches the JVM from the environment.
Quote SIMD-enabled and scalar numbers separately — they are different measurements.

## Charts

Every benchmark charts across Hardwood versions with `make-version-chart.py` (see
[Comparing versions](#comparing-versions)). A published benchmark also has one or more `charts/make-<benchmark>-*chart.py` generators
(stdlib Python, sharing `charts/chartlib.py`) that read `bench-throughput-*.tsv` and
its `bench-meta` sidecar from a results dir (`--results-dir`, default `target/`) and
write SVGs to `<results-dir>/charts/`; point one at a captured run to re-render it.
Each `.svg` is also rasterized to `.png` when an SVG→PNG converter (`rsvg-convert`,
`resvg`, `inkscape`, or `cairosvg`) is on `PATH`. Pass `--machine` for the hardware
label — the one subtitle detail not captured in `bench-meta`.

## Profiling

Attach a JMH profiler with `--prof`, narrowing with `--include`:

```sh
./run-flat.sh --include "hardwoodColumnar|hardwoodRowReaderIndexed" --prof gc           # allocation per op
./run-flat.sh --include hardwoodColumnar --prof stack                                   # sampled stacks
./run-flat.sh --include hardwoodColumnar --prof perfnorm --forks 3                      # CPU counters (needs perf + PMU)
./run-flat.sh --include hardwoodColumnar --prof "async:output=flamegraph;event=itimer"  # async-profiler
```

`gc`, `stack`, and async-profiler (`itimer`/`alloc`) work anywhere. `perfnorm`
(cache-misses, IPC) needs Linux `perf` **and** a host-exposed PMU — most cloud
VMs don't expose it (`<not supported>`); use bare-metal for hardware counters.
On a bare-metal host, `perfasm` also needs `kernel.perf_event_paranoid` ≤ 1 for
kernel frames and hsdis in the JDK's `lib/` to disassemble. Comparable timings need
a fixed clock: governor `performance`, `scaling_max_freq` capped at the clock the
package sustains with all cores busy, and no background timers firing mid-run.
[`profiling-setup`](https://github.com/gunnarmorling/cloud-boxes/blob/master/ansible/roles/bench_host/files/profiling-setup)
applies and reverts these settings for one session.

`--batch-size N` overrides the Hardwood column reader's batch size (e.g. to test
cache-residency effects on single-core throughput).
