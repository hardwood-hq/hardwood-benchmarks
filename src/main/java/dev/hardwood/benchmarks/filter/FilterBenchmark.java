/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.filter;
import dev.hardwood.benchmarks.BenchReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/// Filtered-scan benchmark: Hardwood's filtered column reader vs parquet-java's
/// low-level column API over column-index-filtered row groups, both reading the
/// same generated, time-clustered file and pushing down a range predicate.
///
/// Two selectivities via `@Param`: `selective` (~5 % pass — the push-down win)
/// and `matchAll` (every row passes — the filter-evaluation overhead floor).
///
/// Run with `run-filter.sh`, which executes this out-of-the-box (all cores) and,
/// on Linux, again under `taskset -c 0` for the engine-for-engine per-core number.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FilterBenchmark {

    private static final long ROWS = Long.getLong("perf.rows", 50_000_000L);
    // Row count is in the filename so a different --rows regenerates rather than
    // silently reusing a stale file (EventFileGenerator.ensure only skips when the
    // path exists). The threshold below is derived from ROWS, so a mismatched file
    // would also break the selectivities.
    private static final Path FILE = Path.of("target/filter_benchmark_" + ROWS + ".parquet");

    /// `selective` keeps ~5 %; `matchAll` keeps every row (event_time is in
    /// `[0, ROWS)`, so `< ROWS` passes all) to measure the overhead floor.
    @Param({ "selective", "matchAll" })
    private String selectivity;

    private long threshold;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        EventFileGenerator.ensure(FILE, ROWS);
        threshold = "selective".equals(selectivity) ? ROWS / 20 : ROWS;
    }

    /// Correctness gate for one selectivity: Hardwood's filtered reader must agree
    /// with parquet-java. Run once per selectivity from [main], outside JMH's
    /// per-trial dispatch — the result is deterministic, so one check per
    /// selectivity covers every fork (running it per trial re-checked
    /// `forks x params x methods` times for no added signal).
    private static void gate(String selectivity) throws IOException {
        long threshold = "selective".equals(selectivity) ? ROWS / 20 : ROWS;
        Scans.Result pj = Scans.parquetJavaFiltered(FILE, threshold);
        Scans.Result hw = Scans.hardwoodFiltered(FILE, threshold);
        Scans.Result avro = Scans.avroParquetFiltered(FILE, threshold);
        assertMatches(selectivity, "Hardwood (filtered column reader)", hw, pj);
        assertMatches(selectivity, "AvroParquetReader.withFilter", avro, pj);
        System.out.printf("Gate passed [%s] — Hardwood and AvroParquetReader agree with parquet-java (%d rows, sum %.3f).%n",
                selectivity, hw.count(), hw.sum());
    }

    private static void assertMatches(String selectivity, String name, Scans.Result actual, Scans.Result ref) {
        if (actual.count() != ref.count()
                || Math.abs(actual.sum() - ref.sum()) > 1e-6 * Math.max(1.0, Math.abs(ref.sum()))) {
            throw new IllegalStateException(String.format(
                    "[%s] %s disagrees with parquet-java: (%d, %.3f) vs (%d, %.3f)",
                    selectivity, name, actual.count(), actual.sum(), ref.count(), ref.sum()));
        }
    }

    @Benchmark
    public Scans.Result hardwoodDefault() throws IOException {
        return Scans.hardwoodFiltered(FILE, threshold);
    }

    @Benchmark
    public Scans.Result parquetJava() throws IOException {
        return Scans.parquetJavaFiltered(FILE, threshold);
    }

    /// Ad-hoc reference (not published): parquet-java's record path with its
    /// built-in `withFilter` exact filtering. See [Scans#avroParquetFiltered].
    @Benchmark
    public Scans.Result avroParquetWithFilter() throws IOException {
        return Scans.avroParquetFiltered(FILE, threshold);
    }

    public static void main(String[] args) throws Exception {
        String param = System.getProperty("perf.param");
        // Gate-check mode: verify Hardwood agrees with parquet-java, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            EventFileGenerator.ensure(FILE, ROWS);
            if (param != null && !param.isBlank()) {
                gate(param);
            }
            else {
                gate("selective");
                gate("matchAll");
            }
            return;
        }
        // Generate (if needed) and log the dataset params before timing.
        EventFileGenerator.ensure(FILE, ROWS);
        BenchReport.writeRunParams(ROWS, Files.size(FILE), "");
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(FilterBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(2))
                .measurementTime(TimeValue.seconds(2))
                .forks(Integer.getInteger("perf.forks", 1));
        if (param != null && !param.isBlank()) {
            opts.param("selectivity", param);
        }
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the same row count, etc.
        System.getProperties().forEach((k, v) -> {
            if (((String) k).startsWith("perf.")) {
                opts.jvmArgsAppend("-D" + k + "=" + v);
            }
        });
        // Optional profilers, e.g. -Dperf.prof=gc,stack
        String prof = System.getProperty("perf.prof");
        if (prof != null && !prof.isBlank()) {
            for (String raw : prof.split(",")) {
                String p = raw.trim();
                int colon = p.indexOf(':');
                if (colon > 0) {
                    opts.addProfiler(p.substring(0, colon), p.substring(colon + 1));
                }
                else {
                    opts.addProfiler(p);
                }
            }
        }
        java.util.Collection<org.openjdk.jmh.results.RunResult> results = new Runner(opts.build()).run();
        BenchReport.appendMsPerOpTsv(results, FilterBenchmark.class);
    }
}
