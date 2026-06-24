/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.HadoopReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.MessageType;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;

/// The contender read paths, shared by the correctness gate and the JMH
/// benchmarks. Each sums `amount` over the rows matching `event_time < threshold`
/// and returns the count + sum so callers can compare results and JMH can
/// consume the value (no dead-code elimination).
public final class Scans {

    /// Aggregate result: rows that passed the filter, and the sum of `amount`.
    public record Result(long count, double sum) {}

    /// Hadoop config for the parquet-java contender. Built once (a static field, not
    /// per call) so its construction stays out of the timed `parquetJavaFiltered`.
    private static final Configuration CONF = new Configuration();

    private Scans() {
    }

    /// Hardwood column reader with a pushed-down range filter, using the default
    /// (all-cores) context. The single-core number comes from running the whole
    /// JVM under `taskset`, not from a thread-capped context — see `run-filter.sh`.
    ///
    /// The natural form: read only `amount`, filter on `event_time`. Per the #624
    /// fix this is row-exact — the reader yields only matching `amount` values,
    /// with no client-side residual and without reading the predicate column.
    public static Result hardwoodFiltered(Path file, long threshold) throws IOException {
        FilterPredicate filter = FilterPredicate.lt("event_time", threshold);
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             ColumnReader amt = reader.buildColumnReader("amount").filter(filter).build()) {
            while (amt.nextBatch()) {
                int n = amt.getRecordCount();
                double[] values = amt.getDoubles();
                for (int i = 0; i < n; i++) {
                    sum += values[i];
                }
                count += n;
            }
        }
        return new Result(count, sum);
    }

    /// parquet-java's low-level column API over column-index-filtered row groups.
    /// That API is page-granular and has no native exact columnar filter, so we
    /// apply the exact predicate per row to match Hardwood's row-exact result.
    /// Single-threaded by construction.
    public static Result parquetJavaFiltered(Path file, long threshold) throws IOException {
        org.apache.parquet.filter2.predicate.FilterPredicate pred =
                FilterApi.lt(FilterApi.longColumn("event_time"), threshold);
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
            MessageType projection = new MessageType("event",
                    fileSchema.getType("event_time"), fileSchema.getType("amount"));
            reader.setRequestedSchema(projection);
            ColumnDescriptor etCol = projection.getColumnDescription(new String[] { "event_time" });
            ColumnDescriptor amtCol = projection.getColumnDescription(new String[] { "amount" });
            String createdBy = reader.getFileMetaData().getCreatedBy();

            PageReadStore pages;
            while ((pages = reader.readNextFilteredRowGroup()) != null) {
                long n = pages.getRowCount();
                ColumnReadStoreImpl store = new ColumnReadStoreImpl(
                        pages, new NoOpGroupConverter(), projection, createdBy);
                org.apache.parquet.column.ColumnReader et = store.getColumnReader(etCol);
                org.apache.parquet.column.ColumnReader amt = store.getColumnReader(amtCol);
                for (long i = 0; i < n; i++) {
                    long eventTime = et.getLong();
                    et.consume();
                    double value = amt.getDouble();
                    amt.consume();
                    if (eventTime < threshold) {
                        sum += value;
                        count++;
                    }
                }
            }
        }
        return new Result(count, sum);
    }

    /// **Ad-hoc reference (not published).** parquet-java's record path with its
    /// built-in exact filter: `AvroParquetReader.withFilter(FilterCompat.get(...))`.
    /// Unlike [#parquetJavaFiltered] (low-level column API + manual residual), this
    /// applies row-group/column-index page pruning **and** the exact per-record
    /// predicate internally, returning only matching records — at the cost of
    /// materializing a `GenericRecord` per surviving row. Kept to quantify the
    /// materialization overhead of the built-in filter against the columnar path.
    public static Result avroParquetFiltered(Path file, long threshold) throws IOException {
        org.apache.parquet.filter2.predicate.FilterPredicate pred =
                FilterApi.lt(FilterApi.longColumn("event_time"), threshold);
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        long count = 0;
        double sum = 0.0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                HadoopInputFile.fromPath(hPath, CONF))
                .withFilter(FilterCompat.get(pred))
                .build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                sum += (Double) record.get("amount");
                count++;
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
