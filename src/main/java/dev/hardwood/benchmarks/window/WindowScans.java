/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.HadoopReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.schema.MessageType;

import dev.hardwood.benchmarks.filter.Scans;
import dev.hardwood.reader.FilterPredicate;

/// The time-window query shared by [TimeWindowBenchmark] and its gate: the
/// window's lower bound, its Hardwood predicate, the parquet-java reference scan,
/// and the file's row-group count.
public final class WindowScans {

    private static final Pattern WINDOW = Pattern.compile("last(\\d+)pct");

    private static final Configuration CONF = new Configuration();

    private WindowScans() {
    }

    /// Lower bound of `window` (`last<P>pct`, the most recent P % of the time range)
    /// over a file whose `event_time` runs `0 .. rows - 1`: `rows * (1 - P/100)`,
    /// so exactly `rows * P / 100` rows match (rounded down to whole rows).
    public static long windowStart(String window, long rows) {
        Matcher m = WINDOW.matcher(window);
        if (!m.matches()) {
            throw new IllegalArgumentException("window must be last<P>pct, e.g. last5pct: " + window);
        }
        int pct = Integer.parseInt(m.group(1));
        if (pct < 1 || pct > 100) {
            throw new IllegalArgumentException("window percentage must be in 1..100: " + window);
        }
        return rows - Math.multiplyExact(rows, pct) / 100;
    }

    /// The window query's predicate: `event_time >= from`.
    public static FilterPredicate predicate(long from) {
        return FilterPredicate.gtEq("event_time", from);
    }

    /// Reference for the gate: parquet-java's low-level column API over
    /// column-index-filtered row groups, reading `event_time` and `amount` and
    /// applying `event_time >= from` exactly per row. Shares no code with Hardwood.
    public static Scans.Result parquetJavaWindow(Path file, long from) throws IOException {
        org.apache.parquet.filter2.predicate.FilterPredicate pred =
                FilterApi.gtEq(FilterApi.longColumn("event_time"), from);
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        long count = 0;
        double sum = 0.0;
        ParquetReadOptions options = HadoopReadOptions.builder(CONF)
                .withRecordFilter(FilterCompat.get(pred))
                .build();
        try (ParquetFileReader reader = ParquetFileReader.open(HadoopInputFile.fromPath(hPath, CONF), options)) {
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
                        pages, new Scans.NoOpGroupConverter(), projection, createdBy);
                org.apache.parquet.column.ColumnReader et = store.getColumnReader(etCol);
                org.apache.parquet.column.ColumnReader amt = store.getColumnReader(amtCol);
                for (long i = 0; i < n; i++) {
                    long eventTime = et.getLong();
                    et.consume();
                    double value = amt.getDouble();
                    amt.consume();
                    if (eventTime >= from) {
                        sum += value;
                        count++;
                    }
                }
            }
        }
        return new Scans.Result(count, sum);
    }

    /// Number of row groups in `file`, read from its footer.
    public static int rowGroupCount(Path file) throws IOException {
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        try (ParquetFileReader reader = ParquetFileReader.open(HadoopInputFile.fromPath(hPath, CONF))) {
            return reader.getRowGroups().size();
        }
    }
}
