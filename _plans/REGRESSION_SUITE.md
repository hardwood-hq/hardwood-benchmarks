# Regression suite

Rebuilds the regression layer of the benchmarks into two profiles whose numbers are steady state, recorded nightly from the Intel N300 into a history that Apache Otava analyses. The publication benchmarks and their run mechanism stay as they are.

## Goal

- **Trustworthy numbers.** Every contender is measured after it settles, in three JVMs. A contender still warming during measurement gets "not settled", never a verdict, and is not recorded. A night whose reference version moved is not recorded either.
- **A known false-alarm rate.** At most one false alert a month, established from nights run with alerts off before alerts are trusted.
- **Two profiles.**
  - `quick`, for development: under 10 min on the N300, the version under test only, compared with the latest nightly result from the same machine. Elsewhere a baseline version named beside it is interleaved, doubling the time.
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

## Remaining work

What is built is described in [the regression suite design](../_designs/REGRESSION_SUITE.md). Open:

1. **Calibration.** The nightly job runs with `--no-alert`, on `origin/main`, for a few nights, to see how often Otava flags change points that the confirmation run refutes, and how often it confirms one with nothing behind it. That sets whether Otava's significance and magnitude thresholds need tightening for the number of series at the target false-alarm rate.
2. **Alerts on.** Drop `--no-alert` from the timer once the calibration nights show the false-alarm rate is acceptable.
3. **Reference.** Every night also measures a pinned release on a few contenders; a reference that moves beyond its band marks the night as disturbed, and nothing is recorded.
4. **Cold start.** One or two paths are also timed as the first operation of a fresh JVM, over many forks, since a library is often used for a single read per process.

The backfill of 1.0.0.Final, 1.1.0.Beta1 to Beta3 and selected main commits is under way through the job's queue.

When these are done, their design goes into the design document and this plan is deleted.

## Settled decisions

- `quick` compares against history on the N300 only; on any other machine it needs a baseline version named beside the one under test.
- Reading a sample of a taxi month to shorten flat and narrow is dropped: it changes their inputs and only halved warmup, since a smaller read makes per-read setup a larger share of each operation and that code needs its own invocations to compile.
- Warmup is time-based per preset, sized on the N300. One operation count per script does not fit contenders whose settle points differ twentyfold; operation counts per contender would need a JMH run per contender in every benchmark.
- 3 forks of 3 measurement iterations per contender, nightly.
- Calibration runs on `origin/main` rather than one pinned commit, to get a feel for the noise over a few nights first.
- `quick` against history compares with the latest nightly value of each contender, shown beside its commit; on any other machine than the N300, `quick` compares against a baseline version named beside the one under test.
- The nightly job pushes straight to the results repository's `main`, as a bot identity with a fine-grained token for that repository only, and alerts by opening an issue on `hardwood-hq/hardwood` with a second token, for issues on that repository only.
