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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.SplittableRandom;

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

/// Generates the wide-scan fixture: [#COLUMNS] required `FLOAT` columns of [#ROWS] rows in one
/// row group, one page per column chunk: one time series per column, an hour per row over a
/// quarter. Each column is a random walk rounded to two decimals, seeded per column, stored
/// `BYTE_STREAM_SPLIT` without a dictionary and compressed with SNAPPY.
///
/// Written by parquet-java. Cached across runs, keyed on the path only.
final class WideFileGenerator {

    static final int COLUMNS = 10_000;
    static final int ROWS = 2_190;

    private static final MessageType SCHEMA = schema();

    private WideFileGenerator() {
    }

    static String columnName(int i) {
        return "s" + i;
    }

    /// Writes the file if it is not already present.
    static void ensure(Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-column, %,d-row wide file at %s...%n", COLUMNS, ROWS, file);
        float[][] values = values();
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (ParquetWriter<Integer> writer = new Builder(new LocalOutputFile(partial), values)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withDictionaryEncoding(false)
                .withByteStreamSplitEncoding(true)
                .withRowGroupRowCountLimit(ROWS)
                .withRowGroupSize(1L << 30)
                .withPageRowCountLimit(ROWS)
                .withPageSize(1 << 20)
                .withPageWriteChecksumEnabled(false)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (int row = 0; row < ROWS; row++) {
                writer.write(row);
            }
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("Generated %s (%,d bytes)%n", file, Files.size(file));
    }

    /// Per column, its random walk.
    private static float[][] values() {
        float[][] values = new float[COLUMNS][ROWS];
        for (int c = 0; c < COLUMNS; c++) {
            SplittableRandom random = new SplittableRandom(c);
            double v = 0;
            for (int r = 0; r < ROWS; r++) {
                v += random.nextGaussian();
                values[c][r] = Math.round(v * 100) / 100f;
            }
        }
        return values;
    }

    private static MessageType schema() {
        Types.MessageTypeBuilder builder = Types.buildMessage();
        for (int i = 0; i < COLUMNS; i++) {
            builder.required(PrimitiveTypeName.FLOAT).named(columnName(i));
        }
        return builder.named("wide");
    }

    private static final class Builder extends ParquetWriter.Builder<Integer, Builder> {

        private final float[][] values;

        Builder(OutputFile file, float[][] values) {
            super(file);
            this.values = values;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        protected WriteSupport<Integer> getWriteSupport(Configuration conf) {
            return new RowWriteSupport(values);
        }
    }

    /// Writes the row whose index it is given.
    private static final class RowWriteSupport extends WriteSupport<Integer> {

        private final float[][] values;
        private RecordConsumer consumer;

        RowWriteSupport(float[][] values) {
            this.values = values;
        }

        @Override
        public WriteContext init(Configuration configuration) {
            return new WriteContext(SCHEMA, new HashMap<>());
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            this.consumer = recordConsumer;
        }

        @Override
        public void write(Integer boxedRow) {
            int row = boxedRow;
            consumer.startMessage();
            for (int c = 0; c < COLUMNS; c++) {
                String name = columnName(c);
                consumer.startField(name, c);
                consumer.addFloat(values[c][row]);
                consumer.endField(name, c);
            }
            consumer.endMessage();
        }
    }
}
