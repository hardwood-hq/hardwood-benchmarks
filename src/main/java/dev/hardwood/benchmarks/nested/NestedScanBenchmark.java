/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.nested;
import dev.hardwood.benchmarks.BenchReport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;
import dev.hardwood.row.StructAccessor;

/// Nested-read benchmark (benchmark #2): a full read of the single-file Overture
/// Maps places dataset — deeply nested struct / list / map — comparing the
/// record pair, Hardwood's row reader against parquet-java's `AvroParquetReader`.
/// Both reconstruct nested records, so this is the like-for-like nested
/// comparison; the file is single, so there is no cross-file asymmetry and the
/// parallel advantage is purely within-file concurrent decode.
///
/// Each contender **traverses the whole record** — recursing every struct, list,
/// and map down to the scalar leaves — so neither skips reconstruction the other
/// performs. Every leaf is folded into a representation-stable checksum
/// ([Checksum]) so the two readers are proven to assemble the same data before
/// any timing counts.
///
/// The Hardwood reader is run twice: with the default all-cores context and with
/// a one-thread context, mirroring [FlatScanBenchmark]. parquet-java is
/// single-threaded by construction. Run with `run-nested.sh` for the all-cores
/// and taskset-pinned passes.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class NestedScanBenchmark {

    private static final Path FILE = Path.of(System.getProperty(
            "perf.file", "target/overture-maps-data/overture_places.zstd.parquet"));

    private InputFile inputFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        loadData();
    }

    /// Open the nested file. Runs per JMH trial — each forked JVM needs its own
    /// [inputFile] — and once from [main] to prime the standalone correctness gate.
    private void loadData() throws IOException {
        inputFile = InputFile.of(OvertureMapsDownloader.ensure(FILE));
    }

    /// Correctness gate: the record pair must assemble identical data before any
    /// timing counts (AvroParquetReader is the reference). Run once from [main],
    /// outside JMH's per-trial dispatch — the checksum is deterministic, so a
    /// single verification covers every fork and benchmark method.
    private void runGate() throws IOException {
        System.out.println("Correctness gate — reconstructing every record, comparing to AvroParquetReader:");
        Checksum ref = avro();
        check("Hardwood row reader", hardwoodRow(), ref);
        // Named access must read the same values as indexed; the timed loop uses
        // the light per-leaf touch, whose sum is order-independent, so equal sums
        // prove the named path resolves the same fields the indexed path does.
        checkSameAccess("Hardwood row reader", hardwoodScan(true), hardwoodScan(false));
        checkSameAccess("AvroParquetReader", avroScan(true), avroScan(false));
        System.out.println("Gate passed — record pair assembles identical data; named and indexed access agree.");
    }

    private static void check(String name, Checksum actual, Checksum ref) {
        if (!actual.matches(ref)) {
            throw new IllegalStateException("Checksum mismatch: " + name + " " + actual
                    + " vs AvroParquetReader " + ref);
        }
        System.out.printf("  OK  %s%n", name);
    }

    private static void checkSameAccess(String name, long named, long indexed) {
        if (named != indexed) {
            throw new IllegalStateException(name + ": named-access sum " + named
                    + " != indexed-access sum " + indexed);
        }
    }

    // The timed benchmarks run the *light* scan (full reconstruction + a cheap,
    // symmetric per-leaf touch), not the heavy correctness checksum. The heavy
    // fold's per-string `getBytes(UTF-8)` re-encoding is pure measurement
    // overhead — and asymmetric (it costs Avro's `Utf8.toString().getBytes()`
    // two allocations vs Hardwood's one), which would flatter Hardwood. The
    // checksum still runs once in `setup()` to prove both readers reconstruct
    // identical data; the timed loop just measures read + materialize.

    // Named access (by field name) is the idiomatic way to read nested records and
    // is the headline pair; indexed (positional) access is the secondary
    // power-user number. Both are published, mirroring the flat scan. Each uses the
    // default (all-cores) context; the single-thread engine-for-engine number comes
    // from the taskset-pinned pass (the JVM then sees one core).

    @Benchmark
    public long hardwoodRowReaderNamed() throws IOException {
        return hardwoodScan(true);
    }

    @Benchmark
    public long hardwoodRowReaderIndexed() throws IOException {
        return hardwoodScan(false);
    }

    @Benchmark
    public long avroParquetReaderNamed() throws IOException {
        return avroScan(true);
    }

    @Benchmark
    public long avroParquetReaderIndexed() throws IOException {
        return avroScan(false);
    }

    // ==================== Hardwood row reader ====================

    /// Hardwood row reader over the single file, default (all-cores) context. Each
    /// row is traversed in full — every nested struct, list, and map is recursed to
    /// its scalar leaves — so the record is fully reconstructed, matching what
    /// `AvroParquetReader` does eagerly. (Heavy checksum path, used by the gate.)
    private Checksum hardwoodRow() throws IOException {
        Checksum cs = new Checksum();
        try (ParquetFileReader reader = ParquetFileReader.open(inputFile);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                cs.rowCount++;
                foldStruct(rows, cs);
            }
        }
        return cs;
    }

    /// Fold every field of a struct-shaped accessor (the row itself or a nested
    /// [PqStruct]). Field order is irrelevant — the checksum is order-independent.
    private static void foldStruct(StructAccessor struct, Checksum cs) {
        int fieldCount = struct.getFieldCount();
        for (int i = 0; i < fieldCount; i++) {
            foldHardwood(struct.getValue(i), cs);
        }
    }

    /// Recurse a decoded Hardwood value: structs, lists, and maps descend; scalar
    /// leaves fold into the checksum. Nulls (absent fields, null elements) fold to
    /// nothing, matching the Avro side.
    private static void foldHardwood(Object value, Checksum cs) {
        if (value == null) {
            return;
        }
        if (value instanceof PqStruct struct) {
            foldStruct(struct, cs);
        }
        else if (value instanceof PqList list) {
            int size = list.size();
            for (int i = 0; i < size; i++) {
                foldHardwood(list.get(i), cs);
            }
        }
        else if (value instanceof PqMap map) {
            for (PqMap.Entry entry : map.getEntries()) {
                foldHardwood(entry.getKey(), cs);
                foldHardwood(entry.getValue(), cs);
            }
        }
        else {
            cs.leaf(value);
        }
    }

    /// Timed Hardwood scan: same full traversal as [#hardwoodRow], but each leaf
    /// gets the cheap [#touch] instead of the checksum fold. `getValue` still
    /// materializes every value, so this measures read + reconstruction without
    /// the per-string re-encoding overhead.
    private long hardwoodScan(boolean byName) throws IOException {
        long acc = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(inputFile);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                acc += scanStruct(rows, byName);
            }
        }
        return acc;
    }

    /// Walk a struct's fields, by name (the ergonomic path — each `getValue(name)`
    /// resolves the field by name) or by position, recursing into each value.
    private static long scanStruct(StructAccessor struct, boolean byName) {
        long acc = 0;
        int fieldCount = struct.getFieldCount();
        if (byName) {
            for (int i = 0; i < fieldCount; i++) {
                acc += scanHardwood(struct.getValue(struct.getFieldName(i)), byName);
            }
        } else {
            for (int i = 0; i < fieldCount; i++) {
                acc += scanHardwood(struct.getValue(i), byName);
            }
        }
        return acc;
    }

    private static long scanHardwood(Object value, boolean byName) {
        if (value == null) {
            return 0;
        }
        if (value instanceof PqStruct struct) {
            return scanStruct(struct, byName);
        }
        if (value instanceof PqList list) {
            long acc = 0;
            int size = list.size();
            for (int i = 0; i < size; i++) {
                acc += scanHardwood(list.get(i), byName);
            }
            return acc;
        }
        if (value instanceof PqMap map) {
            long acc = 0;
            for (PqMap.Entry entry : map.getEntries()) {
                acc += scanHardwood(entry.getKey(), byName) + scanHardwood(entry.getValue(), byName);
            }
            return acc;
        }
        return touch(value);
    }

    // ==================== parquet-java AvroParquetReader ====================

    /// parquet-java record path: `AvroParquetReader` materializing every record,
    /// each traversed in full and folded the same way as the Hardwood side.
    private Checksum avro() throws IOException {
        Checksum cs = new Checksum();
        Configuration conf = new Configuration();
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString());
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(HadoopInputFile.fromPath(hPath, conf)).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                cs.rowCount++;
                foldRecord(record, cs);
            }
        }
        return cs;
    }

    private static void foldRecord(GenericRecord record, Checksum cs) {
        List<Schema.Field> fields = record.getSchema().getFields();
        int fieldCount = fields.size();
        for (int i = 0; i < fieldCount; i++) {
            foldAvro(record.get(i), cs);
        }
    }

    /// Recurse a decoded Avro value: records, maps, and lists descend; scalar
    /// leaves fold into the checksum, mirroring [#foldHardwood].
    private static void foldAvro(Object value, Checksum cs) {
        if (value == null) {
            return;
        }
        if (value instanceof GenericRecord record) {
            foldRecord(record, cs);
        }
        else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                foldAvro(entry.getKey(), cs);
                foldAvro(entry.getValue(), cs);
            }
        }
        else if (value instanceof List<?> list) {
            for (Object element : list) {
                foldAvro(element, cs);
            }
        }
        else {
            cs.leaf(value);
        }
    }

    /// Timed Avro scan: same full traversal as [#avro], with the cheap [#touch]
    /// per leaf. `AvroParquetReader.read()` already materialized the whole
    /// record, so this walks it and forces each value to be read.
    private long avroScan(boolean byName) throws IOException {
        long acc = 0;
        Configuration conf = new Configuration();
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(FILE.toAbsolutePath().toString());
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(HadoopInputFile.fromPath(hPath, conf)).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                acc += scanAvro(record, byName);
            }
        }
        return acc;
    }

    private static long scanAvro(Object value, boolean byName) {
        if (value == null) {
            return 0;
        }
        if (value instanceof GenericRecord record) {
            long acc = 0;
            List<Schema.Field> fields = record.getSchema().getFields();
            int fieldCount = fields.size();
            if (byName) {
                for (int i = 0; i < fieldCount; i++) {
                    acc += scanAvro(record.get(fields.get(i).name()), byName);
                }
            } else {
                for (int i = 0; i < fieldCount; i++) {
                    acc += scanAvro(record.get(i), byName);
                }
            }
            return acc;
        }
        if (value instanceof Map<?, ?> map) {
            long acc = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                acc += scanAvro(entry.getKey(), byName) + scanAvro(entry.getValue(), byName);
            }
            return acc;
        }
        if (value instanceof List<?> list) {
            long acc = 0;
            for (Object element : list) {
                acc += scanAvro(element, byName);
            }
            return acc;
        }
        return touch(value);
    }

    /// A cheap, allocation-free read of a scalar leaf, shared by both scans to
    /// keep the per-leaf work symmetric. Each branch reads an O(1) field of the
    /// already-materialized value (so the JIT cannot elide the materialization)
    /// without re-encoding or allocating. Handles both Hardwood's leaf types
    /// (`String`, boxed numbers, `byte[]`) and Avro's (`Utf8`/`CharSequence`,
    /// boxed numbers, `ByteBuffer`, `GenericFixed`); `String.length()` and
    /// `Utf8.length()` are both O(1) field reads.
    private static long touch(Object value) {
        if (value instanceof CharSequence text) {
            return text.length();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof ByteBuffer buffer) {
            return buffer.remaining();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof GenericFixed fixed) {
            return fixed.bytes().length;
        }
        return 1;
    }

    // ==================== Checksum ====================

    /// A representation-stable, order-independent fold over the scalar leaves of a
    /// reconstructed record tree, designed to be identical across Hardwood's
    /// `Pq*` value types and Avro's `GenericRecord` / `List` / `Map`:
    ///
    /// - `leafCount` — the number of non-null scalar leaves traversed. Folds
    ///   every leaf regardless of type, so it gates the **structure** (field
    ///   counts, list sizes, map sizes, null vs empty) exactly and independently
    ///   of value representation.
    /// - `lengthSum` — the summed UTF-8 byte length of string leaves and byte
    ///   length of binary leaves. Exact; matches whether a string arrives as
    ///   `String` (Hardwood) or `Utf8` (Avro) and a binary as `byte[]` (Hardwood)
    ///   or `ByteBuffer` / `GenericFixed` (Avro).
    /// - `numericHash` — the XOR of `Double.doubleToLongBits(doubleValue())` over
    ///   numeric and boolean leaves. Coercing through `doubleValue()` makes a
    ///   `Float` (Avro's representation of a FLOAT column) and a `Double`
    ///   (Hardwood's) agree exactly, and XOR is order-independent.
    ///
    /// All three are exact integers, so the gate is exact equality — no tolerance.
    /// Long-tail logical leaves (date / time / decimal / uuid) are counted in
    /// `leafCount` but not value-folded; Overture places has none, and `leafCount`
    /// still gates their structure if a future dataset introduces them.
    static final class Checksum {
        long rowCount;
        long leafCount;
        long lengthSum;
        long numericHash;

        void leaf(Object value) {
            leafCount++;
            if (value instanceof Number number) {
                numericHash ^= Double.doubleToLongBits(number.doubleValue());
            }
            else if (value instanceof Boolean bool) {
                numericHash ^= Double.doubleToLongBits(bool ? 1.0 : 0.0);
            }
            else if (value instanceof String string) {
                lengthSum += string.getBytes(StandardCharsets.UTF_8).length;
            }
            else if (value instanceof CharSequence sequence) {
                lengthSum += sequence.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            else if (value instanceof byte[] bytes) {
                lengthSum += bytes.length;
            }
            else if (value instanceof ByteBuffer buffer) {
                lengthSum += buffer.remaining();
            }
            else if (value instanceof GenericFixed fixed) {
                lengthSum += fixed.bytes().length;
            }
            // Other leaf types are counted (leafCount) but not value-folded.
        }

        boolean matches(Checksum other) {
            return rowCount == other.rowCount
                    && leafCount == other.leafCount
                    && lengthSum == other.lengthSum
                    && numericHash == other.numericHash;
        }

        @Override
        public String toString() {
            return "rows=" + rowCount + " leaves=" + leafCount
                    + " lengthSum=" + lengthSum + " numericHash=" + numericHash;
        }
    }

    public static void main(String[] args) throws Exception {
        // Gate-check mode: verify the record pair agrees, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            NestedScanBenchmark gate = new NestedScanBenchmark();
            gate.loadData();
            gate.runGate();
            return;
        }
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(NestedScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(2))
                .measurementTime(TimeValue.seconds(2))
                .forks(Integer.getInteger("perf.forks", 1));
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the same file path, etc.
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

        // Derive M rows/s and MB/s over the single Overture file.
        List<Path> files = List.of(FILE);
        long rows = BenchReport.totalRows(files);
        long bytes = BenchReport.totalBytes(files);
        BenchReport.printFullScanThroughput(results, NestedScanBenchmark.class, rows, bytes);
        BenchReport.appendThroughputTsv(results, NestedScanBenchmark.class, rows, bytes);
    }
}
