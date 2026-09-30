/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.values.bloomfilter.BloomFilter;
import org.apache.parquet.column.statistics.LongStatistics;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

/// Generates the bloom-lookup fixtures: a unique, unclustered `INT64` `key` and a `DOUBLE`
/// `value`, in row groups of [#ROWS_PER_GROUP] rows, with a bloom filter on `key`.
///
/// Keys come from the SplitMix64 finalizer, a bijection on 64 bits, so every key is distinct and
/// every row group's `[min, max]` spans nearly the whole range: statistics prune no row group for
/// an equality probe. Dictionary encoding is off for `key`, so only the bloom filter can drop a
/// row group. A bloom filter answers "maybe present" for some keys never written, so the absent
/// probe is chosen after writing: the first key of an index past the last row that every row
/// group's filter rejects. It and a present probe are stored next to the fixture
/// ([#probes]), and [#ensure] checks the fixture against them on every run.
///
/// Two shapes, which differ in how the bloom filters compare with Hardwood's page-index window
/// budget (16 MB):
///
/// | Fixture | Row groups | Filter per row group |
/// |---|---|---|
/// | [#SMALL] | 200 | sized for the row group's 20 000 keys at 1 % FPP (tens of KB) |
/// | [#LARGE] | 6 | 16 MB, larger than one window's budget |
///
/// Written by parquet-java with SNAPPY and its default column and offset indexes. Cached across
/// runs, keyed on the path only.
final class BloomLookupGenerator {

    static final String KEY_COLUMN = "key";
    static final String VALUE_COLUMN = "value";
    static final int ROWS_PER_GROUP = 20_000;
    private static final double FPP = 0.01;

    /// One fixture shape: its row groups, and the NDV and size cap its bloom filters are written with.
    record Shape(String name, int rowGroups, long ndv, int maxFilterBytes) {

        /// The fixture's path, naming every parameter it is written with, so a changed parameter
        /// writes a new file rather than reusing an old one.
        Path file() {
            return Path.of(String.format("target/bloom_lookup_%s_%drg_%dr_ndv%d_max%d.parquet", name, rowGroups,
                    ROWS_PER_GROUP, ndv, maxFilterBytes));
        }

        private Path probesFile() {
            return file().resolveSibling(file().getFileName() + ".probes");
        }

        long rows() {
            return (long) rowGroups * ROWS_PER_GROUP;
        }
    }

    static final Shape SMALL = new Shape("small", 200, ROWS_PER_GROUP, 1024 * 1024);
    static final Shape LARGE = new Shape("large", 6, 16L * 1024 * 1024 * 8, 16 * 1024 * 1024);

    private static final MessageType SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).named(KEY_COLUMN)
            .required(PrimitiveTypeName.DOUBLE).named(VALUE_COLUMN)
            .named("lookup");

    private BloomLookupGenerator() {
    }

    /// The key of row `i`: the SplitMix64 finalizer, a bijection, so distinct rows get distinct keys.
    static long key(long i) {
        long z = i + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /// The probes of a fixture: `absent`, a key no row holds and every bloom filter rejects, and
    /// `present`, the key of the middle row, which holds `presentValue`.
    record Probes(long absent, long present, double presentValue) {
    }

    /// The probes stored next to the fixture of `shape` by [#ensure].
    static Probes probes(Shape shape) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(shape.probesFile())) {
            p.load(in);
        }
        return new Probes(Long.parseLong(p.getProperty("absent")), Long.parseLong(p.getProperty("present")),
                Double.parseDouble(p.getProperty("presentValue")));
    }

    /// Writes the fixture of `shape` and its probes if they are not already present, then checks
    /// the fixture against the probes: no dictionary page and a bloom filter on every `key` chunk,
    /// the absent probe inside every row group's `[min, max]` and rejected by every filter.
    ///
    /// @throws IllegalStateException if the fixture does not hold
    static void ensure(Shape shape) throws IOException {
        Path file = shape.file();
        if (!Files.exists(file) || Files.size(file) == 0) {
            write(shape);
        }
        if (!Files.exists(shape.probesFile())) {
            writeProbes(shape);
        }
        check(shape, probes(shape));
    }

    private static void write(Shape shape) throws IOException {
        Path file = shape.file();
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %s bloom-lookup fixture (%d row groups) at %s...%n", shape.name(),
                shape.rowGroups(), file);
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (ParquetWriter<Long> writer = new Builder(new LocalOutputFile(partial))
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupRowCountLimit(ROWS_PER_GROUP)
                .withRowGroupSize(1L << 30)
                .withDictionaryEncoding(KEY_COLUMN, false)
                .withBloomFilterEnabled(KEY_COLUMN, true)
                .withBloomFilterNDV(KEY_COLUMN, shape.ndv())
                .withBloomFilterFPP(KEY_COLUMN, FPP)
                .withMaxBloomFilterBytes(shape.maxFilterBytes())
                .withPageWriteChecksumEnabled(false)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (long row = 0; row < shape.rows(); row++) {
                writer.write(row);
            }
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("Generated %s (%,d bytes)%n", file, Files.size(file));
    }

    /// Chooses the absent probe, the first key of an index past the last row that every filter
    /// rejects, and stores it with the present probe.
    private static void writeProbes(Shape shape) throws IOException {
        long absent = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(shape.file()))) {
            List<BloomFilter> filters = filters(reader);
            for (long i = shape.rows(); ; i++) {
                if (rejectedByAll(filters, key(i))) {
                    absent = key(i);
                    break;
                }
            }
        }
        long presentRow = shape.rows() / 2;
        Properties p = new Properties();
        p.setProperty("absent", Long.toString(absent));
        p.setProperty("present", Long.toString(key(presentRow)));
        p.setProperty("presentValue", Double.toString(value(presentRow)));
        try (OutputStream out = Files.newOutputStream(shape.probesFile())) {
            p.store(out, "Probes for " + shape.file().getFileName());
        }
    }

    private static void check(Shape shape, Probes probes) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(shape.file()))) {
            List<BlockMetaData> blocks = reader.getFooter().getBlocks();
            if (blocks.size() != shape.rowGroups()) {
                throw new IllegalStateException(shape.file() + ": " + blocks.size() + " row groups, expected "
                        + shape.rowGroups());
            }
            for (int rg = 0; rg < blocks.size(); rg++) {
                ColumnChunkMetaData key = keyChunk(blocks.get(rg));
                LongStatistics stats = (LongStatistics) key.getStatistics();
                if (key.hasDictionaryPage() || probes.absent() < stats.getMin() || probes.absent() > stats.getMax()) {
                    throw new IllegalStateException(shape.file() + ": row group " + rg
                            + " has a dictionary on key or does not cover the absent probe in its bounds");
                }
            }
            if (!rejectedByAll(filters(reader), probes.absent())) {
                throw new IllegalStateException(shape.file() + ": a bloom filter accepts the absent probe "
                        + probes.absent());
            }
        }
    }

    private static List<BloomFilter> filters(ParquetFileReader reader) throws IOException {
        List<BloomFilter> filters = new ArrayList<>();
        for (BlockMetaData block : reader.getFooter().getBlocks()) {
            BloomFilter filter = reader.readBloomFilter(keyChunk(block));
            if (filter == null) {
                throw new IllegalStateException("No bloom filter on key in a row group of " + reader.getFile());
            }
            filters.add(filter);
        }
        return filters;
    }

    private static boolean rejectedByAll(List<BloomFilter> filters, long probe) {
        for (BloomFilter filter : filters) {
            if (filter.findHash(filter.hash(probe))) {
                return false;
            }
        }
        return true;
    }

    private static ColumnChunkMetaData keyChunk(BlockMetaData block) {
        return block.getColumns().getFirst();
    }

    /// The value of row `i`: a uniform value in `[0, 100)` from 53 pseudorandom bits.
    static double value(long i) {
        return (key(i ^ 0x5deece66dL) >>> 11) * 0x1.0p-53 * 100.0;
    }

    private static final class Builder extends ParquetWriter.Builder<Long, Builder> {

        Builder(OutputFile file) {
            super(file);
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        protected WriteSupport<Long> getWriteSupport(Configuration conf) {
            return new RowWriteSupport();
        }
    }

    /// Writes the row whose index it is given.
    private static final class RowWriteSupport extends WriteSupport<Long> {

        private RecordConsumer consumer;

        @Override
        public WriteContext init(Configuration configuration) {
            return new WriteContext(SCHEMA, new HashMap<>());
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            this.consumer = recordConsumer;
        }

        @Override
        public void write(Long boxedRow) {
            long row = boxedRow;
            consumer.startMessage();
            consumer.startField(KEY_COLUMN, 0);
            consumer.addLong(key(row));
            consumer.endField(KEY_COLUMN, 0);
            consumer.startField(VALUE_COLUMN, 1);
            consumer.addDouble(value(row));
            consumer.endField(VALUE_COLUMN, 1);
            consumer.endMessage();
        }
    }
}
