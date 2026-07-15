/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import dev.hardwood.reader.ColumnReader;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.openjdk.jmh.results.RunResult;

/// Derives and prints throughput (M rows/s, MB/s) from JMH average-time results.
///
/// JMH reports `ms/op` only. For a **full-scan** benchmark every contender
/// processes the same rows and bytes, so throughput is just `rows ÷ time` and
/// `bytes ÷ time` over a shared denominator. (Filtered scans are not full
/// scans — their honest denominators are rows-returned and surviving-page
/// bytes — so they are reported as `ms/op` and not handled here.)
public final class BenchReport {

    private BenchReport() {
    }

    /// JMH `include` regex scoping a run to `benchmark`'s own class. `-Dperf.include`,
    /// when set, narrows the selection to matching methods *within* that class
    /// rather than selecting across classes, so a benchmark's `main()` never runs
    /// another benchmark's methods (every benchmark class is on the shared classpath).
    ///
    /// The user pattern is wrapped in a non-capturing group so a top-level
    /// alternation stays scoped to the class: without it, `--include "a|b"` would
    /// bind as `(class…a)|(b…)` and the second branch would match the same method
    /// in *another* benchmark class on the classpath.
    public static String includePattern(Class<?> benchmark) {
        String scope = benchmark.getName();
        String narrow = System.getProperty("perf.include");
        if (narrow == null || narrow.isBlank()) {
            return scope;
        }
        return scope + "\\..*(?:" + narrow + ").*";
    }

    /// Total record count across the given Parquet files, read from their footers.
    public static long totalRows(List<Path> files) throws IOException {
        Configuration conf = new Configuration();
        long rows = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hadoopPath =
                    new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (ParquetFileReader reader = ParquetFileReader.open(
                    HadoopInputFile.fromPath(hadoopPath, conf))) {
                rows += reader.getRecordCount();
            }
        }
        return rows;
    }

    /// Total on-disk size of the given files.
    public static long totalBytes(List<Path> files) throws IOException {
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.size(file);
        }
        return bytes;
    }

    /// Records the run's dataset parameters — record count, on-disk size, the JVM,
    /// and (for a date-windowed dataset) the window label — so the chart generator
    /// reads them from the run itself rather than from CLI flags or back-computed
    /// throughput. Echoes them to stdout and writes a sidecar TSV next to the
    /// `-Dperf.results` file (`bench-throughput-<X>.tsv` → `bench-meta-<X>.tsv`),
    /// `key\tvalue` per line: `rows`, `bytes`, `java`, `hardwood`, and `window`
    /// when non-blank.
    /// No-op for the meta file when `-Dperf.results` is unset or does not follow the
    /// `bench-throughput-` convention (the stdout line still prints).
    public static void writeRunParams(long rows, long bytes, String window) {
        String java = "Java " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")";
        String hardwood = hardwoodVersion();
        double mb = bytes / 1_000_000.0;
        System.out.printf("Run params: %,d rows, %,.1f MB, %s, Hardwood %s%s%n",
                rows, mb, java, hardwood,
                window == null || window.isBlank() ? "" : " (" + window + ")");

        String resultsPath = System.getProperty("perf.results");
        if (resultsPath == null || resultsPath.isBlank()) {
            return;
        }
        String metaPath = resultsPath.replace("bench-throughput-", "bench-meta-");
        if (metaPath.equals(resultsPath)) {
            return; // not the conventional results path — don't risk clobbering it
        }
        StringBuilder sb = new StringBuilder();
        sb.append("rows\t").append(rows).append('\n');
        sb.append("bytes\t").append(bytes).append('\n');
        sb.append("java\t").append(java).append('\n');
        sb.append("hardwood\t").append(hardwood).append('\n');
        if (window != null && !window.isBlank()) {
            sb.append("window\t").append(window).append('\n');
        }
        try {
            Files.writeString(Path.of(metaPath), sb.toString());
        } catch (IOException e) {
            System.err.println("Could not write run params to " + metaPath + ": " + e);
        }
    }

    /// The resolved `hardwood-core` build as `<version> (<git-sha>)`, read from the
    /// jar's `META-INF/MANIFEST.MF` — `Implementation-Version` and
    /// `Implementation-Build`, the latter stamped by the Hardwood build from
    /// `git rev-parse`. This is the authoritative record of which commit a run
    /// measured, since the dependency is a floating `-SNAPSHOT`. Returns `unknown`
    /// when core is on the classpath as loose classes rather than a jar.
    private static String hardwoodVersion() {
        URL classUrl = ColumnReader.class.getResource(ColumnReader.class.getSimpleName() + ".class");
        if (classUrl == null || !"jar".equals(classUrl.getProtocol())) {
            return "unknown";
        }
        String jarRef = classUrl.toString();
        String manifestUrl = jarRef.substring(0, jarRef.lastIndexOf('!') + 1) + "/META-INF/MANIFEST.MF";
        try (InputStream in = URI.create(manifestUrl).toURL().openStream()) {
            Attributes attrs = new Manifest(in).getMainAttributes();
            String version = attrs.getValue("Implementation-Version");
            String build = attrs.getValue("Implementation-Build");
            if (version == null && build == null) {
                return "unknown";
            }
            return version + " (" + build + ")";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /// Prints a throughput table for the full-scan results of `benchmark`, which
    /// all share `rows`/`bytes`. Results from other benchmark classes (e.g. when
    /// a cross-class `-Dperf.include` regex pulls them in) are skipped — their
    /// denominator would differ.
    public static void printFullScanThroughput(Collection<RunResult> results,
            Class<?> benchmark, long rows, long bytes) {
        String prefix = benchmark.getName() + ".";
        double mb = bytes / 1_000_000.0;
        System.out.println();
        System.out.println("=== Throughput (derived: rows / avg time, bytes / avg time) ===");
        System.out.printf("    dataset: %,d rows, %,.1f MB%n", rows, mb);
        System.out.printf("    %-44s %10s %12s %10s%n", "Benchmark", "ms/op", "M rows/s", "MB/s");
        for (RunResult result : results) {
            String fullName = result.getParams().getBenchmark();
            if (!fullName.startsWith(prefix)) {
                continue;
            }
            double ms = result.getPrimaryResult().getScore();
            double seconds = ms / 1000.0;
            System.out.printf("    %-44s %10.1f %12.2f %10.0f%n",
                    fullName.substring(prefix.length()), ms, rows / seconds / 1_000_000.0, mb / seconds);
        }
    }

    /// Append the same per-contender throughput to the machine-readable TSV at
    /// `-Dperf.results` (one row per contender), tagged with the `-Dperf.pass`
    /// (`unpinned` / `pinned`). No-op when `-Dperf.results` is unset. The two
    /// passes append to the same file so a chart tool can pivot contender ×
    /// {default, 1-core}. Columns: `pass benchmark contender ms_per_op
    /// m_rows_per_s mb_per_s`.
    public static void appendThroughputTsv(Collection<RunResult> results,
            Class<?> benchmark, long rows, long bytes) {
        String resultsPath = System.getProperty("perf.results");
        if (resultsPath == null || resultsPath.isBlank()) {
            return;
        }
        String pass = System.getProperty("perf.pass", "default");
        String prefix = benchmark.getName() + ".";
        double mb = bytes / 1_000_000.0;
        StringBuilder sb = new StringBuilder();
        for (RunResult result : results) {
            String fullName = result.getParams().getBenchmark();
            if (!fullName.startsWith(prefix)) {
                continue;
            }
            double ms = result.getPrimaryResult().getScore();
            double seconds = ms / 1000.0;
            sb.append(pass).append('\t')
                    .append(benchmark.getSimpleName()).append('\t')
                    .append(fullName.substring(prefix.length())).append('\t')
                    .append(String.format("%.3f", ms)).append('\t')
                    .append(String.format("%.3f", rows / seconds / 1_000_000.0)).append('\t')
                    .append(String.format("%.1f", mb / seconds)).append('\n');
        }
        try {
            Files.writeString(Path.of(resultsPath), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not append results to " + resultsPath + ": " + e);
        }
    }

    /// Append per-contender **ms/op** to the TSV at `-Dperf.results`, tagged with
    /// the `-Dperf.pass`. The throughput columns are written as `-`: a fixed
    /// rows/bytes denominator is selectivity-dependent and would mislead, so the
    /// runner renders a lower-is-better ms/op chart instead. JMH params (e.g.
    /// `selectivity`) are folded into the contender label, one row per
    /// (method, params) combination. No-op when `-Dperf.results` is unset.
    public static void appendMsPerOpTsv(Collection<RunResult> results, Class<?> benchmark) {
        String resultsPath = System.getProperty("perf.results");
        if (resultsPath == null || resultsPath.isBlank()) {
            return;
        }
        String pass = System.getProperty("perf.pass", "default");
        String prefix = benchmark.getName() + ".";
        StringBuilder sb = new StringBuilder();
        for (RunResult result : results) {
            String fullName = result.getParams().getBenchmark();
            if (!fullName.startsWith(prefix)) {
                continue;
            }
            StringBuilder contender = new StringBuilder(fullName.substring(prefix.length()));
            for (String key : result.getParams().getParamsKeys()) {
                contender.append('[').append(result.getParams().getParam(key)).append(']');
            }
            double ms = result.getPrimaryResult().getScore();
            sb.append(pass).append('\t')
                    .append(benchmark.getSimpleName()).append('\t')
                    .append(contender).append('\t')
                    .append(String.format("%.3f", ms)).append('\t')
                    .append('-').append('\t')
                    .append('-').append('\n');
        }
        try {
            Files.writeString(Path.of(resultsPath), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not append results to " + resultsPath + ": " + e);
        }
    }
}
