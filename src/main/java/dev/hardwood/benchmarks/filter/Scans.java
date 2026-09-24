/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.filter;

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
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

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
        return hardwoodFiltered(file, FilterPredicate.lt("event_time", threshold));
    }

    /// As [#hardwoodFiltered(Path, long)], for any predicate: reads only `amount`,
    /// yielding the values of the rows `filter` matches.
    public static Result hardwoodFiltered(Path file, FilterPredicate filter) throws IOException {
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

    /// Hardwood, same single column, **no predicate**. The control for
    /// [#hardwoodFiltered]: identical decode work, nothing filtered.
    public static Result hardwoodUnfiltered(Path file) throws IOException {
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             ColumnReader amt = reader.buildColumnReader("amount").build()) {
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

    /// Hardwood row reader, projecting only `amount` and filtering on `event_time`, a
    /// column outside the projection. The record-path counterpart of [#hardwoodFiltered].
    public static Result hardwoodRowReaderFiltered(Path file, long threshold) throws IOException {
        return hardwoodRowReaderFiltered(file, FilterPredicate.lt("event_time", threshold));
    }

    /// As [#hardwoodRowReaderFiltered(Path, long)], for any predicate: projects only
    /// `amount`, yielding the rows `filter` matches.
    public static Result hardwoodRowReaderFiltered(Path file, FilterPredicate filter) throws IOException {
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             RowReader rows = reader.buildRowReader()
                     .projection(ColumnProjection.columns("amount"))
                     .filter(filter)
                     .build()) {
            while (rows.hasNext()) {
                rows.next();
                sum += rows.getDouble(0);
                count++;
            }
        }
        return new Result(count, sum);
    }

    /// Hardwood row reader, same projection, **no predicate**. The control for
    /// [#hardwoodRowReaderFiltered].
    public static Result hardwoodRowReaderUnfiltered(Path file) throws IOException {
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             RowReader rows = reader.buildRowReader()
                     .projection(ColumnProjection.columns("amount"))
                     .build()) {
            while (rows.hasNext()) {
                rows.next();
                sum += rows.getDouble(0);
                count++;
            }
        }
        return new Result(count, sum);
    }

    /// parquet-java, **no predicate**, reading only `amount` — the like-for-like
    /// control against [#hardwoodUnfiltered]: one column, every row, no filtering.
    public static Result parquetJavaUnfiltered(Path file) throws IOException {
        return parquetJavaScan(file, false);
    }

    /// parquet-java, **no predicate**, reading `event_time` *and* `amount`. The
    /// difference against [#parquetJavaUnfiltered] is what the predicate column
    /// costs it — work Hardwood's filtered path never does.
    public static Result parquetJavaUnfilteredBothColumns(Path file) throws IOException {
        return parquetJavaScan(file, true);
    }

    private static Result parquetJavaScan(Path file, boolean readEventTime) throws IOException {
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        long count = 0;
        double sum = 0.0;
        ParquetReadOptions options = HadoopReadOptions.builder(CONF).build();
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(
                             HadoopInputFile.fromPath(hPath, CONF), options)) {
            MessageType fileSchema = reader.getFileMetaData().getSchema();
            MessageType projection = readEventTime
                    ? new MessageType("event", fileSchema.getType("event_time"), fileSchema.getType("amount"))
                    : new MessageType("event", fileSchema.getType("amount"));
            reader.setRequestedSchema(projection);
            ColumnDescriptor amtCol = projection.getColumnDescription(new String[] { "amount" });
            ColumnDescriptor etCol = readEventTime
                    ? projection.getColumnDescription(new String[] { "event_time" })
                    : null;
            String createdBy = reader.getFileMetaData().getCreatedBy();

            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                long n = pages.getRowCount();
                ColumnReadStoreImpl store = new ColumnReadStoreImpl(
                        pages, new NoOpGroupConverter(), projection, createdBy);
                org.apache.parquet.column.ColumnReader amt = store.getColumnReader(amtCol);
                org.apache.parquet.column.ColumnReader et =
                        etCol == null ? null : store.getColumnReader(etCol);
                for (long i = 0; i < n; i++) {
                    if (et != null) {
                        sum += et.getLong() * 0.0;
                        et.consume();
                    }
                    sum += amt.getDouble();
                    amt.consume();
                    count++;
                }
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
    /// Public so other benchmarks' low-level parquet-java scans can share it.
    public static final class NoOpGroupConverter extends GroupConverter {
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
