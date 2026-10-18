/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.onebrc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.BinaryDictionary;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowGroupPredicate;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// The ways of computing the 1BRC aggregate over a measurements file through Hardwood's public
/// API. [OneBrcBenchmark] times each of them; [OneBrc] runs one as a program.
final class OneBrcScans {

    private static final ColumnProjection COLUMNS = ColumnProjection.columns("station", "measurement");

    private OneBrcScans() {
    }

    /// The column reader on one consumer thread, aggregating by dictionary entry id.
    static StationTable columnIds(Path file) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            return aggregate(reader, null);
        }
    }

    /// The column reader over contiguous row-group byte ranges, one reader and one consumer
    /// per split, at most one split per row group; the tables are merged at the end.
    static StationTable columnSplits(Path file, int splits) throws IOException, InterruptedException, ExecutionException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            List<RowGroup> rowGroups = reader.getFileMetaData().rowGroups();
            int k = Math.max(1, Math.min(splits, rowGroups.size()));
            long[] bounds = new long[k + 1];
            bounds[k] = Long.MAX_VALUE;
            for (int s = 1; s < k; s++) {
                bounds[s] = chunkStart(rowGroups.get(Math.toIntExact((long) s * rowGroups.size() / k)));
            }
            try (ExecutorService executor = Executors.newFixedThreadPool(k)) {
                @SuppressWarnings("unchecked")
                Future<StationTable>[] futures = new Future[k];
                for (int s = 0; s < k; s++) {
                    RowGroupPredicate range = RowGroupPredicate.byteRange(bounds[s], bounds[s + 1]);
                    futures[s] = executor.submit(() -> aggregate(reader, range));
                }
                StationTable merged = futures[0].get();
                for (int s = 1; s < k; s++) {
                    merged.mergeFrom(futures[s].get());
                }
                return merged;
            }
        }
    }

    /// The row reader, one `getString` and `getDouble` per row into a `HashMap`.
    static StationTable rowReader(Path file) throws IOException {
        Map<String, long[]> stations = new HashMap<>(1024);
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
                RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                long tenths = Math.round(rows.getDouble(1) * 10.0);
                long[] stats = stations.get(rows.getString(0));
                if (stats == null) {
                    stats = new long[]{ Long.MAX_VALUE, Long.MIN_VALUE, 0, 0 };
                    stations.put(rows.getString(0), stats);
                }
                stats[0] = Math.min(stats[0], tenths);
                stats[1] = Math.max(stats[1], tenths);
                stats[2] += tenths;
                stats[3]++;
            }
        }
        StationTable table = new StationTable();
        for (Map.Entry<String, long[]> entry : stations.entrySet()) {
            long[] stats = entry.getValue();
            table.add(table.slot(entry.getKey().getBytes(StandardCharsets.UTF_8)), stats[0], stats[1], stats[2], stats[3]);
        }
        return table;
    }

    /// Aggregates the rows `range` selects, or all rows when it is `null`, by dictionary entry
    /// id. Fails on a batch without ids: every page of the file is dictionary-encoded, which
    /// the benchmark's gate checks.
    private static StationTable aggregate(ParquetFileReader reader, RowGroupPredicate range) throws IOException {
        StationTable table = new StationTable();
        ParquetFileReader.ColumnReadersBuilder builder = reader.buildColumnReaders(COLUMNS);
        if (range != null) {
            builder.filter(range);
        }
        try (ColumnReaders columns = builder.build()) {
            ColumnReader station = columns.getColumnReader("station");
            ColumnReader measurement = columns.getColumnReader("measurement");
            BinaryDictionary dictionary = null;
            int[] slotOfEntry = null;
            while (columns.nextBatch()) {
                int n = columns.getRecordCount();
                int[] ids = station.getDictionaryIds();
                if (ids == null) {
                    throw new IllegalStateException("station batch is not dictionary-encoded");
                }
                BinaryDictionary current = station.getBinaryDictionary();
                if (current != dictionary) {
                    dictionary = current;
                    slotOfEntry = new int[current.size()];
                    for (int e = 0; e < slotOfEntry.length; e++) {
                        slotOfEntry[e] = table.slot(current.getBinary(e));
                    }
                }
                double[] measurements = measurement.getDoubles();
                for (int i = 0; i < n; i++) {
                    table.add(slotOfEntry[ids[i]], Math.round(measurements[i] * 10.0));
                }
            }
        }
        return table;
    }

    /// Batches queued per worker before the reader's loop waits for one to be taken.
    private static final int QUEUED_PER_WORKER = 2;

    /// A batch's arrays, handed from the reader's loop to a worker. The column reader allocates
    /// them fresh per batch and never reuses them, so a worker may read them after the reader
    /// has advanced.
    private record Batch(int rows, int[] ids, double[] measurements, BinaryDictionary dictionary) {
    }

    private static final Batch END = new Batch(0, null, null, null);

    /// Aggregates every row on `workers` threads: this thread advances one column reader and
    /// queues each batch, and each worker aggregates the batches it takes into its own table.
    static StationTable fanOut(ParquetFileReader reader, int workers)
            throws IOException, InterruptedException, ExecutionException {
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUED_PER_WORKER * workers);
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<StationTable>> tables = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                tables.add(executor.submit(() -> aggregateQueued(queue)));
            }
            try (ColumnReaders columns = reader.buildColumnReaders(COLUMNS).build()) {
                ColumnReader station = columns.getColumnReader("station");
                ColumnReader measurement = columns.getColumnReader("measurement");
                while (columns.nextBatch()) {
                    int[] ids = station.getDictionaryIds();
                    if (ids == null) {
                        throw new IllegalStateException("station batch is not dictionary-encoded");
                    }
                    enqueue(queue, new Batch(columns.getRecordCount(), ids, measurement.getDoubles(),
                            station.getBinaryDictionary()), tables);
                }
            }
            finally {
                for (int w = 0; w < workers; w++) {
                    enqueue(queue, END, tables);
                }
            }
            StationTable merged = tables.get(0).get();
            for (int w = 1; w < workers; w++) {
                merged.mergeFrom(tables.get(w).get());
            }
            return merged;
        }
    }

    /// Queues `batch`, waiting for room; fails if a worker has ended, which before the end of
    /// the read only a failure does.
    private static void enqueue(BlockingQueue<Batch> queue, Batch batch, List<Future<StationTable>> workers)
            throws InterruptedException, ExecutionException {
        while (!queue.offer(batch, 10, TimeUnit.MILLISECONDS)) {
            for (Future<StationTable> worker : workers) {
                if (worker.isDone()) {
                    worker.get();
                    throw new IllegalStateException("A worker ended before the read did");
                }
            }
        }
    }

    /// A worker: aggregates the batches it takes until it takes [#END], each dictionary's
    /// entries resolved to table slots once.
    private static StationTable aggregateQueued(BlockingQueue<Batch> queue) throws InterruptedException {
        StationTable table = new StationTable();
        BinaryDictionary dictionary = null;
        int[] slotOfEntry = null;
        Batch batch;
        while ((batch = queue.take()) != END) {
            if (batch.dictionary() != dictionary) {
                dictionary = batch.dictionary();
                slotOfEntry = new int[dictionary.size()];
                for (int e = 0; e < slotOfEntry.length; e++) {
                    slotOfEntry[e] = table.slot(dictionary.getBinary(e));
                }
            }
            int[] ids = batch.ids();
            double[] measurements = batch.measurements();
            for (int i = 0; i < batch.rows(); i++) {
                table.add(slotOfEntry[ids[i]], Math.round(measurements[i] * 10.0));
            }
        }
        return table;
    }

    /// The first byte of the row group's first column chunk: its dictionary page if it has
    /// one, else its first data page.
    private static long chunkStart(RowGroup rowGroup) {
        ColumnMetaData first = rowGroup.columns().get(0).metaData();
        Long dictionary = first.dictionaryPageOffset();
        return dictionary != null && dictionary > 0 ? Math.min(dictionary, first.dataPageOffset()) : first.dataPageOffset();
    }
}
