/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.bloom;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.HadoopReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.MessageType;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;

/// The bloom-lookup read paths, shared by the correctness gate and the JMH
/// benchmarks. Each pushes an equality predicate on `total_amount` down through the
/// reader's row-group pruning and sums `trip_distance` over the matching rows,
/// returning the count + sum so callers can compare results and JMH can consume the
/// value (no dead-code elimination). Called against both the bloom-bearing file and
/// the statistics-only twin — same call, two files ⇒ isolates the bloom win.
public final class Scans {

    /// Aggregate result: rows that passed the filter, and the sum of `trip_distance`.
    public record Result(long count, double sum) {}

    /// Hadoop config for the parquet-java contender. Built once (a static field, not
    /// per call) so its construction stays out of the timed `parquetJavaEqDouble`.
    private static final Configuration CONF = new Configuration();

    private Scans() {
    }

    /// Hardwood column reader with a pushed-down equality filter on the
    /// high-cardinality `total_amount` column, summing `trip_distance` over the
    /// matching rows. On a file that carries a bloom filter on `total_amount`,
    /// Hardwood consults it automatically during row-group pruning (there is no
    /// toggle) — an absent probe drops every row group, a present probe keeps only
    /// the row group(s) holding it. On a file without a bloom filter the same probe
    /// falls back to statistics, which (the fares being unclustered, dictionary
    /// disabled) can drop nothing, so the whole `total_amount` column is scanned.
    /// Same call, two files ⇒ isolates the bloom win.
    public static Result hardwoodEqDouble(Path file, double amount) throws IOException {
        FilterPredicate filter = FilterPredicate.eq(TaxiBloomGenerator.PROBE_COLUMN, amount);
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             ColumnReader dist = reader.buildColumnReader(TaxiBloomGenerator.PAYLOAD_COLUMN)
                     .filter(filter).build()) {
            while (dist.nextBatch()) {
                int n = dist.getRecordCount();
                double[] values = dist.getDoubles();
                for (int i = 0; i < n; i++) {
                    sum += values[i];
                }
                count += n;
            }
        }
        return new Result(count, sum);
    }

    /// parquet-java's low-level column API with an equality predicate on
    /// `total_amount`. `readNextFilteredRowGroup` applies parquet-java's own
    /// row-group pruning — statistics, dictionary, and (when the file has one) the
    /// bloom filter, which is enabled by default in `HadoopReadOptions` — before
    /// returning a row group; the exact `total_amount == probe` residual then
    /// matches Hardwood's row-exact result. The head-to-head against
    /// [#hardwoodEqDouble] on the bloom file compares the two bloom implementations
    /// decision-for-decision (the gate proves they agree). The generator disables
    /// dictionary encoding on `total_amount`, so on the no-bloom file parquet-java's
    /// dictionary filter cannot prune either — the only pruner that ever fires is
    /// the bloom filter.
    public static Result parquetJavaEqDouble(Path file, double amount) throws IOException {
        org.apache.parquet.filter2.predicate.FilterPredicate pred =
                FilterApi.eq(FilterApi.doubleColumn(TaxiBloomGenerator.PROBE_COLUMN), amount);
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        long count = 0;
        double sum = 0.0;
        ParquetReadOptions options = HadoopReadOptions.builder(CONF)
                .withRecordFilter(FilterCompat.get(pred))
                .build();
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(
                             HadoopInputFile.fromPath(hPath, CONF), options)) {
            MessageType fileSchema = reader.getFileMetaData().getSchema();
            MessageType projection = new MessageType("taxi_lookup",
                    fileSchema.getType(TaxiBloomGenerator.PROBE_COLUMN),
                    fileSchema.getType(TaxiBloomGenerator.PAYLOAD_COLUMN));
            reader.setRequestedSchema(projection);
            ColumnDescriptor amtCol = projection.getColumnDescription(
                    new String[] { TaxiBloomGenerator.PROBE_COLUMN });
            ColumnDescriptor distCol = projection.getColumnDescription(
                    new String[] { TaxiBloomGenerator.PAYLOAD_COLUMN });
            String createdBy = reader.getFileMetaData().getCreatedBy();

            PageReadStore pages;
            while ((pages = reader.readNextFilteredRowGroup()) != null) {
                long n = pages.getRowCount();
                ColumnReadStoreImpl store = new ColumnReadStoreImpl(
                        pages, new NoOpGroupConverter(), projection, createdBy);
                org.apache.parquet.column.ColumnReader amt = store.getColumnReader(amtCol);
                org.apache.parquet.column.ColumnReader dist = store.getColumnReader(distCol);
                for (long i = 0; i < n; i++) {
                    double key = amt.getDouble();
                    amt.consume();
                    double value = dist.getDouble();
                    dist.consume();
                    if (key == amount) {
                        sum += value;
                        count++;
                    }
                }
            }
        }
        return new Result(count, sum);
    }

    /// No-op converter required by [ColumnReadStoreImpl]; we never assemble records.
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
}
