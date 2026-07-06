# hardwood-benchmarks

Performance benchmarks comparing [Hardwood](https://github.com/hardwood-hq/hardwood)
and [parquet-java](https://github.com/apache/parquet-java) on Parquet read
workloads. Depends only on `hardwood-core` and `parquet-*` as Maven artifacts.

## Benchmark overview

Each benchmark is a script at the repo root; run it directly (e.g. `./run-flat.sh`),
with its own flags plus the [common flags](#common-flags) and `--help` for the full
list. Data is acquired on first run — no manual setup. A detailed description of
each lives in [The Benchmarks](#the-benchmarks).

| Script | Workload | Contenders |
| --- | --- | --- |
| `run-flat.sh` | Full scan of every column (NYC Yellow Taxi) | Hardwood columnar + record readers ↔ parquet-java / Avro, Arrow reference |
| `run-filter.sh` | Range predicate over a time-clustered file | Hardwood filtered reader ↔ parquet-java |
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
- `dev.hardwood:hardwood-core` resolves at `<hardwood.version>` in `pom.xml`.
  Released versions (e.g. `1.0.0.CR1`) come from Maven Central; a `-SNAPSHOT`
  must first be installed locally from a Hardwood checkout:
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
low-level column API over `readNextFilteredRowGroup()`. Two selectivities —
**selective** (threshold `rows/20`) and **matchAll** (the overhead floor).

**Run:** `./run-filter.sh --help` — gate, smoke test, measure, chart.

**Data.** Generated under `target/` on first run, keyed on the row count so a
different `--rows` regenerates rather than reusing a stale file.

**Charts** (`make-filter-chart.py`) — `filtered_chart.svg`, ms/op (**lower is
better**), the two selectivity groups on a broken axis so the match-all bar stays
readable next to the selective one.

### Nested scan — `run-nested.sh`

A full read of the single-file Overture Maps places dataset — deeply nested
struct / list / map — comparing the record pair: Hardwood's row reader against
`AvroParquetReader`. Both reconstruct every record down to the scalar leaves, so
neither skips work the other performs; a representation-stable checksum proves they
assemble identical data before any timing counts. The file is single, so there is
no cross-file asymmetry — the parallel advantage is purely within-file concurrent
decode. (Not part of the 1.0 publication; kept as the like-for-like nested record
comparison.)

**Run:** `./run-nested.sh --help` — gate, smoke test, measure (numbers only, no
chart).

**Data.** Downloads the Overture places file on first run to the default path
`target/overture-maps-data/overture_places.zstd.parquet`; `--file` points at an
existing file instead.

**Charts.** None — the nested record comparison is reported as numbers only.

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

### Common flags

Every script shares a set of flags — `--warmup`/`--meas`/`--forks`, `--prof`,
`--include`, `--no-pin`, `--gate`, `--help` — each documented by the script's own
`--help`. Any `-Dperf.*=…` (or other `-D…`) passes straight through to the JVM.

## Output

JMH reports **average time per op** (`ms/op`, lower is better); some benchmarks also
print a derived **throughput** table (`M rows/s`, `MB/s`) where a stable denominator
exists.

Some benchmarks run two passes: **all cores** (out of the box) times every contender,
and **single core** (`taskset -c 0`, Linux only) re-times just the Hardwood
contenders for a per-core figure — the single-threaded baselines aren't re-timed,
since pinning doesn't change them. Benchmarks with no engine-for-engine comparison
run the all-cores pass only. Both passes run only when benchmarking; `--gate` runs
neither.

Each run writes per-benchmark TSVs to `target/` — `bench-throughput-<Benchmark>.tsv`
(the numbers, also echoed as an ASCII bar chart) and `bench-meta-<Benchmark>.tsv`
(dataset parameters the chart generator reads for its subtitles) — and tees its
console output to `target/<bench>.log` (`BENCH_LOG=0` to disable). `./capture-run.sh
<dir>` snapshots that set into a self-contained, chartable archive.

## Charts

A charted benchmark has one or more `charts/make-<benchmark>-*chart.py` generators
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

`--batch-size N` overrides the Hardwood column reader's batch size (e.g. to test
cache-residency effects on single-core throughput).
