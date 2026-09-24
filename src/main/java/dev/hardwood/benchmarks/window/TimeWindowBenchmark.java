/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.filter.EventFileGenerator;
import dev.hardwood.benchmarks.filter.Scans;
import dev.hardwood.reader.FilterPredicate;

/// Recent-time-window benchmark: `SELECT amount WHERE event_time >= T` over a
/// time-sorted event log split into many row groups.
///
/// The filter column is not projected. Every row group lying wholly inside the
/// window is proven fully matching by its statistics, so a reader that skips the
/// filter column in proven row groups reads `event_time` only for the one row
/// group straddling `T`. `@Param window` sweeps the window width (the most recent
/// 5 / 25 / 75 % of the time range); the unfiltered reads of `amount` are the
/// controls.
///
/// Every contender is a Hardwood reader. The benchmark is regression-only: no
/// publication cites it, and its dataset has one size, so runs of any two Hardwood
/// versions read the same file. Run with `run-window.sh`, which executes this on
/// all cores and, on Linux, again under `taskset -c 0`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class TimeWindowBenchmark {

    // A regression-only benchmark has one size, so runs of any two versions read the
    // same file. Both settings are in the file name, so changing either here
    // regenerates rather than reusing a stale file (EventFileGenerator.ensure only
    // skips when the path exists).
    private static final long ROWS = 10_000_000L;
    private static final int ROW_GROUP_MB = 16;
    private static final Path FILE = Path.of("target/window_benchmark_" + ROWS + "_" + ROW_GROUP_MB + "mb.parquet");

    private static final String[] WINDOWS = { "last5pct", "last25pct", "last75pct" };

    /// The most recent fraction of the time range the query selects.
    @Param({ "last5pct", "last25pct", "last75pct" })
    private String window;

    private FilterPredicate filter;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        ensureFile();
        filter = WindowScans.predicate(WindowScans.windowStart(window, ROWS));
    }

    private static void ensureFile() throws IOException {
        EventFileGenerator.ensure(FILE, ROWS, ROW_GROUP_MB * 1024L * 1024L);
    }

    /// Column reader on `amount`, filtered on `event_time`.
    @Benchmark
    public Scans.Result hardwoodColumnFiltered() throws IOException {
        return Scans.hardwoodFiltered(FILE, filter);
    }

    /// Row reader projecting `amount`, filtered on `event_time`.
    @Benchmark
    public Scans.Result hardwoodRowFiltered() throws IOException {
        return Scans.hardwoodRowReaderFiltered(FILE, filter);
    }

    /// Control: column reader on `amount`, no predicate. Independent of `window`.
    @Benchmark
    public Scans.Result hardwoodColumnNoFilter() throws IOException {
        return Scans.hardwoodUnfiltered(FILE);
    }

    /// Control: row reader projecting `amount`, no predicate. Independent of `window`.
    @Benchmark
    public Scans.Result hardwoodRowNoFilter() throws IOException {
        return Scans.hardwoodRowReaderUnfiltered(FILE);
    }

    /// Correctness gate for one window: both filtered Hardwood readers must agree
    /// with parquet-java's exact filtered scan, and that scan must select exactly
    /// the rows the window's bound implies. Run from [#main], outside JMH.
    private static void gate(String window) throws IOException {
        long from = WindowScans.windowStart(window, ROWS);
        Scans.Result ref = WindowScans.parquetJavaWindow(FILE, from);
        if (ref.count() != ROWS - from) {
            throw new IllegalStateException(String.format(
                    "[%s] parquet-java selected %d rows, expected %d (event_time >= %d of %d rows)",
                    window, ref.count(), ROWS - from, from, ROWS));
        }
        FilterPredicate filter = WindowScans.predicate(from);
        assertMatches(window, "Hardwood column reader (filtered)", Scans.hardwoodFiltered(FILE, filter), ref);
        assertMatches(window, "Hardwood row reader (filtered)", Scans.hardwoodRowReaderFiltered(FILE, filter), ref);
        System.out.printf("Gate passed [%s] — both filtered Hardwood readers agree with parquet-java "
                + "(event_time >= %d: %d rows, sum %.3f).%n", window, from, ref.count(), ref.sum());
    }

    /// Correctness gate for the controls: both unfiltered Hardwood readers must see
    /// every row and agree with parquet-java's unfiltered scan of `amount`.
    private static void gateControls() throws IOException {
        Scans.Result ref = Scans.parquetJavaUnfiltered(FILE);
        if (ref.count() != ROWS) {
            throw new IllegalStateException(String.format(
                    "parquet-java read %d rows, expected %d", ref.count(), ROWS));
        }
        assertMatches("no filter", "Hardwood column reader (unfiltered)", Scans.hardwoodUnfiltered(FILE), ref);
        assertMatches("no filter", "Hardwood row reader (unfiltered)", Scans.hardwoodRowReaderUnfiltered(FILE), ref);
        System.out.printf("Gate passed [no filter] — both unfiltered Hardwood readers agree with parquet-java "
                + "(%d rows, sum %.3f).%n", ref.count(), ref.sum());
    }

    private static void assertMatches(String window, String name, Scans.Result actual, Scans.Result ref) {
        if (actual.count() != ref.count()
                || Math.abs(actual.sum() - ref.sum()) > 1e-6 * Math.max(1.0, Math.abs(ref.sum()))) {
            throw new IllegalStateException(String.format(
                    "[%s] %s disagrees with parquet-java: (%d, %.3f) vs (%d, %.3f)",
                    window, name, actual.count(), actual.sum(), ref.count(), ref.sum()));
        }
    }

    public static void main(String[] args) throws Exception {
        String param = System.getProperty("perf.param");
        ensureFile();
        int rowGroups = WindowScans.rowGroupCount(FILE);
        System.out.printf("Dataset: %,d rows, %d MB row groups -> %d row groups (%s)%n",
                ROWS, ROW_GROUP_MB, rowGroups, FILE);
        // Gate-check mode: verify Hardwood agrees with parquet-java, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            String[] windows = param != null && !param.isBlank() ? new String[] { param } : WINDOWS;
            for (String w : windows) {
                gate(w);
            }
            gateControls();
            return;
        }
        BenchReport.writeRunParams(ROWS, Files.size(FILE), "rowGroupMb", Integer.toString(ROW_GROUP_MB),
                "rowGroups", Integer.toString(rowGroups));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(TimeWindowBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
        if (param != null && !param.isBlank()) {
            WindowScans.windowStart(param, ROWS); // fail early on a malformed --window
            opts.param("window", param);
        }
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the same settings.
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
        Collection<RunResult> results = new Runner(opts.build()).run();
        BenchReport.appendMsPerOpTsv(results, TimeWindowBenchmark.class);
    }
}
