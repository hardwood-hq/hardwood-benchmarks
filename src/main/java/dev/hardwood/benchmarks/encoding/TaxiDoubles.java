/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.encoding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.hardwood.InputFile;
import dev.hardwood.Validity;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

/// Every `DOUBLE` column of one month of NYC taxi trips, held in memory in batches of
/// [#BATCH_ROWS], the form the column writer takes. Fares, distances, tips, tolls and
/// surcharges: decimal values with two digits after the point, the data ALP targets.
final class TaxiDoubles {

    static final int BATCH_ROWS = 4096;

    final String[] names;
    final RepetitionType[] repetition;
    /// `values[column][batch]`, `nulls[column][batch]` (`null` for a `REQUIRED` column).
    final double[][][] values;
    final boolean[][][] nulls;
    final int rows;

    private TaxiDoubles(String[] names, RepetitionType[] repetition, double[][][] values, boolean[][][] nulls,
            int rows) {
        this.names = names;
        this.repetition = repetition;
        this.values = values;
        this.nulls = nulls;
        this.rows = rows;
    }

    /// Reads the first `maxRows` rows of every `DOUBLE` column of `file`.
    static TaxiDoubles load(Path file, int maxRows) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            List<ColumnSchema> columns = new ArrayList<>();
            for (ColumnSchema column : reader.getFileSchema().getColumns()) {
                if (column.type() == PhysicalType.DOUBLE) {
                    columns.add(column);
                }
            }
            int rows = (int) Math.min(maxRows, reader.getFileMetaData().numRows());
            int batches = (rows + BATCH_ROWS - 1) / BATCH_ROWS;
            String[] names = new String[columns.size()];
            RepetitionType[] repetition = new RepetitionType[columns.size()];
            double[][][] values = new double[columns.size()][][];
            boolean[][][] nulls = new boolean[columns.size()][][];
            for (int c = 0; c < columns.size(); c++) {
                ColumnSchema column = columns.get(c);
                names[c] = column.name();
                repetition[c] = column.repetitionType();
                double[] all = new double[rows];
                boolean[] allNulls = new boolean[rows];
                readColumn(reader, column.name(), all, allNulls);
                values[c] = new double[batches][];
                nulls[c] = repetition[c] == RepetitionType.REQUIRED ? null : new boolean[batches][];
                for (int b = 0; b < batches; b++) {
                    int from = b * BATCH_ROWS;
                    int to = Math.min(rows, from + BATCH_ROWS);
                    values[c][b] = Arrays.copyOfRange(all, from, to);
                    if (nulls[c] != null) {
                        nulls[c][b] = Arrays.copyOfRange(allNulls, from, to);
                    }
                }
            }
            return new TaxiDoubles(names, repetition, values, nulls, rows);
        }
    }

    private static void readColumn(ParquetFileReader reader, String name, double[] into, boolean[] nulls)
            throws IOException {
        int row = 0;
        try (ColumnReader column = reader.columnReader(name)) {
            while (row < into.length && column.nextBatch()) {
                double[] batch = column.getDoubles();
                Validity validity = column.getLeafValidity();
                int count = Math.min(column.getValueCount(), into.length - row);
                for (int i = 0; i < count; i++) {
                    nulls[row] = validity.isNull(i);
                    into[row] = nulls[row] ? 0 : batch[i];
                    row++;
                }
            }
        }
    }

    int batchCount() {
        return values[0].length;
    }

    FileSchema schema() {
        FileSchema.Builder builder = FileSchema.builder("taxi_doubles");
        for (int c = 0; c < names.length; c++) {
            builder.addColumn(names[c], PhysicalType.DOUBLE, repetition[c]);
        }
        return builder.build();
    }

    /// What a scan of the columns has to reproduce: per column, the sum of the present
    /// values' raw bits (order-insensitive, and blind to nothing a decode could get wrong
    /// value by value) and the null count.
    Checksum checksum() {
        Checksum sum = new Checksum(names.length);
        for (int c = 0; c < names.length; c++) {
            for (int b = 0; b < batchCount(); b++) {
                double[] batch = values[c][b];
                for (int i = 0; i < batch.length; i++) {
                    sum.add(c, nulls[c] != null && nulls[c][b][i], batch[i]);
                }
            }
        }
        return sum;
    }

    /// Per column, the summed raw bits of the present values and the count of null ones.
    static final class Checksum {

        final long[] bits;
        final long[] nullCount;

        Checksum(int columns) {
            bits = new long[columns];
            nullCount = new long[columns];
        }

        void add(int column, boolean isNull, double value) {
            if (isNull) {
                nullCount[column]++;
            }
            else {
                bits[column] += Double.doubleToRawLongBits(value);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Checksum that && Arrays.equals(bits, that.bits)
                    && Arrays.equals(nullCount, that.nullCount);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bits) * 31 + Arrays.hashCode(nullCount);
        }

        @Override
        public String toString() {
            return "bits=" + Arrays.toString(bits) + " nulls=" + Arrays.toString(nullCount);
        }
    }
}
