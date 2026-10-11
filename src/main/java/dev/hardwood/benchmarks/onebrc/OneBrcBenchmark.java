/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.EncodingStats;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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

/// The One Billion Row Challenge over Parquet: min, mean and max `measurement` per `station`.
///
/// The other full scans fold what they read into a checksum. Here the consumer does work per
/// row, a hash-table update, so the benchmark shows what happens when consumption and not
/// decoding sets the pace: one consumer thread capping a parallel decode, or batches that
/// leave the cache before they are consumed.
///
/// Three contenders, all Hardwood:
/// - `hardwoodColumnIds`: the column reader on one consumer thread, aggregating by dictionary
///   entry id, each entry resolved to a table slot once per dictionary
/// - `hardwoodColumnSplits`: the same, over contiguous row-group byte ranges
///   ([RowGroupPredicate#byteRange]), one reader and one consumer per available processor,
///   their tables merged at the end
/// - `hardwoodRowReader`: the row reader, one `getString` and `getDouble` per row into a
///   `HashMap`
///
/// The benchmark is regression-only, with no published chart. The column contenders need
/// `ColumnReader#getDictionaryIds()`, so it builds against 1.1.0.Beta2 and later.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class OneBrcBenchmark {

    private static final long ROWS = Long.getLong("perf.rows", 100_000_000L);
    private static final Path FILE = Path.of("target/onebrc_benchmark_" + ROWS + ".parquet");

    @Setup(Level.Trial)
    public void setup() throws IOException {
        MeasurementsGenerator.ensure(FILE, ROWS);
    }

    @Benchmark
    public StationTable hardwoodColumnIds() throws IOException {
        return OneBrcScans.columnIds(FILE);
    }

    @Benchmark
    public StationTable hardwoodColumnSplits() throws IOException, InterruptedException, ExecutionException {
        return OneBrcScans.columnSplits(FILE, Runtime.getRuntime().availableProcessors());
    }

    @Benchmark
    public StationTable hardwoodRowReader() throws IOException {
        return OneBrcScans.rowReader(FILE);
    }

    /// Correctness gate, outside JMH: every data page of the file must be dictionary-encoded,
    /// so the column contenders measure what they claim to, and every contender must print
    /// the aggregate the generator recorded while drawing the values.
    private static void gate() throws Exception {
        HadoopInputFile input = HadoopInputFile.fromPath(
                new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString()), new Configuration());
        int rowGroups;
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                org.apache.parquet.hadoop.ParquetFileReader.open(input)) {
            rowGroups = reader.getFooter().getBlocks().size();
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                for (ColumnChunkMetaData column : block.getColumns()) {
                    EncodingStats stats = column.getEncodingStats();
                    if (stats == null || !stats.hasDictionaryPages() || stats.hasNonDictionaryEncodedPages()) {
                        throw new IllegalStateException(
                                "Column " + column.getPath() + " has pages that are not dictionary-encoded: " + stats);
                    }
                }
            }
        }

        String expected = Files.readString(MeasurementsGenerator.expected(FILE)).strip();
        OneBrcBenchmark benchmark = new OneBrcBenchmark();
        Map<String, StationTable> results = Map.of(
                "hardwoodColumnIds", benchmark.hardwoodColumnIds(),
                "hardwoodColumnSplits", benchmark.hardwoodColumnSplits(),
                "hardwoodRowReader", benchmark.hardwoodRowReader());
        for (Map.Entry<String, StationTable> result : results.entrySet()) {
            if (result.getValue().rows() != ROWS) {
                throw new IllegalStateException(result.getKey() + " aggregated " + result.getValue().rows()
                        + " rows, the file has " + ROWS);
            }
            if (!result.getValue().format().equals(expected)) {
                throw new IllegalStateException(result.getKey() + " disagrees with the generator's aggregate in "
                        + MeasurementsGenerator.expected(FILE));
            }
        }
        System.out.printf("Gate passed: every page is dictionary-encoded (%d row groups), and every contender "
                + "matches the generator's aggregate (%d rows).%n", rowGroups, ROWS);
    }

    public static void main(String[] args) throws Exception {
        MeasurementsGenerator.ensure(FILE, ROWS);
        System.out.printf("Dataset: %,d rows (%s)%n", ROWS, FILE);
        // Gate-check mode: verify every contender against the expected aggregate, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            gate();
            return;
        }
        long bytes = Files.size(FILE);
        BenchReport.writeRunParams(ROWS, bytes, "");
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(OneBrcBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
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
        BenchReport.printFullScanThroughput(results, OneBrcBenchmark.class, ROWS, bytes);
        BenchReport.appendThroughputTsv(results, OneBrcBenchmark.class, ROWS, bytes);
    }
}
