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
import java.util.List;
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

/// Bloom-filter point-lookup benchmark: an equality probe on a **unique,
/// unclustered 64-bit key**, the workload bloom filters exist for. [LookupFileGenerator]
/// writes the corpus twice — once with a bloom filter on `key`, once without — so the
/// same probe against the two files isolates exactly what the filter buys. Because
/// every key is distinct and pseudorandomly ordered, statistics cannot prune (every
/// row group's range covers any probe) and the dictionary cannot prune (the column
/// falls back to plain encoding on its own), leaving the bloom filter as the sole
/// pruner. It measures two things at once:
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
/// Two probes via `@Param`: `present` (a key that was written — unique, so the bloom
/// keeps the one row group holding it and drops the rest) and `absent` (a key that
/// was never written but is in range for every row group — the bloom drops them all,
/// the case statistics cannot catch).
///
/// Run with `run-bloom.sh`. Hardwood does not yet *write* bloom filters, so the files
/// are written by parquet-java; this is purely a read-path comparison.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class BloomFilterBenchmark {

    /// Corpus size (`perf.rows`). The default spans ~10 row groups at Parquet's
    /// default 128 MB row-group size, so the present probe keeps one and drops nine.
    private static final long ROWS = Long.getLong("perf.rows", LookupFileGenerator.DEFAULT_ROWS);

    private static final Path BLOOM = LookupFileGenerator.bloomFile(ROWS);
    private static final Path NO_BLOOM = LookupFileGenerator.noBloomFile(ROWS);

    /// `present` probes a key that exists (exactly one row); `absent` probes a key
    /// that was never written (zero rows).
    @Param({ "present", "absent" })
    private String probe;

    private long key;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        LookupFileGenerator.ensure(ROWS);
        key = probeKey(probe);
    }

    private static long probeKey(String probe) {
        return "present".equals(probe) ? LookupFileGenerator.presentProbe(ROWS)
                : LookupFileGenerator.absentProbe(ROWS);
    }

    /// Correctness gate for one probe: all four contenders must return the same rows
    /// and sum. Run once per probe from [main], outside JMH's per-trial dispatch —
    /// the result is deterministic. Crucially this proves Hardwood and parquet-java
    /// prune the *same* row groups (an over-eager bloom drop would show up as a
    /// missing present row here), that the present probe matches exactly one row, and
    /// that the absent probe really is absent (zero rows).
    private static void gate(String probe) throws IOException {
        long key = probeKey(probe);
        Scans.Result pj = Scans.parquetJavaEqLong(BLOOM, key);
        assertMatches(probe, "parquet-java (no bloom)", Scans.parquetJavaEqLong(NO_BLOOM, key), pj);
        assertMatches(probe, "Hardwood (bloom)", Scans.hardwoodEqLong(BLOOM, key), pj);
        assertMatches(probe, "Hardwood (no bloom)", Scans.hardwoodEqLong(NO_BLOOM, key), pj);
        long expected = "present".equals(probe) ? 1 : 0;
        if (pj.count() != expected) {
            throw new IllegalStateException(String.format(
                    "[%s] expected %d matching row(s) for a unique key, got %d", probe, expected, pj.count()));
        }
        System.out.printf("Gate passed [%s, key=%d] — Hardwood agrees with parquet-java on both files (%d rows, sum %.3f).%n",
                probe, key, pj.count(), pj.sum());
    }

    private static void assertMatches(String probe, String name, Scans.Result actual, Scans.Result ref) {
        if (actual.count() != ref.count()
                || Math.abs(actual.sum() - ref.sum()) > 1e-6 * Math.max(1.0, Math.abs(ref.sum()))) {
            throw new IllegalStateException(String.format(
                    "[%s] %s disagrees with parquet-java (bloom): (%d, %.3f) vs (%d, %.3f)",
                    probe, name, actual.count(), actual.sum(), ref.count(), ref.sum()));
        }
    }

    /// Proves the layout preconditions the benchmark rests on, rather than assuming
    /// them: the corpus spans several row groups (so there is something to prune),
    /// and the unique key column fell back to plain encoding by itself (so no
    /// dictionary filter can prune, and the bloom filter is the only pruner).
    private static void gateLayout() throws IOException {
        List<Long> rowCounts = Scans.rowGroupRowCounts(BLOOM);
        List<String> encodings = Scans.probeColumnEncodings(BLOOM);
        System.out.printf("Layout: %d row groups, rows/group=%s%n", rowCounts.size(), rowCounts);
        System.out.printf("Probe-column encodings per row group: %s%n", encodings);
        if (rowCounts.size() < 2) {
            throw new IllegalStateException(
                    "expected several row groups so pruning has something to drop, got " + rowCounts.size()
                            + " — raise perf.rows");
        }
        for (String e : encodings) {
            if (e.contains("DICTIONARY")) {
                throw new IllegalStateException(
                        "probe column is dictionary-encoded (" + e + "); the dictionary filter could prune "
                                + "instead of the bloom filter, which the benchmark must isolate");
            }
        }
    }

    @Benchmark
    public Scans.Result hardwoodBloom() throws IOException {
        return Scans.hardwoodEqLong(BLOOM, key);
    }

    @Benchmark
    public Scans.Result hardwoodNoBloom() throws IOException {
        return Scans.hardwoodEqLong(NO_BLOOM, key);
    }

    @Benchmark
    public Scans.Result parquetJavaBloom() throws IOException {
        return Scans.parquetJavaEqLong(BLOOM, key);
    }

    @Benchmark
    public Scans.Result parquetJavaNoBloom() throws IOException {
        return Scans.parquetJavaEqLong(NO_BLOOM, key);
    }

    public static void main(String[] args) throws Exception {
        String param = System.getProperty("perf.param");
        // Gate-check mode: verify every contender agrees, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            LookupFileGenerator.ensure(ROWS);
            gateLayout();
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
        LookupFileGenerator.ensure(ROWS);
        BenchReport.writeRunParams(BenchReport.totalRows(java.util.List.of(BLOOM)), Files.size(BLOOM),
                "unique 64-bit key");
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
        BenchReport.appendMsPerOpTsv(results, BloomFilterBenchmark.class);
    }
}
