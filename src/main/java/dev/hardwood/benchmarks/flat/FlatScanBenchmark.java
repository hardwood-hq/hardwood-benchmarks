/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.flat;
import dev.hardwood.benchmarks.BenchReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.dataset.file.FileFormat;
import org.apache.arrow.dataset.file.FileSystemDatasetFactory;
import org.apache.arrow.dataset.jni.NativeMemoryPool;
import org.apache.arrow.dataset.scanner.ScanOptions;
import org.apache.arrow.dataset.scanner.Scanner;
import org.apache.arrow.dataset.source.Dataset;
import org.apache.arrow.dataset.source.DatasetFactory;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificData;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.ParquetReader;
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

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Flat full-scan benchmark (benchmark #1): a full scan of **every** column of
/// the NYC Yellow Taxi monthly files (multi-file), reducing each column into a
/// per-file checksum (present values only) so every value is decoded, dead-code
/// elimination is prevented, and all contenders are proven to agree.
///
/// The field access is **materialized to the 2025 TLC schema** (the 20 columns
/// listed below) rather than a generic loop-and-type-switch: each contender reads
/// its fields with monomorphic, schema-specific calls, the way real code reads a
/// known schema. This removes generic-dispatch scaffolding from the measured work
/// (so the numbers reflect decode, not the harness) at the cost of pinning the
/// benchmark to that schema — `-Dperf.start`/`-Dperf.end` must stay within a
/// window whose schema matches — including the Arrow Dataset contender, whose
/// vectors are cast to their known 2025 types (a schema change surfaces as a
/// `ClassCastException`, like the other folds failing the gate).
///
/// Each Hardwood contender uses the default (all-cores) context; the
/// engine-for-engine single-thread number comes from running the
/// `taskset -c 0` pass (the JVM then sees one core, so the default decode pool
/// sizes to one thread). Run with `run-flat.sh` for both passes.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FlatScanBenchmark {

    /// The 2025 NYC Yellow Taxi columns, in schema order, that the materialized
    /// folds below are written against. Kept here so the hardcoded access and this
    /// list can be checked against each other. Order matches the on-disk schema
    /// (and the column-reader / descriptor / Avro field order).
    // 0  VendorID               INT32
    // 1  tpep_pickup_datetime   INT64
    // 2  tpep_dropoff_datetime  INT64
    // 3  passenger_count        INT64
    // 4  trip_distance          DOUBLE
    // 5  RatecodeID             INT64
    // 6  store_and_fwd_flag     BYTE_ARRAY (String)
    // 7  PULocationID           INT32
    // 8  DOLocationID           INT32
    // 9  payment_type           INT64
    // 10 fare_amount            DOUBLE
    // 11 extra                  DOUBLE
    // 12 mta_tax                DOUBLE
    // 13 tip_amount             DOUBLE
    // 14 tolls_amount           DOUBLE
    // 15 improvement_surcharge  DOUBLE
    // 16 total_amount           DOUBLE
    // 17 congestion_surcharge   DOUBLE
    // 18 Airport_fee            DOUBLE
    // 19 cbd_congestion_fee     DOUBLE

    /// Rows per Arrow `VectorSchemaRoot` batch for the Arrow Dataset contender.
    private static final int ARROW_BATCH_SIZE = 65_536;

    private List<Path> files;
    private List<InputFile> inputFiles;

    /// Hadoop config for the parquet-java contenders. Built once per trial (a field
    /// initializer, not per `@Benchmark` op) so its construction stays out of the
    /// timed region.
    private final Configuration conf = new Configuration();

    /// Config for the SpecificRecord reference contender: carries the
    /// [SpecificTaxiTrip] read schema so `AvroParquetReader` materializes the typed
    /// class instead of `GenericRecord`.
    private final Configuration specificConf = specificReadConf();

    private static Configuration specificReadConf() {
        Configuration c = new Configuration();
        AvroReadSupport.setAvroReadSchema(c, SpecificTaxiTrip.SCHEMA$);
        return c;
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        loadData();
    }

    /// Resolve and open the benchmark window's files. Runs per JMH trial — each
    /// forked JVM needs its own [files] / [inputFiles] — and once from [main] to
    /// prime the standalone correctness gate.
    private void loadData() throws IOException {
        YearMonth start = YearMonth.parse(System.getProperty("perf.start", "2025-01"));
        YearMonth end = YearMonth.parse(System.getProperty("perf.end", "2025-12"));
        files = TaxiDataDownloader.ensure(start, end);
        if (files.isEmpty()) {
            throw new IllegalStateException("No taxi files for " + start + ".." + end);
        }
        inputFiles = new ArrayList<>(files.size());
        for (Path f : files) {
            inputFiles.add(InputFile.of(f));
        }
    }

    /// Correctness gate: every contender must fold to the same checksum as
    /// parquet-java before any timing counts. Run once from [main], outside JMH's
    /// per-trial dispatch — the checksums are deterministic, so a single
    /// verification covers every fork and benchmark method (running it per trial
    /// re-scanned the window `forks x methods` times for no added signal). Named
    /// and indexed access read the same values, so both must match the reference.
    private void runGate() throws IOException {
        System.out.println("Correctness gate — folding every contender, comparing to parquet-java:");
        long ref = parquetJavaColumnar();
        check("Hardwood column reader", hardwoodColumnar(), ref);
        check("Hardwood row reader (indexed)", hardwoodRowIndexed(), ref);
        check("Hardwood row reader (named)", hardwoodRowNamed(), ref);
        check("AvroParquetReader (indexed)", avroIndexed(), ref);
        check("AvroParquetReader (named)", avroNamed(), ref);
        check("AvroParquetReader (specific)", avroSpecific(), ref);
        check("Arrow Dataset", arrowDatasetScan(), ref);
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
        try (ParquetFileReader reader = ParquetFileReader.openAll(inputFiles);
             ColumnReaders cols = buildColumnReaders(reader)) {
            ColumnReader vendorId = cols.getColumnReader(0);
            ColumnReader pickup = cols.getColumnReader(1);
            ColumnReader dropoff = cols.getColumnReader(2);
            ColumnReader passengerCount = cols.getColumnReader(3);
            ColumnReader tripDistance = cols.getColumnReader(4);
            ColumnReader ratecodeId = cols.getColumnReader(5);
            ColumnReader storeAndFwd = cols.getColumnReader(6);
            ColumnReader puLocationId = cols.getColumnReader(7);
            ColumnReader doLocationId = cols.getColumnReader(8);
            ColumnReader paymentType = cols.getColumnReader(9);
            ColumnReader fareAmount = cols.getColumnReader(10);
            ColumnReader extra = cols.getColumnReader(11);
            ColumnReader mtaTax = cols.getColumnReader(12);
            ColumnReader tipAmount = cols.getColumnReader(13);
            ColumnReader tollsAmount = cols.getColumnReader(14);
            ColumnReader improvementSurcharge = cols.getColumnReader(15);
            ColumnReader totalAmount = cols.getColumnReader(16);
            ColumnReader congestionSurcharge = cols.getColumnReader(17);
            ColumnReader airportFee = cols.getColumnReader(18);
            ColumnReader cbdCongestionFee = cols.getColumnReader(19);
            while (cols.nextBatch()) {
                for (int x : vendorId.getInts()) checksum += x;
                for (long x : pickup.getLongs()) checksum += x;
                for (long x : dropoff.getLongs()) checksum += x;
                for (long x : passengerCount.getLongs()) checksum += x;
                for (double x : tripDistance.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (long x : ratecodeId.getLongs()) checksum += x;
                for (String x : storeAndFwd.getStrings()) {
                    if (x != null) checksum += x.getBytes(StandardCharsets.UTF_8).length;
                }
                for (int x : puLocationId.getInts()) checksum += x;
                for (int x : doLocationId.getInts()) checksum += x;
                for (long x : paymentType.getLongs()) checksum += x;
                for (double x : fareAmount.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : extra.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : mtaTax.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : tipAmount.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : tollsAmount.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : improvementSurcharge.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : totalAmount.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : congestionSurcharge.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : airportFee.getDoubles()) checksum += Double.doubleToLongBits(x);
                for (double x : cbdCongestionFee.getDoubles()) checksum += Double.doubleToLongBits(x);
            }
        }
        return checksum;
    }

    /// Builds the column readers over all columns. `-Dperf.batchSize` overrides the
    /// reader's batch size (0/unset = Hardwood's default); shrinking it below the
    /// cache size is the no-profiler way to test whether the single-core columnar
    /// throughput is bound by re-reading decoded batches that overflow the cache.
    private ColumnReaders buildColumnReaders(ParquetFileReader reader) {
        ParquetFileReader.ColumnReadersBuilder builder = reader.buildColumnReaders(ColumnProjection.all());
        int batchSize = Integer.getInteger("perf.batchSize", 0);
        if (batchSize > 0) {
            builder.batchSize(batchSize);
        }
        return builder.build();
    }

    @Benchmark
    public long hardwoodRowReaderNamed() throws IOException {
        return hardwoodRowNamed();
    }

    @Benchmark
    public long hardwoodRowReaderIndexed() throws IOException {
        return hardwoodRowIndexed();
    }

    @Benchmark
    public long avroParquetReaderNamed() throws IOException {
        return avroNamed();
    }

    @Benchmark
    public long avroParquetReaderIndexed() throws IOException {
        return avroIndexed();
    }

    /// Internal reference only — **not published**. `AvroParquetReader` materializing
    /// a typed `SpecificRecord` ([SpecificTaxiTrip]) rather than `GenericRecord`. Kept
    /// to confirm the record-path lead is not a `GenericRecord` artifact: this is the
    /// fastest Avro record class, so its row belongs beside the Avro baseline for our
    /// own orientation, not in a post or the design doc. Gated for correctness and
    /// never plotted (the chart generator reads contenders by name), like the Arrow
    /// Dataset contender. See [#avroSpecific] for the scan.
    @Benchmark
    public long avroParquetReaderSpecific() throws IOException {
        return avroSpecific();
    }

    /// Internal reference only — **not published**. The 1.0 materials report
    /// Hardwood vs parquet-java; this Arrow Dataset contender is kept for our own
    /// orientation and a possible future Arrow comparison, so do not paste its row
    /// into a post or the design doc. See `arrowDatasetScan` for the scan itself.
    @Benchmark
    public long arrowDataset() throws IOException {
        return arrowDatasetScan();
    }

    /// Hardwood row reader, field-by-name access (the ergonomic path): materialize
    /// every record and fold each present field with monomorphic, schema-specific
    /// named access.
    private long hardwoodRowNamed() throws IOException {
        long checksum = 0;
        try (ParquetFileReader reader = ParquetFileReader.openAll(inputFiles);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                checksum += foldHardwoodRowNamed(rows);
            }
        }
        return checksum;
    }

    /// Hardwood row reader, positional index access (the power-user path).
    private long hardwoodRowIndexed() throws IOException {
        long checksum = 0;
        try (ParquetFileReader reader = ParquetFileReader.openAll(inputFiles);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                checksum += foldHardwoodRowIndexed(rows);
            }
        }
        return checksum;
    }

    private static long foldHardwoodRowNamed(RowReader r) {
        long s = 0;
        if (!r.isNull("VendorID")) s += r.getInt("VendorID");
        if (!r.isNull("tpep_pickup_datetime")) s += r.getLong("tpep_pickup_datetime");
        if (!r.isNull("tpep_dropoff_datetime")) s += r.getLong("tpep_dropoff_datetime");
        if (!r.isNull("passenger_count")) s += r.getLong("passenger_count");
        if (!r.isNull("trip_distance")) s += Double.doubleToLongBits(r.getDouble("trip_distance"));
        if (!r.isNull("RatecodeID")) s += r.getLong("RatecodeID");
        if (!r.isNull("store_and_fwd_flag")) s += r.getString("store_and_fwd_flag").getBytes(StandardCharsets.UTF_8).length;
        if (!r.isNull("PULocationID")) s += r.getInt("PULocationID");
        if (!r.isNull("DOLocationID")) s += r.getInt("DOLocationID");
        if (!r.isNull("payment_type")) s += r.getLong("payment_type");
        if (!r.isNull("fare_amount")) s += Double.doubleToLongBits(r.getDouble("fare_amount"));
        if (!r.isNull("extra")) s += Double.doubleToLongBits(r.getDouble("extra"));
        if (!r.isNull("mta_tax")) s += Double.doubleToLongBits(r.getDouble("mta_tax"));
        if (!r.isNull("tip_amount")) s += Double.doubleToLongBits(r.getDouble("tip_amount"));
        if (!r.isNull("tolls_amount")) s += Double.doubleToLongBits(r.getDouble("tolls_amount"));
        if (!r.isNull("improvement_surcharge")) s += Double.doubleToLongBits(r.getDouble("improvement_surcharge"));
        if (!r.isNull("total_amount")) s += Double.doubleToLongBits(r.getDouble("total_amount"));
        if (!r.isNull("congestion_surcharge")) s += Double.doubleToLongBits(r.getDouble("congestion_surcharge"));
        if (!r.isNull("Airport_fee")) s += Double.doubleToLongBits(r.getDouble("Airport_fee"));
        if (!r.isNull("cbd_congestion_fee")) s += Double.doubleToLongBits(r.getDouble("cbd_congestion_fee"));
        return s;
    }

    private static long foldHardwoodRowIndexed(RowReader r) {
        long s = 0;
        if (!r.isNull(0)) s += r.getInt(0);
        if (!r.isNull(1)) s += r.getLong(1);
        if (!r.isNull(2)) s += r.getLong(2);
        if (!r.isNull(3)) s += r.getLong(3);
        if (!r.isNull(4)) s += Double.doubleToLongBits(r.getDouble(4));
        if (!r.isNull(5)) s += r.getLong(5);
        if (!r.isNull(6)) s += r.getString(6).getBytes(StandardCharsets.UTF_8).length;
        if (!r.isNull(7)) s += r.getInt(7);
        if (!r.isNull(8)) s += r.getInt(8);
        if (!r.isNull(9)) s += r.getLong(9);
        if (!r.isNull(10)) s += Double.doubleToLongBits(r.getDouble(10));
        if (!r.isNull(11)) s += Double.doubleToLongBits(r.getDouble(11));
        if (!r.isNull(12)) s += Double.doubleToLongBits(r.getDouble(12));
        if (!r.isNull(13)) s += Double.doubleToLongBits(r.getDouble(13));
        if (!r.isNull(14)) s += Double.doubleToLongBits(r.getDouble(14));
        if (!r.isNull(15)) s += Double.doubleToLongBits(r.getDouble(15));
        if (!r.isNull(16)) s += Double.doubleToLongBits(r.getDouble(16));
        if (!r.isNull(17)) s += Double.doubleToLongBits(r.getDouble(17));
        if (!r.isNull(18)) s += Double.doubleToLongBits(r.getDouble(18));
        if (!r.isNull(19)) s += Double.doubleToLongBits(r.getDouble(19));
        return s;
    }

    /// parquet-java record path: `AvroParquetReader` materializing every record,
    /// fields read by name (`GenericRecord.get(String)`, the idiomatic ergonomic
    /// path) with monomorphic schema-specific access mirroring the Hardwood row
    /// reader.
    private long avroNamed() throws IOException {
        long checksum = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                    HadoopInputFile.fromPath(hPath, conf)).build()) {
                GenericRecord record;
                while ((record = reader.read()) != null) {
                    checksum += foldAvroNamed(record);
                }
            }
        }
        return checksum;
    }

    /// parquet-java record path with positional access (`GenericRecord.get(int)`).
    private long avroIndexed() throws IOException {
        long checksum = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                    HadoopInputFile.fromPath(hPath, conf)).build()) {
                GenericRecord record;
                while ((record = reader.read()) != null) {
                    checksum += foldAvroIndexed(record);
                }
            }
        }
        return checksum;
    }

    /// parquet-java record path with a typed `SpecificRecord` (internal reference,
    /// not published). Fields by typed accessor — no `GenericRecord` name lookup or
    /// `Object` cast. Requires the [#specificConf] read schema so the typed class is
    /// materialized.
    private long avroSpecific() throws IOException {
        long checksum = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (ParquetReader<SpecificTaxiTrip> reader = AvroParquetReader.<SpecificTaxiTrip>builder(
                    HadoopInputFile.fromPath(hPath, specificConf))
                    .withDataModel(SpecificData.get())
                    .withConf(specificConf)
                    .build()) {
                SpecificTaxiTrip record;
                while ((record = reader.read()) != null) {
                    checksum += foldSpecific(record);
                }
            }
        }
        return checksum;
    }

    private static long foldSpecific(SpecificTaxiTrip r) {
        long s = 0;
        if (r.vendorId != null) s += r.vendorId;
        if (r.tpepPickupDatetime != null) s += r.tpepPickupDatetime;
        if (r.tpepDropoffDatetime != null) s += r.tpepDropoffDatetime;
        if (r.passengerCount != null) s += r.passengerCount;
        if (r.tripDistance != null) s += Double.doubleToLongBits(r.tripDistance);
        if (r.ratecodeId != null) s += r.ratecodeId;
        if (r.storeAndFwdFlag != null) s += avroStringLen(r.storeAndFwdFlag);
        if (r.puLocationId != null) s += r.puLocationId;
        if (r.doLocationId != null) s += r.doLocationId;
        if (r.paymentType != null) s += r.paymentType;
        if (r.fareAmount != null) s += Double.doubleToLongBits(r.fareAmount);
        if (r.extra != null) s += Double.doubleToLongBits(r.extra);
        if (r.mtaTax != null) s += Double.doubleToLongBits(r.mtaTax);
        if (r.tipAmount != null) s += Double.doubleToLongBits(r.tipAmount);
        if (r.tollsAmount != null) s += Double.doubleToLongBits(r.tollsAmount);
        if (r.improvementSurcharge != null) s += Double.doubleToLongBits(r.improvementSurcharge);
        if (r.totalAmount != null) s += Double.doubleToLongBits(r.totalAmount);
        if (r.congestionSurcharge != null) s += Double.doubleToLongBits(r.congestionSurcharge);
        if (r.airportFee != null) s += Double.doubleToLongBits(r.airportFee);
        if (r.cbdCongestionFee != null) s += Double.doubleToLongBits(r.cbdCongestionFee);
        return s;
    }

    private static long foldAvroNamed(GenericRecord r) {
        long s = 0;
        Object v;
        if ((v = r.get("VendorID")) != null) s += (Integer) v;
        if ((v = r.get("tpep_pickup_datetime")) != null) s += (Long) v;
        if ((v = r.get("tpep_dropoff_datetime")) != null) s += (Long) v;
        if ((v = r.get("passenger_count")) != null) s += (Long) v;
        if ((v = r.get("trip_distance")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("RatecodeID")) != null) s += (Long) v;
        if ((v = r.get("store_and_fwd_flag")) != null) s += avroStringLen(v);
        if ((v = r.get("PULocationID")) != null) s += (Integer) v;
        if ((v = r.get("DOLocationID")) != null) s += (Integer) v;
        if ((v = r.get("payment_type")) != null) s += (Long) v;
        if ((v = r.get("fare_amount")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("extra")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("mta_tax")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("tip_amount")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("tolls_amount")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("improvement_surcharge")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("total_amount")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("congestion_surcharge")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("Airport_fee")) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get("cbd_congestion_fee")) != null) s += Double.doubleToLongBits((Double) v);
        return s;
    }

    private static long foldAvroIndexed(GenericRecord r) {
        long s = 0;
        Object v;
        if ((v = r.get(0)) != null) s += (Integer) v;
        if ((v = r.get(1)) != null) s += (Long) v;
        if ((v = r.get(2)) != null) s += (Long) v;
        if ((v = r.get(3)) != null) s += (Long) v;
        if ((v = r.get(4)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(5)) != null) s += (Long) v;
        if ((v = r.get(6)) != null) s += avroStringLen(v);
        if ((v = r.get(7)) != null) s += (Integer) v;
        if ((v = r.get(8)) != null) s += (Integer) v;
        if ((v = r.get(9)) != null) s += (Long) v;
        if ((v = r.get(10)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(11)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(12)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(13)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(14)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(15)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(16)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(17)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(18)) != null) s += Double.doubleToLongBits((Double) v);
        if ((v = r.get(19)) != null) s += Double.doubleToLongBits((Double) v);
        return s;
    }

    /// Avro materializes a BYTE_ARRAY/String column as a `Utf8` (a `CharSequence`);
    /// fold its UTF-8 byte length to match the other readers' string fold.
    private static long avroStringLen(Object v) {
        return ((CharSequence) v).toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /// Cross-engine columnar reference: Arrow C++ reached through the Arrow Dataset
    /// JNI bindings, materialized to the 2025 schema like the JVM contenders — each
    /// column's vector is cast to its known Arrow type and folded by a type-specific
    /// helper, no generic per-vector dispatch. In the 2025 files the string column
    /// is a `LargeVarCharVector` (Arrow's 64-bit-offset variant) and the timestamps
    /// are `TimeStampVector`s exposing the raw stored `long` (matching parquet-java's
    /// INT64). A schema change surfaces as a `ClassCastException`, like the other
    /// folds failing the gate. Requires the
    /// `--add-opens=java.base/java.nio=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow`
    /// JVM flags wired into the JMH fork (see [#main]).
    private long arrowDatasetScan() throws IOException {
        long checksum = 0;
        // One dataset over every file in the window (each file a fragment), so the
        // Arrow C++ scanner parallelises fragment reads across its CPU thread pool —
        // the cross-file concurrency a per-file loop would serialise away. The
        // factory/dataset/scanner close() methods declare the checked Exception;
        // narrow it to IOException for the benchmark signature.
        String[] uris = files.stream()
                .map(f -> f.toAbsolutePath().toUri().toString())
                .toArray(String[]::new);
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
             DatasetFactory factory = new FileSystemDatasetFactory(
                     allocator, NativeMemoryPool.getDefault(), FileFormat.PARQUET, uris);
             Dataset dataset = factory.finish();
             Scanner scanner = dataset.newScan(new ScanOptions(ARROW_BATCH_SIZE));
             ArrowReader reader = scanner.scanBatches()) {
            while (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                checksum += foldArrowInt((IntVector) root.getVector(0));         // VendorID
                checksum += foldArrowTs((TimeStampVector) root.getVector(1));    // tpep_pickup_datetime
                checksum += foldArrowTs((TimeStampVector) root.getVector(2));    // tpep_dropoff_datetime
                checksum += foldArrowLong((BigIntVector) root.getVector(3));     // passenger_count
                checksum += foldArrowDouble((Float8Vector) root.getVector(4));   // trip_distance
                checksum += foldArrowLong((BigIntVector) root.getVector(5));     // RatecodeID
                checksum += foldArrowStr((LargeVarCharVector) root.getVector(6)); // store_and_fwd_flag
                checksum += foldArrowInt((IntVector) root.getVector(7));         // PULocationID
                checksum += foldArrowInt((IntVector) root.getVector(8));         // DOLocationID
                checksum += foldArrowLong((BigIntVector) root.getVector(9));     // payment_type
                checksum += foldArrowDouble((Float8Vector) root.getVector(10));  // fare_amount
                checksum += foldArrowDouble((Float8Vector) root.getVector(11));  // extra
                checksum += foldArrowDouble((Float8Vector) root.getVector(12));  // mta_tax
                checksum += foldArrowDouble((Float8Vector) root.getVector(13));  // tip_amount
                checksum += foldArrowDouble((Float8Vector) root.getVector(14));  // tolls_amount
                checksum += foldArrowDouble((Float8Vector) root.getVector(15));  // improvement_surcharge
                checksum += foldArrowDouble((Float8Vector) root.getVector(16));  // total_amount
                checksum += foldArrowDouble((Float8Vector) root.getVector(17));  // congestion_surcharge
                checksum += foldArrowDouble((Float8Vector) root.getVector(18));  // Airport_fee
                checksum += foldArrowDouble((Float8Vector) root.getVector(19));  // cbd_congestion_fee
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Arrow Dataset scan failed", e);
        }
        return checksum;
    }

    private static long foldArrowInt(IntVector v) {
        int n = v.getValueCount();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (!v.isNull(i)) sum += v.get(i);
        }
        return sum;
    }

    private static long foldArrowLong(BigIntVector v) {
        int n = v.getValueCount();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (!v.isNull(i)) sum += v.get(i);
        }
        return sum;
    }

    /// Timestamp columns are INT64 micros on disk; the Arrow timestamp vector
    /// exposes that raw `long`, matching parquet-java's `getLong`.
    private static long foldArrowTs(TimeStampVector v) {
        int n = v.getValueCount();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (!v.isNull(i)) sum += v.get(i);
        }
        return sum;
    }

    private static long foldArrowDouble(Float8Vector v) {
        int n = v.getValueCount();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (!v.isNull(i)) sum += Double.doubleToLongBits(v.get(i));
        }
        return sum;
    }

    /// Materialise the string the way the record contenders do — `getObject`
    /// decodes the stored UTF-8 bytes into a `Text` (a `CharSequence`), which we
    /// fold by its UTF-8 byte length — rather than reading the stored length
    /// straight off the offset buffer with `getValueLength`. The decode is the work
    /// a real string-column scan performs, so all contenders must do it for the fold
    /// to be uniform.
    private static long foldArrowStr(LargeVarCharVector v) {
        int n = v.getValueCount();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (!v.isNull(i)) sum += v.getObject(i).toString().getBytes(StandardCharsets.UTF_8).length;
        }
        return sum;
    }

    /// parquet-java low-level column API, materialized: each column read
    /// value-at-a-time with a monomorphic, type-specific fold (no per-value type
    /// switch), present values only. Also the reference the correctness gate folds
    /// every other contender against.
    @Benchmark
    public long parquetJavaColumnar() throws IOException {
        long checksum = 0;
        for (Path file : files) {
            org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
            try (org.apache.parquet.hadoop.ParquetFileReader reader =
                         org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, conf))) {
                MessageType schema = reader.getFileMetaData().getSchema();
                String createdBy = reader.getFileMetaData().getCreatedBy();
                List<ColumnDescriptor> d = schema.getColumns();
                PageReadStore pages;
                while ((pages = reader.readNextRowGroup()) != null) {
                    long rows = pages.getRowCount();
                    ColumnReadStoreImpl store = new ColumnReadStoreImpl(
                            pages, new NoOpGroupConverter(), schema, createdBy);
                    checksum += pjInt(store.getColumnReader(d.get(0)), d.get(0), rows);
                    checksum += pjLong(store.getColumnReader(d.get(1)), d.get(1), rows);
                    checksum += pjLong(store.getColumnReader(d.get(2)), d.get(2), rows);
                    checksum += pjLong(store.getColumnReader(d.get(3)), d.get(3), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(4)), d.get(4), rows);
                    checksum += pjLong(store.getColumnReader(d.get(5)), d.get(5), rows);
                    checksum += pjString(store.getColumnReader(d.get(6)), d.get(6), rows);
                    checksum += pjInt(store.getColumnReader(d.get(7)), d.get(7), rows);
                    checksum += pjInt(store.getColumnReader(d.get(8)), d.get(8), rows);
                    checksum += pjLong(store.getColumnReader(d.get(9)), d.get(9), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(10)), d.get(10), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(11)), d.get(11), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(12)), d.get(12), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(13)), d.get(13), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(14)), d.get(14), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(15)), d.get(15), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(16)), d.get(16), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(17)), d.get(17), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(18)), d.get(18), rows);
                    checksum += pjDouble(store.getColumnReader(d.get(19)), d.get(19), rows);
                }
            }
        }
        return checksum;
    }

    private static long pjInt(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) sum += cr.getInteger();
            cr.consume();
        }
        return sum;
    }

    private static long pjLong(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) sum += cr.getLong();
            cr.consume();
        }
        return sum;
    }

    private static long pjDouble(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) sum += Double.doubleToLongBits(cr.getDouble());
            cr.consume();
        }
        return sum;
    }

    /// Materialise the string the way the record contenders do — UTF-8 decode the
    /// stored binary to a `String`, then fold its UTF-8 byte length — rather than
    /// reading the stored byte length straight off the `Binary`. The decode is the
    /// work a real string-column scan performs (and what every record contender
    /// already pays), so all contenders must do it for the fold to be uniform.
    private static long pjString(org.apache.parquet.column.ColumnReader cr, ColumnDescriptor cd, long rows) {
        int maxDef = cd.getMaxDefinitionLevel();
        long sum = 0;
        for (long i = 0; i < rows; i++) {
            if (cr.getCurrentDefinitionLevel() == maxDef) {
                sum += cr.getBinary().toStringUsingUTF8().getBytes(StandardCharsets.UTF_8).length;
            }
            cr.consume();
        }
        return sum;
    }

    /// No-op converter required by [ColumnReadStoreImpl]; records are never assembled.
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
        // Gate-check mode: verify every contender agrees, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            FlatScanBenchmark gate = new FlatScanBenchmark();
            gate.loadData();
            gate.runGate();
            return;
        }
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(FlatScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(2))
                .measurementTime(TimeValue.seconds(2))
                .forks(Integer.getInteger("perf.forks", 1))
                // The Arrow Dataset contender reaches Arrow C++ over JNI: open
                // java.nio for off-heap memory, permit Netty's sun.misc.Unsafe
                // access on JDK 23+, and allow the native dataset library to load.
                .jvmArgsAppend(
                        "--add-opens=java.base/java.nio=ALL-UNNAMED",
                        "--enable-native-access=ALL-UNNAMED",
                        "--sun-misc-unsafe-memory-access=allow");
        System.getProperties().forEach((k, val) -> {
            String key = (String) k;
            // Forward perf.* and data.dir into the fork: the forked JVM runs
            // @Setup, which resolves the (possibly custom) taxi data directory.
            if (key.startsWith("perf.") || key.equals("data.dir")) {
                opts.jvmArgsAppend("-D" + key + "=" + val);
            }
        });
        // Optional profilers, e.g. -Dperf.prof=gc,stack or -Dperf.prof=perfnorm
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
        // Resolve the window and dataset size up front (the same files setup() reads)
        // so the run logs its params before timing; reused below for throughput.
        YearMonth start = YearMonth.parse(System.getProperty("perf.start", "2025-01"));
        YearMonth end = YearMonth.parse(System.getProperty("perf.end", "2025-12"));
        List<Path> files = TaxiDataDownloader.ensure(start, end);
        long rows = BenchReport.totalRows(files);
        long bytes = BenchReport.totalBytes(files);
        BenchReport.writeRunParams(rows, bytes, start + "–" + end);

        java.util.Collection<org.openjdk.jmh.results.RunResult> results = new Runner(opts.build()).run();

        BenchReport.printFullScanThroughput(results, FlatScanBenchmark.class, rows, bytes);
        BenchReport.appendThroughputTsv(results, FlatScanBenchmark.class, rows, bytes);
    }
}
