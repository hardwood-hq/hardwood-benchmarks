/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.bloom;
import dev.hardwood.benchmarks.BenchReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
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

/// Bloom-filter point-lookup benchmark on the **real** NYC TLC yellow-taxi data.
/// The published TLC files carry no bloom filters, so [TaxiBloomGenerator] rewrites
/// the real rows through parquet-java with a bloom filter added on `total_amount`
/// (a high-cardinality, unclustered fare column) and a statistics-only twin —
/// where min/max statistics can prune nothing and, with dictionary encoding
/// disabled, only the bloom filter can. It measures two things at once:
///
/// - **what a bloom filter buys** — Hardwood reading the bloom-bearing file
///   ([#hardwoodBloom]) vs the same probe on the statistics-only file
///   ([#hardwoodNoBloom]); and
/// - **Hardwood's bloom vs parquet-java's bloom** — [#hardwoodBloom] vs
///   [#parquetJavaBloom] on the same file (with [#parquetJavaNoBloom] as the
///   matching baseline), a head-to-head of the two read implementations. Their
///   pruning *decisions* are already proven identical by Hardwood's own
///   `BloomFilterParquetJavaOracleTest`; this times them.
///
/// Two probes via `@Param`: `present` (a real `total_amount` from the data) and
/// `absent` (an in-range fare with sub-cent precision that was never charged —
/// bloom drops every row group, the case statistics cannot catch).
///
/// Run with `run-bloom.sh`. Hardwood does not yet *write* bloom filters, so the
/// files are written by parquet-java; this is purely a read-path comparison.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class BloomFilterBenchmark {

    /// Source TLC window and optional row cap. Defaults to the first quarter of
    /// 2025 (several 128 MB row groups); `perf.limit=0` uses every row in the window.
    private static final YearMonth START = YearMonth.parse(System.getProperty("perf.start", "2025-01"));
    private static final YearMonth END = YearMonth.parse(System.getProperty("perf.end", "2025-03"));
    private static final long LIMIT = Long.getLong("perf.limit", 0L);

    private static final Path BLOOM = TaxiBloomGenerator.bloomFile(START, END, LIMIT);
    private static final Path NO_BLOOM = TaxiBloomGenerator.noBloomFile(START, END, LIMIT);

    /// `present` probes a real fare that exists; `absent` probes an in-range fare
    /// that was never charged (returns zero rows).
    @Param({ "present", "absent" })
    private String probe;

    private double amount;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        TaxiBloomGenerator.ensure(START, END, LIMIT);
        amount = probeAmount(probe);
    }

    private static double probeAmount(String probe) throws IOException {
        return "present".equals(probe) ? TaxiBloomGenerator.presentProbe(START, END, LIMIT)
                : TaxiBloomGenerator.absentProbe(START, END, LIMIT);
    }

    /// Correctness gate for one probe: all four contenders must return the same
    /// rows and sum. Run once per probe from [main], outside JMH's per-trial
    /// dispatch — the result is deterministic. Crucially this proves Hardwood and
    /// parquet-java prune the *same* row groups (an over-eager bloom drop would
    /// show up as a missing present row here), and that the absent probe really is
    /// absent (zero rows).
    private static void gate(String probe) throws IOException {
        double amount = probeAmount(probe);
        Scans.Result pj = Scans.parquetJavaEqDouble(BLOOM, amount);
        assertMatches(probe, "parquet-java (no bloom)", Scans.parquetJavaEqDouble(NO_BLOOM, amount), pj);
        assertMatches(probe, "Hardwood (bloom)", Scans.hardwoodEqDouble(BLOOM, amount), pj);
        assertMatches(probe, "Hardwood (no bloom)", Scans.hardwoodEqDouble(NO_BLOOM, amount), pj);
        System.out.printf("Gate passed [%s, amount=%s] — Hardwood agrees with parquet-java on both files (%d rows, sum %.3f).%n",
                probe, amount, pj.count(), pj.sum());
    }

    private static void assertMatches(String probe, String name, Scans.Result actual, Scans.Result ref) {
        if (actual.count() != ref.count()
                || Math.abs(actual.sum() - ref.sum()) > 1e-6 * Math.max(1.0, Math.abs(ref.sum()))) {
            throw new IllegalStateException(String.format(
                    "[%s] %s disagrees with parquet-java (bloom): (%d, %.3f) vs (%d, %.3f)",
                    probe, name, actual.count(), actual.sum(), ref.count(), ref.sum()));
        }
    }

    @Benchmark
    public Scans.Result hardwoodBloom() throws IOException {
        return Scans.hardwoodEqDouble(BLOOM, amount);
    }

    @Benchmark
    public Scans.Result hardwoodNoBloom() throws IOException {
        return Scans.hardwoodEqDouble(NO_BLOOM, amount);
    }

    @Benchmark
    public Scans.Result parquetJavaBloom() throws IOException {
        return Scans.parquetJavaEqDouble(BLOOM, amount);
    }

    @Benchmark
    public Scans.Result parquetJavaNoBloom() throws IOException {
        return Scans.parquetJavaEqDouble(NO_BLOOM, amount);
    }

    public static void main(String[] args) throws Exception {
        String param = System.getProperty("perf.param");
        // Gate-check mode: verify every contender agrees, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            TaxiBloomGenerator.ensure(START, END, LIMIT);
            if (param != null && !param.isBlank()) {
                gate(param);
            }
            else {
                gate("present");
                gate("absent");
            }
            return;
        }
        // Generate (if needed) and log the dataset params before timing. The bloom
        // file is the reported dataset; the no-bloom twin holds the same rows.
        TaxiBloomGenerator.ensure(START, END, LIMIT);
        BenchReport.writeRunParams(BenchReport.totalRows(java.util.List.of(BLOOM)), Files.size(BLOOM), START + ".." + END);
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(BloomFilterBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(2))
                .measurementTime(TimeValue.seconds(2))
                .forks(Integer.getInteger("perf.forks", 1));
        if (param != null && !param.isBlank()) {
            opts.param("probe", param);
        }
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the same window, etc.
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
        BenchReport.appendMsPerOpTsv(results, BloomFilterBenchmark.class);
    }
}
