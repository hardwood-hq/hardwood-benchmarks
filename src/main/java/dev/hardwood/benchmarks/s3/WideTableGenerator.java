/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

/// Generates the wide-table fixture: [#COLUMNS] numeric columns in [#ROW_GROUPS] row groups of
/// [#ROWS_PER_GROUP] rows, each column chunk cut into pages of [#PAGE_ROWS] rows, so the page
/// index (a column index and an offset index entry per page, per column, per row group) is large
/// against the data it describes.
///
/// Column 0, `seq`, counts `0 .. ROWS_PER_GROUP - 1` in every row group: its row-group statistics
/// span the whole range, so no row group is pruned on them, while its column index proves all but
/// the pages holding small values non-matching. A range predicate on `seq` therefore consults the
/// page index of every row group. The other columns are low-cardinality `INT64` (even positions)
/// and `DOUBLE` (odd positions) values, dictionary-encoded, which keeps the data small.
///
/// Written by parquet-java with SNAPPY and its default column and offset indexes. Cached across
/// runs, keyed on the path only.
final class WideTableGenerator {

    static final int COLUMNS = 200;
    static final int ROW_GROUPS = 40;
    static final int ROWS_PER_GROUP = 20_000;
    static final int PAGE_ROWS = 1_000;
    static final String FILTER_COLUMN = "seq";

    private static final String[] NAMES = names();
    private static final MessageType SCHEMA = schema();

    private WideTableGenerator() {
    }

    /// The name of column `i`: `seq` for column 0, `c001` .. `c199` for the others.
    static String columnName(int i) {
        return NAMES[i];
    }

    private static String[] names() {
        String[] names = new String[COLUMNS];
        names[0] = FILTER_COLUMN;
        for (int i = 1; i < COLUMNS; i++) {
            names[i] = String.format("c%03d", i);
        }
        return names;
    }

    static long rows() {
        return (long) ROW_GROUPS * ROWS_PER_GROUP;
    }

    /// Writes the file if it is not already present.
    static void ensure(Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %d-column, %d-row-group wide table at %s...%n", COLUMNS, ROW_GROUPS, file);
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (ParquetWriter<Long> writer = new Builder(new LocalOutputFile(partial))
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupRowCountLimit(ROWS_PER_GROUP)
                .withRowGroupSize(1L << 30)
                .withPageRowCountLimit(PAGE_ROWS)
                .withPageWriteChecksumEnabled(false)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (long row = 0; row < rows(); row++) {
                writer.write(row);
            }
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("Generated %s (%,d bytes)%n", file, Files.size(file));
    }

    private static MessageType schema() {
        Types.MessageTypeBuilder builder = Types.buildMessage();
        for (int i = 0; i < COLUMNS; i++) {
            PrimitiveTypeName type = i % 2 == 0 ? PrimitiveTypeName.INT64 : PrimitiveTypeName.DOUBLE;
            builder.required(type).named(columnName(i));
        }
        return builder.named("wide");
    }

    /// Column `i`'s value in `row`: sixteen distinct values per column, moving every seven rows.
    private static long filler(long row, int i) {
        return ((row / 7) * 31 + i * 17) & 15;
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
            consumer.startField(FILTER_COLUMN, 0);
            consumer.addLong(row % ROWS_PER_GROUP);
            consumer.endField(FILTER_COLUMN, 0);
            for (int i = 1; i < COLUMNS; i++) {
                String name = columnName(i);
                consumer.startField(name, i);
                if (i % 2 == 0) {
                    consumer.addLong(filler(row, i));
                }
                else {
                    consumer.addDouble(filler(row, i) * 0.25);
                }
                consumer.endField(name, i);
            }
            consumer.endMessage();
        }
    }
}
