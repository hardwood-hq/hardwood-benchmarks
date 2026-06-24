# hardwood-benchmarks

Performance benchmarks comparing [Hardwood](https://github.com/hardwood-hq/hardwood)
and [parquet-java](https://github.com/apache/parquet-java) on Parquet read
workloads. Depends only on `hardwood-core` and `parquet-*` as Maven artifacts.

## Prerequisites

- JDK 21 or newer (`java -version`); tested on 25.
- Bundled Maven wrapper (`./mvnw`) and run scripts — no separate Maven install.
- `dev.hardwood:hardwood-core` resolves at `<hardwood.version>` in `pom.xml`.
  Released versions (e.g. `1.0.0.CR1`) come from Maven Central; a `-SNAPSHOT`
  must first be installed locally from a Hardwood checkout:
  ```sh
  ./mvnw -pl core -am install -Dquick
  ```

## Benchmarks

| Script | Workload | Contenders |
| --- | --- | --- |
| `run-flat.sh` | Full scan of every column (NYC Yellow Taxi) | columnar + record pairs, Arrow reference |
| `run-filter.sh` | Range predicate over a time-clustered file | Hardwood filtered reader vs parquet-java |

Run a script directly (e.g. `./run-flat.sh`); each takes its own flags (below)
plus the [common flags](#common-flags), with `--help` for the full list. Data is
[acquired on first run](#test-data) — no manual setup.

**Two modes.** By default a script **benchmarks** (two timed passes,
[below](#two-passes)). With `--gate` it runs a **gate check** instead: it folds
every contender, verifies they all match the reference checksum, prints a
per-contender confirmation, and exits — no timing, no results file. Gate-check
before a measurement run to prove agreement.

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

```
--start YYYY-MM   first month (default 2025-01)
--end YYYY-MM     last month, inclusive (default 2025-12)
```

### Filtered scan — `run-filter.sh`

A generated, time-clustered `event_time` file (column index, no bloom filters)
read with a range predicate: Hardwood's filtered column reader vs parquet-java's
low-level column API over `readNextFilteredRowGroup()`. Two selectivities —
**selective** (threshold `rows/20`) and **matchAll** (the overhead floor).

```
--rows N              rows in the generated file (default 50000000)
--selectivity VALUE   selective | matchAll (default: both)
```

### Common flags

```
--warmup N / --meas N / --forks N   JMH iterations / forks (defaults 3 / 5 / 1; 0 forks = in-process debug)
--prof gc,stack                     attach JMH profilers
--include REGEX                     restrict to benchmark methods matching REGEX
--no-pin                            all-cores pass only (skip the pinned pass)
--gate                              gate-check mode: verify contenders agree, then exit (no JMH)
--help                              show a script's options
```

Any `-Dperf.*=…` (or other `-D…`) passes straight through to the JVM.

## Quick sanity check

Before committing to the multi-hour publication run, smoke-test the whole pipeline
on a tiny dataset with minimal iterations — one fork, one warmup, one measurement,
a single taxi month, and a 1M-row filter file. It builds, fetches/generates data,
times every contender once, writes the results and `bench-meta` sidecars, and
renders the charts, so it surfaces setup problems (classpath, data download,
contender errors, chart generation) in a couple of minutes. The numbers are
throwaway — not publication-grade:

```sh
./run-flat.sh   --start 2025-01 --end 2025-01 --warmup 1 --meas 1 --forks 1
./run-filter.sh --rows 1000000              --warmup 1 --meas 1 --forks 1
python3 charts/make-charts.py   # renders from the small run's TSVs + meta sidecars
```

The pinned single-core pass is skipped where `taskset` is unavailable (macOS, some
containers; see [Two passes](#two-passes)), so add `--no-pin` to silence its notice.
For a correctness-only check with no timing, use `--gate` instead (next section).

## Reproducing the published numbers

1. Ensure the pinned `<hardwood.version>` is available: a released version
   resolves from Maven Central; a `-SNAPSHOT` must be installed from a clean
   Hardwood checkout (see [Prerequisites](#prerequisites)).
2. Gate-check both benchmarks — folds every contender, verifies they agree, no
   timing. This also builds and fetches data, so it surfaces environment problems
   before the long run:
   ```sh
   ./run-flat.sh --gate
   ./run-filter.sh --gate
   ```
3. Measure the **published** contenders. The flat run restricts timing to the
   published set with `--include`, skipping the non-published references (Arrow
   Dataset and the `SpecificRecord` reader) — the gate in step 2 already verified
   their correctness, and timing them adds time but feeds no chart. The pinned
   (1-core) pass stays Hardwood-only regardless. The filter run has no
   non-published contenders, so it needs no `--include`:
   ```sh
   ./run-flat.sh   --forks 5 --meas 10 --include 'hardwood|parquetJava|avroParquetReaderNamed|avroParquetReaderIndexed'
   ./run-filter.sh --forks 5 --meas 10
   ```

A full measurement run takes a while, and on a shared instance you want the
**median of several runs**, not a single one — run-to-run variance on a shared
host is ~5–10%. Do **three full runs with a 5-minute break between them** (so they
sample across a span of time rather than one momentary machine state), started
**detached** under `tmux` so an SSH drop can't kill it. Each run self-logs to
`target/<bench>.log`, and `capture-run.sh` archives the run into its own directory:

```sh
tmux new -d -s bench '
  for i in 1 2 3; do
    ./run-flat.sh   --forks 5 --meas 10 --include "hardwood|parquetJava|avroParquetReaderNamed|avroParquetReaderIndexed"
    ./run-filter.sh --forks 5 --meas 10
    ./capture-run.sh "results/run-$i"
    [ "$i" -lt 3 ] && sleep 300   # 5-min break between runs, not after the last
  done'
# reattach to watch:  tmux attach -t bench      (detach again: Ctrl-b d)
```

To chart a captured run, point the generator at it: `python3 charts/make-charts.py
--results-dir results/run-2`. Take the per-contender **median** across the three runs
(or a representative run) and quote the run-to-run spread as the error bar.

Take the pinned single-core pass on a Linux host where `taskset` works (see [Two
passes](#two-passes)).

## Two passes

Every script runs two passes:

- **All cores** (out-of-the-box) — times every contender.
- **Single core** (`taskset -c 0`, Linux only) — times only the Hardwood
  contenders, for the engine-for-engine per-core number. The single-threaded
  baselines (parquet-java, Avro, Arrow) aren't re-timed: pinning doesn't change
  them, so their 1-core number equals their default.

There is no separate single-thread contender — each Hardwood reader uses the
default (all-cores) context, and with one core visible its decode pool sizes to
one thread. Run the pinned pass on a Linux host where `taskset` works; a regular
EC2 instance is fine — the published numbers used an AWS m7i.2xlarge, where it
gives stable-enough values. (`-c 0` is one SMT thread sharing a physical core, so
the per-core ratio carries some sibling noise and reads as roughly on par rather
than a precise figure.) macOS can't pin, and the Docker Desktop / linuxkit dev
container pins unreliably; use `--no-pin` there for the all-cores pass only.

Both passes run only in benchmark mode; `--gate` runs neither (it verifies
correctness and exits).

## Test data

Acquired on first run, skipped once present:

- **Flat** — downloads the NYC taxi files. Default location
  `target/tlc-trip-record-data/`, overridable with `--data-dir <path>` (or
  `-Ddata.dir=…`), which the benchmark and its fork both honour.
- **Filtered** — generates its file under `target/`.

`target/` is wiped by `mvn clean`, so point `--data-dir` at a persistent
directory (e.g. `~/.cache/tlc-trip-record-data`) to keep the taxi files across
cleans and avoid re-downloading.

## Output

JMH reports **average time per op** (`ms/op`, lower is better); the full-scan
benchmarks also print a derived **throughput** table (`M rows/s`, `MB/s`). The
filtered scan stays `ms/op` only, since its denominator depends on selectivity.

Each run writes per-benchmark TSVs to `target/` — `bench-throughput-<Benchmark>.tsv`
(the numbers, also echoed as an ASCII bar chart) and `bench-meta-<Benchmark>.tsv`
(dataset parameters the chart generator reads for its subtitles) — and tees its
console output to `target/<bench>.log` (`BENCH_LOG=0` to disable). `./capture-run.sh
<dir>` snapshots that set into a self-contained, chartable archive.

## Charts

After a run, generate the announcement SVGs from its results:

```sh
python3 charts/make-charts.py                         # default: reads target/, writes target/charts/
python3 charts/make-charts.py --results-dir results/run-2   # chart a captured run (writes results/run-2/charts/)
```

It reads each benchmark's TSV from the results directory (`--results-dir`, default
`target`) and writes to `<results-dir>/charts/`:

- `flat_chart1_columnar.svg` and `flat_chart2_record.svg` — from
  `bench-throughput-FlatScanBenchmark.tsv`; throughput (M rows/s, **higher is
  better**) on an axis sized to the data.
- `filtered_chart.svg` — from `bench-throughput-FilterBenchmark.tsv`; ms/op
  (**lower is better**), two selectivity groups, on an axis sized to the data.

Each `.svg` is also rasterized to a `.png` (2× scale) when an SVG→PNG converter
(`rsvg-convert`, `resvg`, `inkscape`, or `cairosvg`) is on `PATH`; otherwise the
SVGs are written alone.

Bar heights, labels, and ratios come from the data; titles, axes, and legends
live in `charts/templates/`. The Arrow and SpecificRecord contenders are never
plotted, and the charts include 1-core bars, so a publish run needs both passes
(no `--no-pin`). Subtitles (row count, size, JVM, date window) come from each run's
`bench-meta-<Benchmark>.tsv`; pass `--machine` for the hardware label, the one
detail not auto-captured. Stdlib Python only.

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
