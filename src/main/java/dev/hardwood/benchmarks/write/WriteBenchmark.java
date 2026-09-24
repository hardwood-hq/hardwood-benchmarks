/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.write;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
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

import dev.hardwood.InputFile;
import dev.hardwood.OutputFile;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnWriter;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.RowWriter;
import dev.hardwood.writer.WriterConfig;

/// Flat write benchmark (regression-only): taxi-shaped records with nulls in two columns,
/// written to memory through Hardwood's column writer and row writer, SNAPPY and ZSTD.
///
/// Writing to memory keeps the filesystem and page cache out of the number, so it is encode
/// throughput. The compressed column-chunk bytes of each codec's file are recorded in the meta
/// sidecar (`bytes` for SNAPPY, `bytesZstd` for ZSTD): an encoding change that moves them
/// shows up there as a dataset difference between two versions, even where the time does not
/// move. Run with `run-write.sh`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WriteBenchmark {

    private static final int ROWS = Integer.getInteger("perf.rows", 500_000);
    private static final int PAGE_TARGET_BYTES = 1 << 20;
    private static final long ROW_GROUP_TARGET_BYTES = 16L << 20;

    @Param({ "SNAPPY", "ZSTD" })
    private String codec;

    private WriteFixture fixture;
    private FileSchema schema;
    private WriterConfig config;
    private MemoryOutputFile out;

    @Setup(Level.Trial)
    public void setup() {
        fixture = new WriteFixture(ROWS);
        schema = WriteFixture.schema();
        config = config(codec);
        out = new MemoryOutputFile();
    }

    private static WriterConfig config(String codec) {
        return WriterConfig.builder()
                .pageTargetBytes(PAGE_TARGET_BYTES)
                .rowGroupBufferTargetBytes(ROW_GROUP_TARGET_BYTES)
                .codec(CompressionCodec.valueOf(codec))
                .build();
    }

    /// Column writer: each batch's arrays as they are.
    @Benchmark
    public long hardwoodColumnar() throws IOException {
        writeColumnar(out, fixture, schema, config);
        return out.position();
    }

    /// Row writer through the named setters, with `String` and `Instant` values.
    @Benchmark
    public long hardwoodRow() throws IOException {
        writeRows(out, fixture, schema, config);
        return out.position();
    }

    static void writeColumnar(OutputFile out, WriteFixture f, FileSchema schema, WriterConfig config) throws IOException {
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            ColumnWriter columns = writer.columnWriter();
            for (int b = 0; b < f.batchCount(); b++) {
                int i = b;
                columns.writeBatch(batch -> batch
                        .longs("id", f.id[i])
                        .longs("pickup_ts", f.pickupMicros[i])
                        .ints("passenger_count", f.passengerCount[i], f.passengerCountNulls[i])
                        .doubles("fare", f.fare[i])
                        .bytes("payment_type", f.paymentTypeBytes[i])
                        .bytes("vendor", f.vendorBytes[i], f.vendorNulls[i]));
            }
        }
    }

    static void writeRows(OutputFile out, WriteFixture f, FileSchema schema, WriterConfig config) throws IOException {
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            RowWriter rows = writer.rowWriter();
            for (int b = 0; b < f.batchCount(); b++) {
                long[] ids = f.id[b];
                for (int r = 0; r < ids.length; r++) {
                    int i = b;
                    int row = r;
                    rows.writeRow(record -> {
                        record.setLong("id", f.id[i][row])
                                .setTimestamp("pickup_ts", f.pickup[i][row])
                                .setDouble("fare", f.fare[i][row])
                                .setString("payment_type", f.paymentType[i][row]);
                        if (f.passengerCountNulls[i][row]) {
                            record.setNull("passenger_count");
                        }
                        else {
                            record.setInt("passenger_count", f.passengerCount[i][row]);
                        }
                        if (f.vendorNulls[i][row]) {
                            record.setNull("vendor");
                        }
                        else {
                            record.setString("vendor", f.vendor[i][row]);
                        }
                    });
                }
            }
        }
    }

    /// The compressed size of every column chunk in the file: what the encoding produced, without
    /// the footer, whose `created_by` differs between Hardwood versions by the version string.
    private static long compressedChunkBytes(FileMetaData metadata) {
        long bytes = 0;
        for (RowGroup rowGroup : metadata.rowGroups()) {
            for (ColumnChunk chunk : rowGroup.columns()) {
                bytes += chunk.metaData().totalCompressedSize();
            }
        }
        return bytes;
    }

    /// Writes one file per API and codec, reads each back, and checks every column against the
    /// fixture: a sum per numeric column, an order-sensitive hash per string column, and the null
    /// count of each nullable one. Returns the compressed column-chunk bytes of the column writer's
    /// file.
    private static long gate(WriteFixture f, String codec) throws IOException {
        Totals expected = new Totals();
        for (int b = 0; b < f.batchCount(); b++) {
            for (int r = 0; r < f.id[b].length; r++) {
                expected.add(f.id[b][r], f.pickupMicros[b][r], f.passengerCountNulls[b][r], f.passengerCount[b][r],
                        f.fare[b][r], f.paymentType[b][r], f.vendorNulls[b][r], f.vendor[b][r]);
            }
        }
        long size = -1;
        for (String api : new String[] { "column writer", "row writer" }) {
            MemoryOutputFile out = new MemoryOutputFile();
            if (api.equals("column writer")) {
                writeColumnar(out, f, WriteFixture.schema(), config(codec));
            }
            else {
                writeRows(out, f, WriteFixture.schema(), config(codec));
            }
            Totals actual = new Totals();
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())));
                 RowReader rows = reader.buildRowReader().build()) {
                if (api.equals("column writer")) {
                    size = compressedChunkBytes(reader.getFileMetaData());
                }
                while (rows.hasNext()) {
                    rows.next();
                    boolean passengerCountNull = rows.isNull("passenger_count");
                    boolean vendorNull = rows.isNull("vendor");
                    actual.add(rows.getLong("id"), micros(rows.getTimestamp("pickup_ts")), passengerCountNull,
                            passengerCountNull ? 0 : rows.getInt("passenger_count"), rows.getDouble("fare"),
                            rows.getString("payment_type"), vendorNull, vendorNull ? null : rows.getString("vendor"));
                }
            }
            if (!actual.equals(expected)) {
                throw new IllegalStateException(String.format("[%s, %s] read back %s; wrote %s", codec, api, actual, expected));
            }
        }
        System.out.printf("Gate passed [%s] — column and row writer files read back %d rows, every column matching (%,d chunk bytes).%n",
                codec, f.rows, size);
        return size;
    }

    private static long micros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }

    /// Per-column totals over a file's rows, accumulated in row order from the fixture and from a
    /// read-back alike, so two files with the same values in the same order compare equal.
    private static final class Totals {

        private long rows;
        private long id;
        private long pickupMicros;
        private long passengerCount;
        private long passengerCountNulls;
        private double fare;
        private long paymentTypeHash;
        private long vendorHash;
        private long vendorNulls;

        void add(long id, long pickupMicros, boolean passengerCountNull, int passengerCount, double fare,
                String paymentType, boolean vendorNull, String vendor) {
            rows++;
            this.id += id;
            this.pickupMicros += pickupMicros;
            if (passengerCountNull) {
                passengerCountNulls++;
            }
            else {
                this.passengerCount += passengerCount;
            }
            this.fare += fare;
            paymentTypeHash = 31 * paymentTypeHash + paymentType.hashCode();
            if (vendorNull) {
                vendorNulls++;
            }
            else {
                vendorHash = 31 * vendorHash + vendor.hashCode();
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Totals t && rows == t.rows && id == t.id && pickupMicros == t.pickupMicros
                    && passengerCount == t.passengerCount && passengerCountNulls == t.passengerCountNulls
                    && Double.compare(fare, t.fare) == 0 && paymentTypeHash == t.paymentTypeHash
                    && vendorHash == t.vendorHash && vendorNulls == t.vendorNulls;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(rows);
        }

        @Override
        public String toString() {
            return String.format("%d rows, id %d, pickup_ts %d, passenger_count %d (%d null), fare %.3f, "
                    + "payment_type #%x, vendor #%x (%d null)", rows, id, pickupMicros, passengerCount,
                    passengerCountNulls, fare, paymentTypeHash, vendorHash, vendorNulls);
        }
    }

    public static void main(String[] args) throws Exception {
        WriteFixture fixture = new WriteFixture(ROWS);
        long snappyBytes = gate(fixture, "SNAPPY");
        long zstdBytes = gate(fixture, "ZSTD");
        if (Boolean.getBoolean("perf.gate")) {
            return;
        }
        BenchReport.writeRunParams(ROWS, snappyBytes, "bytesZstd", Long.toString(zstdBytes));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(WriteBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
        String param = System.getProperty("perf.param");
        if (param != null && !param.isBlank()) {
            opts.param("codec", param);
        }
        System.getProperties().forEach((k, v) -> {
            if (((String) k).startsWith("perf.")) {
                opts.jvmArgsAppend("-D" + k + "=" + v);
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
        Collection<RunResult> results = new Runner(opts.build()).run();
        BenchReport.appendMsPerOpTsv(results, WriteBenchmark.class);
    }
}
