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
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.HadoopReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
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
/// benchmarks. Each pushes an equality predicate on the unique `key` column down
/// through the reader's row-group pruning and sums `value` over the matching rows,
/// returning the count + sum so callers can compare results and JMH can consume the
/// value (no dead-code elimination). Called against both the bloom-bearing file and
/// the statistics-only twin — same call, two files ⇒ isolates the bloom win.
public final class Scans {

    /// Aggregate result: rows that passed the filter, and the sum of `value`.
    public record Result(long count, double sum) {}

    /// Hadoop config for the parquet-java contender. Built once (a static field, not
    /// per call) so its construction stays out of the timed `parquetJavaEqLong`.
    private static final Configuration CONF = new Configuration();

    private Scans() {
    }

    /// Hardwood column reader with a pushed-down equality filter on the unique `key`
    /// column, summing `value` over the matching rows. On a file that carries a bloom
    /// filter on `key`, Hardwood consults it automatically during row-group pruning
    /// (there is no toggle) — an absent probe drops every row group, a present probe
    /// keeps only the single row group holding that key. On a file without a bloom
    /// filter the same probe falls back to statistics, which cannot drop anything
    /// (the keys being unclustered, every row group's range covers the probe), so the
    /// whole `key` column is scanned. Same call, two files ⇒ isolates the bloom win.
    public static Result hardwoodEqLong(Path file, long key) throws IOException {
        FilterPredicate filter = FilterPredicate.eq(LookupFileGenerator.PROBE_COLUMN, key);
        long count = 0;
        double sum = 0.0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             ColumnReader values = reader.buildColumnReader(LookupFileGenerator.PAYLOAD_COLUMN)
                     .filter(filter).build()) {
            while (values.nextBatch()) {
                int n = values.getRecordCount();
                double[] batch = values.getDoubles();
                for (int i = 0; i < n; i++) {
                    sum += batch[i];
                }
                count += n;
            }
        }
        return new Result(count, sum);
    }

    /// parquet-java's low-level column API with an equality predicate on `key`.
    /// `readNextFilteredRowGroup` applies parquet-java's own row-group pruning —
    /// statistics, dictionary, and (when the file has one) the bloom filter, which is
    /// enabled by default in `HadoopReadOptions` — before returning a row group; the
    /// exact `key == probe` residual then matches Hardwood's row-exact result. The
    /// head-to-head against [#hardwoodEqLong] on the bloom file compares the two bloom
    /// implementations decision-for-decision (the gate proves they agree). Because
    /// every key is distinct the column falls back to plain encoding, so on the
    /// no-bloom file parquet-java's dictionary filter cannot prune either — the only
    /// pruner that ever fires is the bloom filter.
    public static Result parquetJavaEqLong(Path file, long key) throws IOException {
        org.apache.parquet.filter2.predicate.FilterPredicate pred =
                FilterApi.eq(FilterApi.longColumn(LookupFileGenerator.PROBE_COLUMN), key);
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
            MessageType projection = new MessageType("lookup",
                    fileSchema.getType(LookupFileGenerator.PROBE_COLUMN),
                    fileSchema.getType(LookupFileGenerator.PAYLOAD_COLUMN));
            reader.setRequestedSchema(projection);
            ColumnDescriptor keyCol = projection.getColumnDescription(
                    new String[] { LookupFileGenerator.PROBE_COLUMN });
            ColumnDescriptor valCol = projection.getColumnDescription(
                    new String[] { LookupFileGenerator.PAYLOAD_COLUMN });
            String createdBy = reader.getFileMetaData().getCreatedBy();

            PageReadStore pages;
            while ((pages = reader.readNextFilteredRowGroup()) != null) {
                long n = pages.getRowCount();
                ColumnReadStoreImpl store = new ColumnReadStoreImpl(
                        pages, new NoOpGroupConverter(), projection, createdBy);
                org.apache.parquet.column.ColumnReader k = store.getColumnReader(keyCol);
                org.apache.parquet.column.ColumnReader v = store.getColumnReader(valCol);
                for (long i = 0; i < n; i++) {
                    long candidate = k.getLong();
                    k.consume();
                    double value = v.getDouble();
                    v.consume();
                    if (candidate == key) {
                        sum += value;
                        count++;
                    }
                }
            }
        }
        return new Result(count, sum);
    }

    /// Row-group count and the per-row-group encodings of the probe column. The gate
    /// uses this to prove the two preconditions the benchmark rests on are real and
    /// not merely configured: the corpus spans several row groups (so pruning has
    /// something to drop), and the unique key column fell back to plain encoding on
    /// its own, so no dictionary filter can prune.
    public static List<String> probeColumnEncodings(Path file) throws IOException {
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        List<String> perRowGroup = new ArrayList<>();
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, CONF))) {
            ParquetMetadata meta = reader.getFooter();
            for (BlockMetaData block : meta.getBlocks()) {
                for (ColumnChunkMetaData chunk : block.getColumns()) {
                    if (chunk.getPath().toDotString().equals(LookupFileGenerator.PROBE_COLUMN)) {
                        StringBuilder sb = new StringBuilder();
                        for (Encoding e : chunk.getEncodings()) {
                            sb.append(sb.length() == 0 ? "" : "+").append(e);
                        }
                        perRowGroup.add(sb.toString());
                    }
                }
            }
        }
        return perRowGroup;
    }

    /// Rows per row group for the probe column, for the gate's layout report.
    public static List<Long> rowGroupRowCounts(Path file) throws IOException {
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());
        List<Long> counts = new ArrayList<>();
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(hPath, CONF))) {
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                counts.add(block.getRowCount());
            }
        }
        return counts;
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
