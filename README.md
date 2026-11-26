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
`target/`, which `./mvnw clean` wipes; `run-flat.sh --data-dir` and `run-nested.sh --file`
point at a persistent location instead.

`hardwood-core` resolves at `<hardwood.version>` in `pom.xml`, or at `--hardwood-version`.
Releases come from Maven Central; a `-SNAPSHOT` must be installed from a Hardwood checkout
first with `./mvnw -pl core -am install -Dquick`.

## Benchmarks

| Script | Kind | Measures |
| --- | --- | --- |
| `run-flat.sh` | published | Full-scan decode throughput, every column of NYC taxi |
| `run-filter.sh` | published | Page-level filtering on a selective and a match-all predicate |
| `run-bloom.sh` | published | Bloom-filter point lookups |
| `run-nested.sh` | published | Record assembly of structs, lists and maps |
| `run-fixedlist.sh` | published | The fixed-size-list fast path against flat-column speed |
| `run-nested-columns.sh` | regression-only | Nested decoding over the whole Overture file |
| `run-narrow.sh` | regression-only | A long read of three columns |
| `run-wide.sh` | regression-only | Every column of a 10,000-column file |
| `run-window.sh` | regression-only | A time-window predicate over a sorted event log |
| `run-dictfilter.sh` | regression-only | A string `IN` predicate over a dictionary-encoded column |
| `run-binary.sh` | regression-only | `PLAIN` string columns |
| `run-encodings.sh` | regression-only | `DOUBLE` encodings, read and write time beside size |
| `run-1brc.sh` | regression-only | 1BRC over Parquet |
| `run-write.sh` | regression-only | Encode throughput and compressed size |
| `run-s3.sh` | regression-only | Reads from an emulated object store |
| `run-s3-write.sh` | mixed-use | Writes to an emulated object store |
| `run-1brc-engines.sh` | engine comparison | 1BRC across Hardwood, DuckDB, Polars and PyArrow |

[BENCHMARKS.md](BENCHMARKS.md) defines the kinds and describes each benchmark: its
workload, contenders, gate, requirements and charts.
[`_designs/BENCHMARK_WORKLOAD_COVERAGE.md`](_designs/BENCHMARK_WORKLOAD_COVERAGE.md) holds
the plan for the suite, including the benchmarks still to come.

## Running a benchmark

`--gate` runs every contender once, checks their results against the reference, and exits.
Gate-check before any long run. Without `--gate`, the script benchmarks in two passes: all
cores, timing every contender, then one core (`taskset -c 0`, Linux only), timing only the
Hardwood contenders.

| Flag | Effect |
| --- | --- |
| `--warmup`, `--meas`, `--forks`, `--time` | JMH iterations, forks and iteration time |
| `--include REGEX` | the contenders to run |
| `--no-pin`, `--pin-only` | skip the single-core pass, or the all-cores one |
| `--prof P` | attach a JMH profiler (see [Profiling](#profiling)) |
| `--machine LABEL` | the hardware label recorded with the results |
| `--hardwood-version V` | the `hardwood-core` version to build against |
| `--regression` | the benchmark's fixed regression preset |
| `-D…` | passed through to the JVM |

Each script's `--help` lists these and its own flags; the scripts share `bench-common.sh`,
which parses them, builds the benchmarks for the selected version and runs the passes. `--hardwood-version` sets a pom
property; a bare `-Dhardwood.version=…` reaches the JVM instead and measures the pom's
version. `-Dperf.threads=N` sizes the pool of every Hardwood contender's context, reads and
writes alike.

JMH reports average time per op (`ms/op`, lower is better); some benchmarks also print
throughput (`M rows/s`, `MB/s`). A run writes to `target/`:

- `bench-throughput-<Benchmark>.tsv`: the numbers.
- `bench-meta-<Benchmark>.tsv`: the dataset parameters, the files the contenders read
  (`fixture`, and `fixture.<contender>` where a contender reads other files), plus `java`,
  `hardwood` (version and commit), `machine`, `preset`, `threads` and `simd`. `simd` is
  `scalar` unless `--add-modules jdk.incubator.vector` reaches the JVM from the environment,
  which the scripts do not pass; quote SIMD and scalar numbers separately.
- `<bench>.log`: the console output (`BENCH_LOG=0` disables it).

`./capture-run.sh <dir>` copies that set into a self-contained snapshot, which the
comparison and chart tools read.

## Comparing versions

`./run-regression.sh` runs the benchmarks' `--regression` presets for one Hardwood version,
or for several, oldest first, interleaved within each round.

```sh
./run-regression.sh 1.1.0-SNAPSHOT                          # quick profile, one version
./run-regression.sh 1.1.0.Beta2 1.1.0-SNAPSHOT              # quick profile, compared
./run-regression.sh --profile nightly 1.1.0-SNAPSHOT        # every contender
JAVA_TOOL_OPTIONS=-Dperf.threads=6 ./run-regression.sh BASE NEW   # a fixed pool size
```

| Profile | Runs | Time for one version on a 1.50 GHz Intel N300 |
| --- | --- | --- |
| `quick` (default) | the contenders covering the core read and write paths | about 9 min |
| `nightly` | every preset contender | about 39 min |
| `smoke` | every contender once, without warmup, to check which run on which version | a few minutes |

It writes to `target/regression/`:

- `tables.md`: per benchmark, each version's median over its forks, and the last version
  against each earlier one (`charts/version-table.py`).
- `verdict.txt`, with several versions: the contenders that moved beyond the noise band,
  first version against the last, and for the write benchmarks each file's size, larger or
  smaller beyond 1 %.
- `not-run.txt`: what a version did not run, and why.
- `<version>/run-<n>/`: the results files, the `RegressionProfiler` metrics
  (`bench-metrics-*.jsonl`) and `manifest.json`, the machine and JVM the run used.

`--only`, `--rounds` and `--fail-on-regression` are described in `./run-regression.sh --help`.
[The regression suite](_designs/REGRESSION_SUITE.md) describes the presets, what a fork
records, the settled and bimodal verdicts, the gates, and the nightly job on the N300, whose
results the [history page](https://hardwood.dev/hardwood-benchmark-results/) charts.

## Publication runs

A published number is the median of three full runs, taken with 5-minute breaks between them
on a machine with a fixed clock (see [Profiling](#profiling)). Gate-check first, then paste
this loop into a `tmux` session, with the measure line from the benchmark's `--help` recipe
(tmux mangles the quoting if the loop is passed to `tmux new` directly):

```sh
tmux new -s bench        # then paste:
for i in 1 2 3; do
  <measure line>                     # e.g. ./run-flat.sh --forks 5 --meas 10 --include "…"
  ./capture-run.sh publications/<YYYY-MM-DD>-<slug>/run-$i
  [ $i -lt 3 ] && sleep 300
done
```

`charts/median-runs.py` writes a snapshot of the per-contender medians of the three runs.
[`publications/`](publications/README.md) holds one directory per publication, each with a
README of its own.

Each published benchmark has `charts/make-<benchmark>-*chart.py` generators, listed in
[BENCHMARKS.md](BENCHMARKS.md). They read a snapshot (`--results-dir`, default `target/`),
write SVGs to its `charts/` directory, and rasterize each to PNG when `rsvg-convert`,
`resvg`, `inkscape` or `cairosvg` is on `PATH`. The hardware label comes from the meta
sidecar's `machine`; `--machine` overrides it. `charts/make-two-version-charts.py OLD NEW`
draws the published flat and filter charts with two Hardwood versions side by side.

## Profiling

Attach a JMH profiler with `--prof`, narrowed with `--include`:

```sh
./run-flat.sh --include "hardwoodColumnar|hardwoodRowReaderIndexed" --prof gc           # allocation per op
./run-flat.sh --include hardwoodColumnar --prof stack                                   # sampled stacks
./run-flat.sh --include hardwoodColumnar --prof perfnorm --forks 3                      # CPU counters
./run-flat.sh --include hardwoodColumnar --prof "async:output=flamegraph;event=itimer"  # async-profiler
```

`gc`, `stack` and async-profiler (`itimer`, `alloc`) work anywhere. `perfnorm` and `perfasm`
need Linux `perf` and a host-exposed PMU, which most cloud VMs lack; `perfasm` also needs
`kernel.perf_event_paranoid` ≤ 1 and hsdis in the JDK's `lib/`. Comparable timings need a
fixed clock: governor `performance`, `scaling_max_freq` capped at the clock the package
sustains with all cores busy, and no background timers firing mid-run.
[`profiling-setup`](https://github.com/gunnarmorling/cloud-boxes/blob/master/ansible/roles/bench_host/files/profiling-setup)
applies and reverts these settings for one session.

## Tools

Each documents its options in `--help`.

| Tool | Does |
| --- | --- |
| `capture-run.sh DIR` | copies a run's results into a self-contained snapshot |
| `charts/compare-runs.py BASE NEW` | differences two snapshots, calling a change only beyond a noise band; warns when they differ in machine, Java, dataset, preset or pool size |
| `charts/make-version-chart.py` | charts every Hardwood contender across any number of snapshots |
| `charts/median-runs.py` | writes a snapshot of the per-contender medians of several runs |
| `charts/record-results.py` | appends a version's run to a checkout of [hardwood-benchmark-results](https://github.com/hardwood-hq/hardwood-benchmark-results) |
| `charts/against-history.py` | compares an N300 run with the latest nightly results |
| `run-s3-fetch-check.sh BASE HEAD` | checks a change to Hardwood's fetching on the S3 benchmark: exact request and byte counts, optionally timed |
| `s3-env.sh start\|stop\|status` | runs the emulated S3 endpoint |
| `nightly.sh` | the N300's nightly regression job |
