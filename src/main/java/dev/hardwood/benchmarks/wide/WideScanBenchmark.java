/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.wide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.internal.column.columnindex.OffsetIndex;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.MessageType;
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

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.benchmarks.BenchContext;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Wide-schema scan (regression-only): every column of a file of 10,000 `FLOAT` columns and
/// 2,190 rows in one row group, one page per column chunk ([WideFileGenerator]), folded into a
/// checksum of the values' bits.
///
/// A step of a read takes one batch of each column, so the work a read does per batch and
/// column, and anything it repeats over all columns per batch taken, is multiplied by the
/// column count: a cost linear per take is quadratic in the columns per step. From
/// 1.1.0.Beta1 the default batch size leaves about 150 rows per batch at this width, so a read
/// takes 14 batches of every column; 1.0.0.Final's batch floor of 16,384 rows reads the file in
/// one batch.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WideScanBenchmark {

    private static final Path FILE = Path.of("target/wide_scan_" + WideFileGenerator.COLUMNS + "x"
            + WideFileGenerator.ROWS + ".parquet");

    private InputFile inputFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        WideFileGenerator.ensure(FILE);
        inputFile = InputFile.of(FILE);
    }

    /// Every column through the column readers, at their default batch size.
    @Benchmark
    public long hardwoodColumnar() throws IOException {
        long checksum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.all()).build()) {
            int columns = WideFileGenerator.COLUMNS;
            while (cols.nextBatch()) {
                int n = cols.getRecordCount();
                for (int c = 0; c < columns; c++) {
                    float[] values = cols.getColumnReader(c).getFloats();
                    for (int i = 0; i < n; i++) {
                        checksum += Float.floatToRawIntBits(values[i]);
                    }
                }
            }
        }
        return checksum;
    }

    /// Every column of every row through the row reader, by index.
    @Benchmark
    public long hardwoodRowReaderIndexed() throws IOException {
        long checksum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             RowReader rows = reader.rowReader()) {
            int columns = WideFileGenerator.COLUMNS;
            while (rows.hasNext()) {
                rows.next();
                for (int c = 0; c < columns; c++) {
                    checksum += Float.floatToRawIntBits(rows.getFloat(c));
                }
            }
        }
        return checksum;
    }

    /// Correctness gate: both Hardwood readers must fold to parquet-java's checksum.
    private void runGate() throws IOException {
        checkFixture();
        System.out.println("Correctness gate — folding every contender, comparing to parquet-java:");
        long ref = parquetJava();
        check("Hardwood column readers", hardwoodColumnar(), ref);
        check("Hardwood row reader (indexed)", hardwoodRowReaderIndexed(), ref);
        System.out.printf("Gate passed — all contenders agree (checksum %d).%n", ref);
    }

    private static void check(String name, long actual, long ref) {
        if (actual != ref) {
            throw new IllegalStateException("Checksum mismatch: " + name + " " + actual + " vs parquet-java " + ref);
        }
        System.out.printf("  OK  %s%n", name);
    }

    /// Checks the fixture has the shape the benchmark describes, rather than assuming parquet-java
    /// wrote it so: one row group, every chunk `BYTE_STREAM_SPLIT` without a dictionary, in one
    /// page.
    private static void checkFixture() throws IOException {
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString());
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, new Configuration()))) {
            List<BlockMetaData> blocks = reader.getFooter().getBlocks();
            if (blocks.size() != 1) {
                throw new IllegalStateException("Expected one row group, found " + blocks.size());
            }
            for (ColumnChunkMetaData chunk : blocks.get(0).getColumns()) {
                OffsetIndex pages = reader.readOffsetIndex(chunk);
                if (chunk.hasDictionaryPage() || !chunk.getEncodings().contains(Encoding.BYTE_STREAM_SPLIT)
                        || pages == null || pages.getPageCount() != 1) {
                    throw new IllegalStateException("Column " + chunk.getPath() + " is not one BYTE_STREAM_SPLIT page "
                            + "without a dictionary: encodings " + chunk.getEncodings() + ", dictionary page "
                            + chunk.hasDictionaryPage() + ", pages " + (pages == null ? "unknown" : pages.getPageCount()));
                }
            }
        }
        System.out.printf("  OK  fixture: one row group, every chunk one BYTE_STREAM_SPLIT page without a dictionary%n");
    }

    /// parquet-java's low-level column API over every column: the gate's reference.
    private static long parquetJava() throws IOException {
        long checksum = 0;
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString());
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, new Configuration()))) {
            MessageType schema = reader.getFileMetaData().getSchema();
            String createdBy = reader.getFileMetaData().getCreatedBy();
            List<ColumnDescriptor> columns = schema.getColumns();
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                long rows = pages.getRowCount();
                ColumnReadStoreImpl store = new ColumnReadStoreImpl(pages, new NoOpGroupConverter(), schema, createdBy);
                for (ColumnDescriptor column : columns) {
                    org.apache.parquet.column.ColumnReader cr = store.getColumnReader(column);
                    for (long i = 0; i < rows; i++) {
                        checksum += Float.floatToRawIntBits(cr.getFloat());
                        cr.consume();
                    }
                }
            }
        }
        return checksum;
    }

    private static final class NoOpGroupConverter extends GroupConverter {
        @Override
        public Converter getConverter(int fieldIndex) {
            return new PrimitiveConverter() {
            };
        }

        @Override
        public void start() {
        }

        @Override
        public void end() {
        }
    }

    public static void main(String[] args) throws Exception {
        WideFileGenerator.ensure(FILE);
        if (Boolean.getBoolean("perf.gate")) {
            WideScanBenchmark gate = new WideScanBenchmark();
            gate.setup();
            gate.runGate();
            return;
        }
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(WideScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1))
                .jvmArgsAppend("--enable-native-access=ALL-UNNAMED");
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so the forked
        // benchmark sees the same settings.
        System.getProperties().forEach((k, val) -> {
            String key = (String) k;
            if (key.startsWith("perf.")) {
                opts.jvmArgsAppend("-D" + key + "=" + val);
            }
        });
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
        long rows = WideFileGenerator.ROWS;
        long bytes = Files.size(FILE);
        BenchReport.fixture(FILE);
        BenchReport.writeRunParams(rows, bytes, "columns", Integer.toString(WideFileGenerator.COLUMNS));

        Collection<RunResult> results = new Runner(opts.build()).run();

        BenchReport.printFullScanThroughput(results, WideScanBenchmark.class, rows, bytes);
        BenchReport.appendThroughputTsv(results, WideScanBenchmark.class, rows, bytes);
    }
}
