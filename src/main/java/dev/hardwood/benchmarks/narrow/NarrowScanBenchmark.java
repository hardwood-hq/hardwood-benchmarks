/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.narrow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.util.HadoopInputFile;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.benchmarks.BenchContext;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.TaxiDataDownloader;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Narrow-projection scan (regression-only): three numeric columns of the 2016-01 NYC Yellow
/// Taxi file, folded into a checksum of their present values.
///
/// The month is one row group of 10.9M rows, GZIP-compressed, with pages of about 0.7M rows:
/// about 15 pages per column. A read of three columns has few pages in flight at a time, and is
/// long enough that how far the reader decodes ahead of the batch it hands out sets its
/// throughput: a shape neither the flat full scan, which reads twenty columns, nor a
/// three-column read of a 2025 month, two pages per column and row group, exercises.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class NarrowScanBenchmark {

    /// The projected columns: `INT64`, `DOUBLE`, `DOUBLE` in the 2016 schema.
    private static final String[] COLUMNS = { "passenger_count", "trip_distance", "fare_amount" };

    private List<Path> files;
    private List<InputFile> inputFiles;
    private final Configuration conf = new Configuration();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        loadData();
    }

    private void loadData() throws IOException {
        YearMonth start = YearMonth.parse(System.getProperty("perf.start", "2016-01"));
        YearMonth end = YearMonth.parse(System.getProperty("perf.end", "2016-01"));
        files = TaxiDataDownloader.ensure(start, end);
        if (files.isEmpty()) {
            throw new IllegalStateException("No taxi files for " + start + ".." + end);
        }
        inputFiles = new ArrayList<>(files.size());
        for (Path f : files) {
            inputFiles.add(InputFile.of(f));
        }
    }

    /// Correctness gate: both Hardwood readers must fold to parquet-java's checksum.
    private void runGate() throws IOException {
        System.out.println("Correctness gate — folding every contender, comparing to parquet-java:");
        long ref = parquetJava();
        check("Hardwood column reader", hardwoodColumnar(), ref);
        check("Hardwood row reader (indexed)", hardwoodRowReaderIndexed(), ref);
        System.out.printf("Gate passed — all contenders agree (checksum %d).%n", ref);
    }

    private static void check(String name, long actual, long ref) {
        if (actual != ref) {
            throw new IllegalStateException("Checksum mismatch: " + name + " " + actual + " vs parquet-java " + ref);
        }
        System.out.printf("  OK  %s%n", name);
    }

    @Benchmark
    public long hardwoodColumnar() throws IOException {
        long checksum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.openAll(inputFiles, context);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns(COLUMNS)).build()) {
            ColumnReader passengerCount = cols.getColumnReader(0);
            ColumnReader tripDistance = cols.getColumnReader(1);
            ColumnReader fareAmount = cols.getColumnReader(2);
            while (cols.nextBatch()) {
                int n = cols.getRecordCount();
                long[] passengers = passengerCount.getLongs();
                double[] distances = tripDistance.getDoubles();
                double[] fares = fareAmount.getDoubles();
                for (int i = 0; i < n; i++) {
                    checksum += passengers[i] + Double.doubleToLongBits(distances[i])
                            + Double.doubleToLongBits(fares[i]);
                }
            }
        }
        return checksum;
    }

    @Benchmark
    public long hardwoodRowReaderIndexed() throws IOException {
        long checksum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.openAll(inputFiles, context);
             RowReader rows = reader.buildRowReader().projection(ColumnProjection.columns(COLUMNS)).build()) {
            while (rows.hasNext()) {
                rows.next();
                if (!rows.isNull(0)) checksum += rows.getLong(0);
                if (!rows.isNull(1)) checksum += Double.doubleToLongBits(rows.getDouble(1));
                if (!rows.isNull(2)) checksum += Double.doubleToLongBits(rows.getDouble(2));
            }
        }
        return checksum;
    }

    /// parquet-java's low-level column API over the same three columns: the gate's reference.
    private long parquetJava() throws IOException {
        long checksum = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (org.apache.parquet.hadoop.ParquetFileReader reader =
                         org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, conf))) {
                MessageType schema = reader.getFileMetaData().getSchema();
                String createdBy = reader.getFileMetaData().getCreatedBy();
                ColumnDescriptor passengers = schema.getColumnDescription(new String[]{ COLUMNS[0] });
                ColumnDescriptor distance = schema.getColumnDescription(new String[]{ COLUMNS[1] });
                ColumnDescriptor fare = schema.getColumnDescription(new String[]{ COLUMNS[2] });
                PageReadStore pages;
                while ((pages = reader.readNextRowGroup()) != null) {
                    long rows = pages.getRowCount();
                    ColumnReadStoreImpl store = new ColumnReadStoreImpl(pages, new NoOpGroupConverter(), schema, createdBy);
                    checksum += foldLongs(store.getColumnReader(passengers), passengers, rows);
                    checksum += foldDoubles(store.getColumnReader(distance), distance, rows);
                    checksum += foldDoubles(store.getColumnReader(fare), fare, rows);
                }
            }
        }
        return checksum;
    }

    private static long foldLongs(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) sum += cr.getLong();
            cr.consume();
        }
        return sum;
    }

    private static long foldDoubles(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) sum += Double.doubleToLongBits(cr.getDouble());
            cr.consume();
        }
        return sum;
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
        if (Boolean.getBoolean("perf.gate")) {
            NarrowScanBenchmark gate = new NarrowScanBenchmark();
            gate.loadData();
            gate.runGate();
            return;
        }
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(NarrowScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1))
                .jvmArgsAppend("--enable-native-access=ALL-UNNAMED");
        System.getProperties().forEach((k, val) -> {
            String key = (String) k;
            if (key.startsWith("perf.") || key.equals("data.dir")) {
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
        YearMonth start = YearMonth.parse(System.getProperty("perf.start", "2016-01"));
        YearMonth end = YearMonth.parse(System.getProperty("perf.end", "2016-01"));
        List<Path> files = TaxiDataDownloader.ensure(start, end);
        long rows = BenchReport.totalRows(files);
        long bytes = BenchReport.totalBytes(files);
        BenchReport.writeRunParams(rows, bytes, start + "–" + end);

        java.util.Collection<org.openjdk.jmh.results.RunResult> results = new Runner(opts.build()).run();

        BenchReport.printFullScanThroughput(results, NarrowScanBenchmark.class, rows, bytes);
        BenchReport.appendThroughputTsv(results, NarrowScanBenchmark.class, rows, bytes);
    }
}
