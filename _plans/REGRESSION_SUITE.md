# Regression suite

Rebuilds the regression layer of the benchmarks into two profiles whose numbers are steady state, recorded nightly from the Intel N300 into a history that Apache Otava analyses. The publication benchmarks and their run mechanism stay as they are.

## Goal

- **Trustworthy numbers.** Every contender is measured after it settles, in three JVMs. A contender still warming during measurement gets "not settled", never a verdict, and is not recorded. A night whose reference version moved is not recorded either.
- **A known false-alarm rate.** At most one false alert a month, established by running the same commit nightly before alerts are trusted.
- **Two profiles.**
  - `quick`, for development: under 10 min on the N300, the version under test only, compared with the median of the last nightly results from the same machine. Elsewhere a baseline version named beside it is interleaved, doubling the time.
  - `nightly`, comprehensive: up to 60 min for one version, `origin/main` on the N300, recorded into the history.
- **A history.** Each nightly run's results go to `hardwood-hq/hardwood-benchmark-results`; Otava finds the change points, and a change point is confirmed before anyone is alerted. A one-time backfill seeds the history with 1.0.0.Final, 1.1.0.Beta1 to Beta3 and selected main commits.

## Principles

| Principle | Consequence |
|---|---|
| Publication benchmarks untouched | Regression-only inputs and settings live in the `--regression` presets and `run-regression.sh`; no benchmark class takes a regression-specific input. |
| Numbers first | A preset's warmup covers its contenders' settle points on the N300 with margin; a contender whose measurement still trends is "not settled". Each contender runs in three JVMs, so a JIT decision that differs between JVMs shows instead of being averaged away. |
| Runnable on any machine | Version adapters (`src/compat`) let every benchmark build against 1.0.0.Final and later. Warmups are sized on the N300, the slowest machine the suite runs on; the settle check catches a slower or loaded one. |
| Hardwood only | No parquet-java, Avro or Arrow contender in a preset. The nightly reference is a pinned Hardwood release. |
| Gates when they can fail | A benchmark's correctness gate runs when its sources or fixtures change, keyed by their hash; core tests cover functional regressions otherwise. |
| Minimal options | `run-regression.sh [--profile quick\|nightly\|smoke] [--out DIR] VERSION [VERSION ...]`. |
| Every contender earns its place | Each contender is listed with the code path only it covers, judged on all four metrics, and the profile it belongs to. |

## Measurement

**Forks and iterations.** Each contender runs in 3 forks of 3 measurement iterations after its preset's warmup; each fork's score is recorded. Fork scores that split into clusters, as a C2 deoptimization makes them, get a verdict of their own ("bimodal") rather than a median.

**Settling.** A contender is not settled when its measurement still trends: the second half of its iterations, across forks, sits more than the noise band below the first. This replaces stage 1's check, which compares three iterations.

**Heap and GC.** Each preset fixes `-Xms`, `-Xmx` and the collector, so that the live-set peak is measured the same way every night and a retention regression meets a collection.

**Reference.** Every nightly run also measures 1.0.0.Final, pinned, on a few contenders. A reference that moves beyond its band marks the night as disturbed (box, JDK, kernel or load), and none of the night's results is recorded.

**Cold start.** One or two paths are also timed as the first operation of a fresh JVM, over many forks, since a library is often used for a single read per process.

**Run time.** After the audit, 32 contenders: a nightly run of main on the N300 takes 37 min, and the quick profile 9 min; the reference adds about 5 min to a nightly run, within the 60 min it may take.

## Stages

Done: stage 1 (#41); stages 2 to 4 (#42); stage 6's code, the nightly job, the history page and the cloud-boxes timer, in the PR stacked on #42. Next: deploying the timer with `--no-alert` for calibration (stage 5), then the backfill and alerts.

1. **Land what is done.**
   - hardwood-benchmarks #41: the 1BRC preset and the writer API.
   - From the `version-adapters` branch: the version adapters, the smoke profile, one build per version, the per-benchmark run order with its tables, the "not settled" verdict, and no parquet-java in presets. The taxi samples and the ad-hoc key suite are dropped.
2. **Contender audit.** A table in `_designs/BENCHMARK_WORKLOAD_COVERAGE.md`: contender, the path it alone covers on any of the four metrics, profile. The maintainer decides what is dropped.
3. **Metrics, then warmups.** The metrics profiler, the fixed heap and GC per preset, and the forks; then per-preset warmups from the N300's settle data for the remaining contenders, measured with all of that in place; the settle and bimodality checks; gate skipping by source and fixture hash. Timed validation runs of both profiles.
4. **Results and Otava.** See [Recording](#recording); the environment manifest; the lock that keeps a nightly run off a busy box.
5. **Calibration.** The same commit nightly for about a week (an A/A run), to set Otava's thresholds for the number of series at the target false-alarm rate. Nothing is backfilled or alerted before.
6. **Nightly on the N300.** The nightly job of [The nightly job](#the-nightly-job) as a systemd timer, deployed through cloud-boxes; the backfill; confirmation and alerting; the history page.
7. **Quick against history.** `quick` compares with the median of the last nightly results from the same machine; user docs for both profiles.

## Recording

**Series.** One series per contender: `series/<machine epoch>/<Benchmark>/<contender>@<epoch>.csv` in `hardwood-benchmark-results`, one row per nightly run with the commit's time, commit, version, when the run began, the schema version, each fork's mean, and its metrics. The files are plain CSV that any tool reads; only `otava.yaml` is Otava's, and it is generated. A metric added later is a new column, empty in older rows. `runs/<date>-<commit>/` keeps each run's raw JMH results, so history can be derived again. `otava.yaml`, generated from the series files, gives Otava one test per file; rows that did not settle or whose forks disagree go to an `.unsettled.csv` twin, which Otava does not read.

**Epochs.** What a contender measures (its preset's parameters, its fixture's checksum and its benchmark method's body) is hashed; a new hash starts a new epoch, and so a new series. When an epoch starts, the nightly run also measures the last release and the previous nightly commit under it, so the new series has comparison points from its first row.

**Environment.** Each run records the JDK build, JVM flags, kernel, microcode, governor and pinned clock, heap settings and the load before it started. The machine and its configuration form a machine epoch, kept apart from the contender's: a replaced box or a new JDK starts a new one, the old and new run side by side for a few nights, and their series are never joined.

**Exclusive box.** A nightly run takes a lock on the N300 and starts only when no other JVM runs and the clock pin is in place; otherwise it records nothing.

**Metrics.** A JMH profiler of the suite's own, in every regression fork, records per measurement iteration:

| Metric | Source | Catches |
|---|---|---|
| wall ms/op | JMH's score | latency |
| CPU ms/op | process CPU time over the operations, with the JIT compiler's and the collector's threads reported apart | more work hidden by parallelism |
| allocated bytes/op | `getTotalThreadAllocatedBytes`, which counts threads that have ended | allocation regressions; close to deterministic, so its band is tight |
| live-set peak | the most heap in use right after a collection during the iteration | memory held too long, as by a run-away read-ahead |

All four come from standard JVM interfaces, so they are recorded on every machine; `quick` records them too, to compare with the nightly series.

**Confirmation and alerts.** A change point Otava finds is confirmed before an alert: the flagged commit and its parent run interleaved, with more forks, in `run-regression.sh`'s two-version mode. A confirmed change point opens an issue on `hardwood-hq/hardwood` naming the series, the commit range, each metric's change and the night's report.

## The nightly job

Otava runs on the N300, as the last step of the nightly job, because confirming a change point needs the box: run in CI on the results repository, it would hand each change point back to the box through a queue.

1. `run-regression.sh --profile nightly` on `origin/main`, recorded with `record-results.py`.
2. `otava analyze --output json` over every series of the machine epoch. Otava fits the whole history each time and returns every change point, old ones included, which may shift as rows arrive.
3. The change points are matched against `changes.json` in the results repository, the change points already handled, by series and commit range; the rest are new.
4. Each new change point is confirmed: its commit and its parent, interleaved, five forks each.
5. One commit, pushed straight to the results repository's `main` by a bot identity: the rows, the run's raw results, `changes.json` with each change point confirmed, refuted or pending, the night's report and the history page.
6. Each confirmed change point opens an issue on `hardwood-hq/hardwood`.

The night's report, `reports/<date>.md` with `reports/latest.md`, lists the contenders run, those not settled or bimodal, each contender against the median of its last nights, and the change points pending confirmation. A failed or skipped night (busy box, failed build) writes a report saying so. A check on the results repository's CI runs `otava validate` on each push.

**History page.** One static page in the results repository, served by GitHub Pages and rewritten each night, reads the series CSVs in the browser. Per benchmark, one row per contender of four charts (wall, CPU, allocation, live-set peak) against commit time: settled rows filled, unsettled and bimodal ones hollow, each fork's mean faint, releases labelled, confirmed change points as lines linking their report. An epoch boundary breaks the line.

## Settled decisions

- `quick` compares against history on the N300 only; on any other machine it needs a baseline version named beside the one under test.
- Reading a sample of a taxi month to shorten flat and narrow is dropped: it changes their inputs and only halved warmup, since a smaller read makes per-read setup a larger share of each operation and that code needs its own invocations to compile.
- Warmup is time-based per preset, sized on the N300. One operation count per script does not fit contenders whose settle points differ twentyfold; operation counts per contender would need a JMH run per contender in every benchmark.
- 3 forks of 3 measurement iterations per contender, nightly.
- The nightly job pushes straight to the results repository's `main`, as a bot identity with a fine-grained token for that repository only, and alerts by opening an issue on `hardwood-hq/hardwood` with a second token, for issues on that repository only.
