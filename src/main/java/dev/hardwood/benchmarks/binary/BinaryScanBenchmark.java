/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.binary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.EncodingStats;
import org.apache.parquet.hadoop.ParquetReader;
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

import dev.hardwood.InputFile;
import dev.hardwood.Validity;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.BinaryWindows;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Plain binary scan: a full read of two string columns stored `PLAIN`, without a dictionary
/// ([TextFileGenerator]): `text`, required, and `note`, optional.
///
/// Witnesses how a variable-length column's bytes travel from the page into the batch
/// (hardwood-hq/hardwood#1287): `hardwoodColumnViews` reads every value through the column
/// reader's byte views, `hardwoodRowStrings` every value as a `String` through the row reader.
///
/// The benchmark is regression-only: no publication cites it, and the dataset has one size,
/// so runs of any two Hardwood versions read the same file. It needs hardwood-core
/// 1.1.0.Beta2 or later, whose column reader exposes byte views.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class BinaryScanBenchmark {

    private static final long ROWS = 5_000_000L;
    private static final Path FILE = Path.of("target/binary_scan_benchmark_" + ROWS + ".parquet");
    private static final String[] COLUMNS = { "text", "note" };

    /// Non-null values read, their summed lengths, and the sum of their first characters.
    public record Result(long values, long length, long firstChars) {}

    @Setup(Level.Trial)
    public void setup() throws IOException {
        TextFileGenerator.ensure(FILE, ROWS);
    }

    /// Column readers over both columns, reading each value through its byte view.
    @Benchmark
    public Result hardwoodColumnViews() throws IOException {
        long values = 0;
        long length = 0;
        long firstChars = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FILE));
             ColumnReaders columns = reader.buildColumnReaders(ColumnProjection.columns(COLUMNS)).build()) {
            while (columns.nextBatch()) {
                for (String name : COLUMNS) {
                    ColumnReader column = columns.getColumnReader(name);
                    BinaryWindows binary = BinaryWindows.of(column);
                    Validity validity = column.getLeafValidity();
                    boolean nulls = validity.hasNulls();
                    for (int i = 0; i < column.getRecordCount(); i++) {
                        if (nulls && validity.isNull(i)) {
                            continue;
                        }
                        int start = binary.start(i);
                        values++;
                        length += binary.end(i) - start;
                        firstChars += binary.buffer(i)[start];
                    }
                }
            }
        }
        return new Result(values, length, firstChars);
    }

    /// Row reader over both columns, reading each value as a `String`.
    @Benchmark
    public Result hardwoodRowStrings() throws IOException {
        long values = 0;
        long length = 0;
        long firstChars = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rows = reader.buildRowReader().projection(ColumnProjection.columns(COLUMNS)).build()) {
            while (rows.hasNext()) {
                rows.next();
                for (int c = 0; c < COLUMNS.length; c++) {
                    if (rows.isNull(c)) {
                        continue;
                    }
                    String value = rows.getString(c);
                    values++;
                    length += value.length();
                    firstChars += value.charAt(0);
                }
            }
        }
        return new Result(values, length, firstChars);
    }

    /// Correctness gate, outside JMH: every data page of both columns must be `PLAIN` with no
    /// dictionary, so the benchmark measures what it claims to, and each contender must agree
    /// with a parquet-java scan.
    private static void gate() throws IOException {
        Configuration conf = new Configuration();
        HadoopInputFile input = HadoopInputFile.fromPath(
                new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString()), conf);
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(input)) {
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                for (ColumnChunkMetaData column : block.getColumns()) {
                    EncodingStats stats = column.getEncodingStats();
                    if (stats == null || stats.hasDictionaryPages()
                            || !stats.getDataEncodings().equals(java.util.Set.of(Encoding.PLAIN))) {
                        throw new IllegalStateException(
                                "Column " + column.getPath() + " has pages that are not PLAIN: " + stats);
                    }
                }
            }
        }

        long values = 0;
        long length = 0;
        long firstChars = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(input)
                .withConf(conf).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                for (String name : COLUMNS) {
                    Object value = record.get(name);
                    if (value != null) {
                        String s = value.toString();
                        values++;
                        length += s.length();
                        firstChars += s.charAt(0);
                    }
                }
            }
        }
        Result ref = new Result(values, length, firstChars);
        BinaryScanBenchmark benchmark = new BinaryScanBenchmark();
        check("hardwoodColumnViews", benchmark.hardwoodColumnViews(), ref);
        check("hardwoodRowStrings", benchmark.hardwoodRowStrings(), ref);
        System.out.printf("Gate passed: every page is PLAIN, and every contender agrees with parquet-java "
                + "(%d values, %d bytes).%n", ref.values(), ref.length());
    }

    private static void check(String contender, Result actual, Result ref) {
        if (!actual.equals(ref)) {
            throw new IllegalStateException(contender + " disagrees with parquet-java: " + actual + " vs " + ref);
        }
    }

    public static void main(String[] args) throws Exception {
        TextFileGenerator.ensure(FILE, ROWS);
        System.out.printf("Dataset: %,d rows (%s)%n", ROWS, FILE);
        // Gate-check mode: verify Hardwood agrees with parquet-java, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            gate();
            return;
        }
        BenchReport.writeRunParams(ROWS, Files.size(FILE), "columns", String.join(",", COLUMNS));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(BinaryScanBenchmark.class))
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
        BenchReport.appendMsPerOpTsv(results, BinaryScanBenchmark.class);
    }
}
