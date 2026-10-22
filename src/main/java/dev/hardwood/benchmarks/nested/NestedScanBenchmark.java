/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.nested;
import dev.hardwood.HardwoodContext;
import dev.hardwood.benchmarks.BenchContext;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.BinaryWindows;

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
import dev.hardwood.Validity;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.LayerKind;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqIntList;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqLongList;
import dev.hardwood.row.PqDoubleList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;
import dev.hardwood.row.StructAccessor;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.SchemaNode;

/// Nested-read benchmark (benchmark #2): a full read of the single-file Overture
/// Maps places dataset — deeply nested struct / list / map — through Hardwood's
/// row reader and column readers, against parquet-java's `AvroParquetReader`.
/// The file is single, so there is no cross-file asymmetry and the parallel
/// advantage is purely within-file concurrent decode.
///
/// Each contender **reads every leaf** of every record, so none skips work
/// another performs, and folds it into a representation-stable checksum
/// ([Checksum]) in the gate, so all of them are proven to read the same data
/// before any timing counts.
///
/// - `hardwoodRowReaderTyped` reads each record the way an application with a
///   known schema does: field positions and types are resolved from the file
///   schema once per read, and every value is read through its typed getter
///   (`getLong`, `getString`, `getStruct`, a list's `strings()`, …).
/// - `hardwoodColumnReaders` reads every leaf column through [ColumnReaders],
///   batch by batch, from its value arrays, layer offsets and validity.
/// - `avroParquetReaderNamed` / `Indexed` walk the `GenericRecord` that
///   `AvroParquetReader` materializes, by field name and by position.
///
/// parquet-java is single-threaded by construction; the single-core Hardwood
/// number comes from the taskset-pinned pass, which `run-nested.sh` runs after the
/// all-cores one.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class NestedScanBenchmark {

    private static final Path FILE = Path.of(System.getProperty(
            "perf.file", "target/overture-maps-data/overture_places.zstd.parquet"));

    /// Rows each scan reads before stopping; `0` (the default) reads the whole file.
    /// A capped run reads a *prefix* — the leading row groups, not a sample across
    /// them — so it is a cheaper stand-in for the corpus rather than a smaller
    /// version of it, and absolute figures from one describe that prefix. Every
    /// contender and the gate stop at the same count, so a capped run compares
    /// like for like.
    private static final long MAX_ROWS = resolveMaxRows();

    private static long resolveMaxRows() {
        long rows = Long.getLong("perf.rows", 0L);
        if (rows < 0) {
            throw new IllegalArgumentException("perf.rows must not be negative: " + rows);
        }
        return rows == 0 ? Long.MAX_VALUE : rows;
    }

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
        check("Hardwood row reader, typed", hardwoodRowTyped(), ref);
        check("Hardwood column readers", hardwoodColumns(), ref);
        // Named access must read the same values as indexed; the timed loop uses
        // the light per-leaf touch, whose sum is order-independent, so equal sums
        // prove the named path resolves the same fields the indexed path does.
        checkSameAccess("AvroParquetReader", avroScan(true), avroScan(false));
        System.out.println("Gate passed — every contender reads identical data; Avro's named and indexed access agree.");
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

    // Each Hardwood contender uses the default (all-cores) context; the
    // single-thread engine-for-engine number comes from the taskset-pinned pass
    // (the JVM then sees one core).

    @Benchmark
    public long hardwoodRowReaderTyped() throws IOException {
        return hardwoodTypedScan();
    }

    @Benchmark
    public long hardwoodColumnReaders() throws IOException {
        return hardwoodColumnScan();
    }

    @Benchmark
    public long avroParquetReaderNamed() throws IOException {
        return avroScan(true);
    }

    @Benchmark
    public long avroParquetReaderIndexed() throws IOException {
        return avroScan(false);
    }

    // ==================== Hardwood row reader, typed ====================

    /// What a typed read does with a field: the kind of its value, resolved once
    /// per read from the file schema, and for a group the reads of its parts.
    private enum Kind { INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING, BINARY, STRUCT, LIST, MAP }

    private record Field(Kind kind, Field[] fields, Field element, Field key, Field value) {

        static Field of(SchemaNode node) {
            return switch (node) {
                case SchemaNode.PrimitiveNode primitive -> new Field(kindOf(primitive), null, null, null, null);
                case SchemaNode.GroupNode group when group.isList() ->
                        new Field(Kind.LIST, null, of(group.getListElement()), null, null);
                case SchemaNode.GroupNode group when group.isMap() ->
                        new Field(Kind.MAP, null, null, of(group.getMapKey()), of(group.getMapValue()));
                case SchemaNode.GroupNode group -> new Field(Kind.STRUCT, fieldsOf(group), null, null, null);
            };
        }

        static Field[] fieldsOf(SchemaNode.GroupNode group) {
            return group.children().stream().map(Field::of).toArray(Field[]::new);
        }

        private static Kind kindOf(SchemaNode.PrimitiveNode primitive) {
            return switch (primitive.type()) {
                case INT32 -> Kind.INT;
                case INT64 -> Kind.LONG;
                case FLOAT -> Kind.FLOAT;
                case DOUBLE -> Kind.DOUBLE;
                case BOOLEAN -> Kind.BOOLEAN;
                case BYTE_ARRAY -> primitive.logicalType() instanceof LogicalType.StringType ? Kind.STRING : Kind.BINARY;
                case FIXED_LEN_BYTE_ARRAY -> Kind.BINARY;
                default -> throw new IllegalStateException("No typed read for " + primitive);
            };
        }
    }

    /// Timed typed row scan: every leaf is read through its typed getter, with
    /// the same cheap per-leaf touch as the other scans.
    private long hardwoodTypedScan() throws IOException {
        long acc = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             RowReader rows = reader.rowReader()) {
            Field[] fields = Field.fieldsOf(reader.getFileSchema().getRootNode());
            long read = 0;
            while (rows.hasNext() && read < MAX_ROWS) {
                rows.next();
                read++;
                acc += scanStruct(rows, fields, null);
            }
        }
        return acc;
    }

    /// The typed row scan folding every leaf into a [Checksum], for the gate.
    private Checksum hardwoodRowTyped() throws IOException {
        Checksum cs = new Checksum();
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             RowReader rows = reader.rowReader()) {
            Field[] fields = Field.fieldsOf(reader.getFileSchema().getRootNode());
            while (rows.hasNext() && cs.rowCount < MAX_ROWS) {
                rows.next();
                cs.rowCount++;
                scanStruct(rows, fields, cs);
            }
        }
        return cs;
    }

    /// Reads every field of a struct-shaped accessor by position, returning the
    /// leaves' touch sum and folding them into `cs` when it is not `null`.
    private static long scanStruct(StructAccessor struct, Field[] fields, Checksum cs) {
        long acc = 0;
        for (int i = 0; i < fields.length; i++) {
            acc += scanField(struct, i, fields[i], cs);
        }
        return acc;
    }

    private static long scanField(StructAccessor struct, int i, Field field, Checksum cs) {
        return switch (field.kind()) {
            case STRUCT -> {
                PqStruct value = struct.getStruct(i);
                yield value == null ? 0 : scanStruct(value, field.fields(), cs);
            }
            case LIST -> {
                PqList value = struct.getList(i);
                yield value == null ? 0 : scanList(value, field.element(), cs);
            }
            case MAP -> {
                PqMap value = struct.getMap(i);
                yield value == null ? 0 : scanMap(value, field, cs);
            }
            default -> struct.isNull(i) ? 0 : scanLeaf(struct, i, field.kind(), cs);
        };
    }

    private static long scanLeaf(StructAccessor struct, int i, Kind kind, Checksum cs) {
        return switch (kind) {
            case INT -> number(struct.getInt(i), cs);
            case LONG -> number(struct.getLong(i), cs);
            case FLOAT -> number(struct.getFloat(i), cs);
            case DOUBLE -> number(struct.getDouble(i), cs);
            case BOOLEAN -> number(struct.getBoolean(i) ? 1 : 0, cs);
            case STRING -> string(struct.getString(i), cs);
            case BINARY -> bytes(struct.getBinary(i).length, cs);
            default -> throw new IllegalStateException("Not a leaf: " + kind);
        };
    }

    private static long scanList(PqList list, Field element, Checksum cs) {
        long acc = 0;
        switch (element.kind()) {
            case STRUCT -> {
                for (PqStruct value : list.structs()) {
                    acc += value == null ? 0 : scanStruct(value, element.fields(), cs);
                }
            }
            case LIST -> {
                for (PqList value : list.lists()) {
                    acc += value == null ? 0 : scanList(value, element.element(), cs);
                }
            }
            case MAP -> {
                for (PqMap value : list.maps()) {
                    acc += value == null ? 0 : scanMap(value, element, cs);
                }
            }
            case STRING -> {
                for (String value : list.strings()) {
                    acc += value == null ? 0 : string(value, cs);
                }
            }
            case BINARY -> {
                for (byte[] value : list.binaries()) {
                    acc += value == null ? 0 : bytes(value.length, cs);
                }
            }
            case INT -> {
                PqIntList values = list.ints();
                for (int j = 0; j < values.size(); j++) {
                    acc += values.isNull(j) ? 0 : number(values.get(j), cs);
                }
            }
            case LONG -> {
                PqLongList values = list.longs();
                for (int j = 0; j < values.size(); j++) {
                    acc += values.isNull(j) ? 0 : number(values.get(j), cs);
                }
            }
            case DOUBLE -> {
                PqDoubleList values = list.doubles();
                for (int j = 0; j < values.size(); j++) {
                    acc += values.isNull(j) ? 0 : number(values.get(j), cs);
                }
            }
            case FLOAT -> {
                for (Float value : list.floats()) {
                    acc += value == null ? 0 : number(value, cs);
                }
            }
            case BOOLEAN -> {
                for (Boolean value : list.booleans()) {
                    acc += value == null ? 0 : number(value ? 1 : 0, cs);
                }
            }
        }
        return acc;
    }

    private static long scanMap(PqMap map, Field field, Checksum cs) {
        long acc = 0;
        for (PqMap.Entry entry : map.getEntries()) {
            acc += switch (field.key().kind()) {
                case STRING -> string(entry.getStringKey(), cs);
                case INT -> number(entry.getIntKey(), cs);
                case LONG -> number(entry.getLongKey(), cs);
                case BINARY -> bytes(entry.getBinaryKey().length, cs);
                default -> throw new IllegalStateException("No typed read for map key " + field.key().kind());
            };
            if (entry.isValueNull()) {
                continue;
            }
            Field value = field.value();
            acc += switch (value.kind()) {
                case STRING -> string(entry.getStringValue(), cs);
                case BINARY -> bytes(entry.getBinaryValue().length, cs);
                case INT -> number(entry.getIntValue(), cs);
                case LONG -> number(entry.getLongValue(), cs);
                case FLOAT -> number(entry.getFloatValue(), cs);
                case DOUBLE -> number(entry.getDoubleValue(), cs);
                case BOOLEAN -> number(entry.getBooleanValue() ? 1 : 0, cs);
                case STRUCT -> scanStruct(entry.getStructValue(), value.fields(), cs);
                case LIST -> scanList(entry.getListValue(), value.element(), cs);
                case MAP -> scanMap(entry.getMapValue(), value, cs);
            };
        }
        return acc;
    }

    /// A numeric or boolean leaf: its value as the touch, folded as a double.
    private static long number(double value, Checksum cs) {
        if (cs != null) {
            cs.number(value);
        }
        return (long) value;
    }

    /// A string leaf: its length as the touch, folded as its UTF-8 byte length.
    private static long string(String value, Checksum cs) {
        if (cs != null) {
            cs.leaf(value);
        }
        return value.length();
    }

    /// A binary leaf: its length as both the touch and the fold.
    private static long bytes(int length, Checksum cs) {
        if (cs != null) {
            cs.bytes(length);
        }
        return length;
    }

    // ==================== Hardwood column readers ====================

    /// Timed column scan: every leaf column through [ColumnReaders], each batch's
    /// values touched from their arrays, skipping nulls by the leaf validity.
    private long hardwoodColumnScan() throws IOException {
        long acc = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             ColumnReaders columns = reader.columnReaders(ColumnProjection.all())) {
            long read = 0;
            while (read < MAX_ROWS && columns.nextBatch()) {
                int records = (int) Math.min(columns.getRecordCount(), MAX_ROWS - read);
                read += records;
                for (int c = 0; c < columns.getColumnCount(); c++) {
                    acc += scanColumn(columns.getColumnReader(c), records, null);
                }
            }
        }
        return acc;
    }

    /// The column scan folding every leaf into a [Checksum], for the gate.
    private Checksum hardwoodColumns() throws IOException {
        Checksum cs = new Checksum();
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(inputFile, context);
             ColumnReaders columns = reader.columnReaders(ColumnProjection.all())) {
            while (cs.rowCount < MAX_ROWS && columns.nextBatch()) {
                int records = (int) Math.min(columns.getRecordCount(), MAX_ROWS - cs.rowCount);
                cs.rowCount += records;
                for (int c = 0; c < columns.getColumnCount(); c++) {
                    scanColumn(columns.getColumnReader(c), records, cs);
                }
            }
        }
        return cs;
    }

    /// Reads the leaf values of the batch's first `records` records.
    private static long scanColumn(ColumnReader column, int records, Checksum cs) {
        int values = leafValuesOf(column, records);
        Validity validity = column.getLeafValidity();
        long acc = 0;
        switch (column.getColumnSchema().type()) {
            case INT32 -> {
                int[] v = column.getInts();
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : number(v[i], cs);
                }
            }
            case INT64 -> {
                long[] v = column.getLongs();
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : number(v[i], cs);
                }
            }
            case FLOAT -> {
                float[] v = column.getFloats();
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : number(v[i], cs);
                }
            }
            case DOUBLE -> {
                double[] v = column.getDoubles();
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : number(v[i], cs);
                }
            }
            case BOOLEAN -> {
                boolean[] v = column.getBooleans();
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : number(v[i] ? 1 : 0, cs);
                }
            }
            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> {
                BinaryWindows binary = BinaryWindows.of(column);
                for (int i = 0; i < values; i++) {
                    acc += validity.isNull(i) ? 0 : bytes(binary.end(i) - binary.start(i), cs);
                }
            }
            default -> throw new IllegalStateException("No column read for " + column.getColumnSchema());
        }
        return acc;
    }

    /// The number of leaf values the batch's first `records` records hold: the
    /// record index followed down the repeated layers' offsets.
    private static int leafValuesOf(ColumnReader column, int records) {
        if (records == column.getRecordCount()) {
            return column.getValueCount();
        }
        int index = records;
        for (int layer = 0; layer < column.getLayerCount(); layer++) {
            if (column.getLayerKind(layer) == LayerKind.REPEATED) {
                index = column.getLayerOffsets(layer)[index];
            }
        }
        return index;
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
            while (cs.rowCount < MAX_ROWS && (record = reader.read()) != null) {
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
            long read = 0;
            while (read < MAX_ROWS && (record = reader.read()) != null) {
                read++;
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

    /// A cheap, allocation-free read of an Avro scalar leaf, matching the touch
    /// the Hardwood scans give each typed leaf. Each branch reads an O(1) field of
    /// the already-materialized value (so the JIT cannot elide the
    /// materialization) without re-encoding or allocating: `Utf8`/`CharSequence`,
    /// boxed numbers, `ByteBuffer`, `GenericFixed`; `Utf8.length()` is an O(1)
    /// field read.
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

        /// A numeric or boolean leaf read through a typed getter, folded as
        /// [#leaf(Object)] folds its boxed form.
        void number(double value) {
            leafCount++;
            numericHash ^= Double.doubleToLongBits(value);
        }

        /// A binary leaf of `length` bytes read through a typed getter.
        void bytes(int length) {
            leafCount++;
            lengthSum += length;
        }

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
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
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

        // Derive M rows/s and MB/s over the single Overture file, and record the
        // dataset in the meta sidecar the charts and the archived run read. The
        // release is part of the provenance: the STAC catalog serves only the most
        // recent releases, so the file behind a published number is not retrievable
        // once its release has rotated out.
        List<Path> files = List.of(FILE);
        long fileRows = BenchReport.totalRows(files);
        long bytes = BenchReport.totalBytes(files);
        // A capped run reads a prefix, so the throughput denominators are the rows
        // actually read and the share of the file they stand for — not the whole
        // corpus, which would inflate every derived figure by the ratio between them.
        long rows = Math.min(fileRows, MAX_ROWS);
        if (rows < fileRows) {
            bytes = Math.round((double) bytes * rows / fileRows);
        }
        BenchReport.writeRunParams(rows, bytes, "release", OvertureMapsDownloader.releaseOf(FILE),
                BenchReport.leafComposition(files));
        BenchReport.printFullScanThroughput(results, NestedScanBenchmark.class, rows, bytes);
        BenchReport.appendThroughputTsv(results, NestedScanBenchmark.class, rows, bytes);
    }
}
