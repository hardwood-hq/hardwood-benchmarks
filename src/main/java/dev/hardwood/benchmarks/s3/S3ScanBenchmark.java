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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.benchmarks.BenchContext;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.EventFileGenerator;
import dev.hardwood.benchmarks.TaxiDataDownloader;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.s3.S3Credentials;
import dev.hardwood.s3.S3InputFile;
import dev.hardwood.s3.S3Source;
import dev.hardwood.schema.ColumnProjection;

/// S3 read benchmark (regression-only): Hardwood's column reader over a local S3 endpoint that
/// emulates object-storage latency and bandwidth (see `s3-env.sh`), so what it measures is the
/// shape of the requests a read issues: how many, how large, how many in sequence.
///
/// | Contender | Read | Request shape it exercises |
/// |---|---|---|
/// | `hardwoodProjectedScan` | 3 non-adjacent of 20 taxi columns, one month | cross-column coalescing against parallel GETs, chunk sizing |
/// | `hardwoodFilteredScan` | selective range predicate with a page index | index slices, surviving pages only, dictionary reads |
/// | `hardwoodMultiFileScan` | one column across 12 files, one multi-file reader | planning each file as the read reaches it, footers, moving between files |
/// | `hardwoodManySmallFiles` | every column of 200 files of 100 KiB to 5 MiB, one row group each, written by PyArrow ([SmallFileSet]), one multi-file reader | the requests each file costs, opening the next file, and how a file's size compares with the tail its open fetches |
/// | `hardwoodManySmallFilesEarlyClose` | the many-small-files read, its consumer closing the reader after the first 20 files | what a read has fetched past the file its consumer stopped in: footers and data opened ahead |
/// | `hardwoodMultiFileEarlyClose` | the multi-file scan, its consumer closing the reader after the first file | the same, for files larger than what the read fetches ahead |
/// | `hardwoodWideFilteredScan` | selective range predicate projecting 3 of 200 columns, 40 row groups with a large page index | page-index reads across many row groups; each row group's data is under the coalescing gap, so it is fetched whole |
/// | `hardwoodBloomLookup` | equality probe for an absent unique key that every bloom filter rejects, 200 row groups with small bloom filters | bloom-filter reads across many row groups, each of which its filter drops |
/// | `hardwoodLargeBloomLookup` | the same kind of probe, 6 row groups with 16 MB bloom filters | bloom-filter reads each larger than one page-index window |
/// | `hardwoodLocalBesideRemote` | the projected scan of the local file, on a context a remote projected scan reads with throughout | whether a remote read's requests hold threads of the shared context's pool that a local read needs |
/// | `hardwoodRemoteBesideWideRemote` | the projected scan, on a context a remote scan of every column of the same file reads with throughout | whether a read with a request in flight for each of its columns holds the shared context's pool from another remote read |
///
/// Before timing, each read is checked against the same read of the local file, and its request
/// and byte counts go into the meta sidecar (`requests.*`, `bytes.*`): a change in the fetch
/// plan shows up there exactly, even where the time does not move. For the wide read, the
/// requests overlapping the file's page-index region and their bytes are recorded apart
/// (`indexRequests.*`, `indexBytes.*`), beside the region's size and the index bytes the read
/// needs (`wide.indexRegionBytes`, `wide.neededIndexBytes`). The many-small-files read records
/// its requests per file and, per band of file size, its files, requests and bytes
/// (`manySmallFiles.*`). It is gated and timed only when the run includes it, which
/// `--regression` does not. The two early-close reads record their requests and bytes split
/// into the files the consumer reached and those past them, the requests counted once the
/// reader had closed, and the time to the consumer's last batch and the close's time
/// (`earlyClose.<read>.*`). Each multi-file read records the requests it
/// had in flight, at most and on average over the read (`inFlight.<read>.*`). Run with
/// `run-s3.sh`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class S3ScanBenchmark {

    static final String BUCKET = "test-bucket";
    private static final String[] PROJECTED = { "trip_distance", "fare_amount", "tip_amount" };
    private static final long FILTER_ROWS = 5_000_000L;
    private static final Path FILTER_FILE = Path.of("target/filter_benchmark_" + FILTER_ROWS + ".parquet");
    private static final FilterPredicate SELECTIVE = FilterPredicate.lt("event_time", FILTER_ROWS / 20);
    private static final Path WIDE_FILE = Path.of(String.format("target/wide_filter_%dc_%drg_%dp.parquet",
            WideTableGenerator.COLUMNS, WideTableGenerator.ROW_GROUPS,
            WideTableGenerator.ROWS_PER_GROUP / WideTableGenerator.PAGE_ROWS));
    private static final String[] WIDE_PROJECTED = { WideTableGenerator.FILTER_COLUMN, "c101", "c199" };
    /// The first page of each row group's `seq` chunk: 5 % of the rows, spread over every row group.
    private static final FilterPredicate WIDE_SELECTIVE =
            FilterPredicate.lt(WideTableGenerator.FILTER_COLUMN, (long) WideTableGenerator.PAGE_ROWS);
    private static final String[] SMALL_FILE_COLUMNS =
            { "id", "pickup_us", "passenger_count", "zone", "trip_distance", "fare_amount" };
    /// The upper bounds of the file-size bands the many-small-files read's counts are recorded in:
    /// the tail `S3InputFile` fetches on open today (64 KiB) and two larger candidates.
    private static final long[] SMALL_FILE_BANDS = { 64L << 10, 256L << 10, 1L << 20 };
    /// The files of the many-small-files fixture the early-close read takes before closing.
    private static final int SMALL_FILES_REACHED = 20;
    /// How long an early-close gate read waits after closing before it counts again, so that
    /// requests the read had in flight are counted.
    private static final long SETTLE_MS = 2_000;
    /// The reads an early-close gate makes before the one it counts, so that the counted read's
    /// consumer runs warm: a cold consumer is slower, and lets the fetching run further ahead.
    private static final int EARLY_CLOSE_WARMUP = Integer.getInteger("perf.s3.earlyCloseWarmup", 0);
    private static final YearMonth FIRST_MONTH = YearMonth.of(2025, 1);
    private static final YearMonth LAST_MONTH = YearMonth.of(2025, 12);

    private S3Source source;
    private List<String> monthKeys;
    private long smallBloomProbe;
    private long largeBloomProbe;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        source = source();
        monthKeys = monthKeys();
        smallBloomProbe = BloomLookupGenerator.probes(BloomLookupGenerator.SMALL).absent();
        largeBloomProbe = BloomLookupGenerator.probes(BloomLookupGenerator.LARGE).absent();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        source.close();
    }

    @Benchmark
    public double hardwoodProjectedScan() throws IOException {
        return projectedScan(source.inputFile(BUCKET, monthKeys.get(0)));
    }

    @Benchmark
    public double hardwoodFilteredScan() throws IOException {
        return filteredScan(source.inputFile(BUCKET, FILTER_FILE.getFileName().toString()));
    }

    @Benchmark
    public double hardwoodWideFilteredScan() throws IOException {
        return wideFilteredScan(source.inputFile(BUCKET, WIDE_FILE.getFileName().toString()));
    }

    @Benchmark
    public double hardwoodBloomLookup() throws IOException {
        return bloomLookup(source.inputFile(BUCKET, BloomLookupGenerator.SMALL.file().getFileName().toString()),
                smallBloomProbe).sum();
    }

    @Benchmark
    public double hardwoodLargeBloomLookup() throws IOException {
        return bloomLookup(source.inputFile(BUCKET, BloomLookupGenerator.LARGE.file().getFileName().toString()),
                largeBloomProbe).sum();
    }

    /// A context shared by the scan a contender times and a remote scan of the first month that
    /// a thread of its own repeats throughout each iteration ([#readInBackground]).
    @State(Scope.Benchmark)
    public abstract static class SharedContext {

        HardwoodContext context;
        private S3Source source;
        private Thread remote;
        private volatile boolean stopped;
        private volatile Throwable remoteFailure;

        /// One pass of the background read.
        abstract void readInBackground(InputFile file, HardwoodContext context) throws IOException;

        @Setup(Level.Trial)
        public void createContext() {
            context = BenchContext.create();
            source = source();
        }

        @Setup(Level.Iteration)
        public void startRemote() {
            stopped = false;
            String key = monthKeys().get(0);
            remote = Thread.ofPlatform().name("remote-scan").start(() -> {
                try {
                    while (!stopped) {
                        readInBackground(source.inputFile(BUCKET, key), context);
                    }
                }
                catch (Throwable t) {
                    remoteFailure = t;
                }
            });
        }

        @TearDown(Level.Iteration)
        public void stopRemote() throws InterruptedException {
            stopped = true;
            remote.join();
            if (remoteFailure != null) {
                throw new IllegalStateException("Remote scan failed", remoteFailure);
            }
        }

        @TearDown(Level.Trial)
        public void closeContext() {
            context.close();
            source.close();
        }
    }

    /// The background read is the projected scan.
    public static class BesideProjected extends SharedContext {

        @Override
        void readInBackground(InputFile file, HardwoodContext context) throws IOException {
            projectedScan(file, context);
        }
    }

    /// The background read takes every column, so that it has a request in flight for each.
    public static class BesideWide extends SharedContext {

        @Override
        void readInBackground(InputFile file, HardwoodContext context) throws IOException {
            try (ParquetFileReader reader = ParquetFileReader.open(file, context);
                 ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.all()).build()) {
                while (cols.nextBatch()) {
                }
            }
        }
    }

    @Benchmark
    public double hardwoodLocalBesideRemote(BesideProjected shared) throws IOException {
        return projectedScan(InputFile.of(localPath(monthKeys.get(0))), shared.context);
    }

    @Benchmark
    public double hardwoodRemoteBesideWideRemote(BesideWide shared) throws IOException {
        return projectedScan(source.inputFile(BUCKET, monthKeys.get(0)), shared.context);
    }

    /// The many-small-files fixture's keys, loaded only for the contender that reads them.
    @State(Scope.Benchmark)
    public static class SmallFiles {

        List<String> keys;
        /// the rows of the files the early-close read takes
        long reachedRows;

        @Setup(Level.Trial)
        public void load() throws IOException {
            SmallFileSet set = SmallFileSet.load();
            keys = set.keys();
            reachedRows = set.rows(SMALL_FILES_REACHED);
        }
    }

    @Benchmark
    public SmallFileSums hardwoodManySmallFiles(SmallFiles smallFiles) throws IOException {
        List<InputFile> files = new ArrayList<>();
        for (String key : smallFiles.keys) {
            files.add(source.inputFile(BUCKET, key));
        }
        return smallFilesScan(files, Long.MAX_VALUE);
    }

    @Benchmark
    public SmallFileSums hardwoodManySmallFilesEarlyClose(SmallFiles smallFiles) throws IOException {
        List<InputFile> files = new ArrayList<>();
        for (String key : smallFiles.keys) {
            files.add(source.inputFile(BUCKET, key));
        }
        return smallFilesScan(files, smallFiles.reachedRows);
    }

    @Benchmark
    public double hardwoodMultiFileScan() throws IOException {
        List<InputFile> files = new ArrayList<>();
        for (String key : monthKeys) {
            files.add(source.inputFile(BUCKET, key));
        }
        return multiFileScan(files, Long.MAX_VALUE).sum();
    }

    /// The rows of the first month, after which the early-close multi-file read closes.
    @State(Scope.Benchmark)
    public static class FirstMonth {

        long rows;

        @Setup(Level.Trial)
        public void count() throws IOException {
            rows = BenchReport.totalRows(List.of(localPath(monthKeys().get(0))));
        }
    }

    @Benchmark
    public double hardwoodMultiFileEarlyClose(FirstMonth firstMonth) throws IOException {
        List<InputFile> files = new ArrayList<>();
        for (String key : monthKeys) {
            files.add(source.inputFile(BUCKET, key));
        }
        return multiFileScan(files, firstMonth.rows).sum();
    }

    static double projectedScan(InputFile file) throws IOException {
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(file, context)) {
            return projectedSum(reader);
        }
    }

    static double projectedScan(InputFile file, HardwoodContext context) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(file, context)) {
            return projectedSum(reader);
        }
    }

    private static double projectedSum(ParquetFileReader reader) throws IOException {
        double sum = 0;
        try (ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns(PROJECTED)).build()) {
            while (cols.nextBatch()) {
                for (int c = 0; c < PROJECTED.length; c++) {
                    for (double x : cols.getColumnReader(c).getDoubles()) {
                        sum += x;
                    }
                }
            }
        }
        return sum;
    }

    static double filteredScan(InputFile file) throws IOException {
        double sum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(file, context);
             ColumnReader amount = reader.buildColumnReader("amount").filter(SELECTIVE).build()) {
            while (amount.nextBatch()) {
                double[] values = amount.getDoubles();
                for (int i = 0; i < amount.getRecordCount(); i++) {
                    sum += values[i];
                }
            }
        }
        return sum;
    }

    static double wideFilteredScan(InputFile file) throws IOException {
        double sum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(file, context);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns(WIDE_PROJECTED))
                     .filter(WIDE_SELECTIVE)
                     .build()) {
            ColumnReader seq = cols.getColumnReader(0);
            ColumnReader first = cols.getColumnReader(1);
            ColumnReader second = cols.getColumnReader(2);
            while (cols.nextBatch()) {
                long[] seqs = seq.getLongs();
                double[] firsts = first.getDoubles();
                double[] seconds = second.getDoubles();
                for (int i = 0; i < cols.getRecordCount(); i++) {
                    sum += seqs[i] + firsts[i] + seconds[i];
                }
            }
        }
        return sum;
    }

    /// The rows an equality lookup matched and the sum of their values.
    record Lookup(long rows, double sum) {
    }

    static Lookup bloomLookup(InputFile file, long probe) throws IOException {
        long rows = 0;
        double sum = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(file, context);
             ColumnReader value = reader.buildColumnReader(BloomLookupGenerator.VALUE_COLUMN)
                     .filter(FilterPredicate.eq(BloomLookupGenerator.KEY_COLUMN, probe))
                     .build()) {
            while (value.nextBatch()) {
                double[] values = value.getDoubles();
                for (int i = 0; i < value.getRecordCount(); i++) {
                    sum += values[i];
                }
                rows += value.getRecordCount();
            }
        }
        return new Lookup(rows, sum);
    }

    /// The rows a scan took and the sum of their values.
    record Scanned(long rows, double sum) {
    }

    /// How long the last scan on this thread took from opening its reader to its consumer's last
    /// batch, and from then until the reader and its context had closed, in nanoseconds; read by
    /// the early-close gate.
    private static long lastConsumedNanos;
    private static long lastCloseNanos;

    /// `fare_amount` of `files`, summed, the consumer closing the reader once it has taken
    /// `stopRows` rows; a batch ends at a file, so a stop at a file's end is exact.
    static Scanned multiFileScan(List<InputFile> files, long stopRows) throws IOException {
        long rows = 0;
        double sum = 0;
        long start = System.nanoTime();
        long consumed = start;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.openAll(files, context);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns("fare_amount")).build()) {
            ColumnReader fare = cols.getColumnReader(0);
            while (rows < stopRows && cols.nextBatch()) {
                double[] values = fare.getDoubles();
                for (int i = 0; i < fare.getRecordCount(); i++) {
                    sum += values[i];
                }
                rows += fare.getRecordCount();
            }
            consumed = System.nanoTime();
        }
        timed(start, consumed);
        return new Scanned(rows, sum);
    }

    /// The rows of the many-small-files read and a sum per column: exact for the integer columns,
    /// and taken in row order for the doubles, so two reads of the same rows agree bit for bit.
    record SmallFileSums(long rows, long ids, long pickups, long passengers, long zones, double distances,
            double fares) {
    }

    /// Every column of `files`, summed per column, the consumer closing the reader once it has
    /// taken `stopRows` rows.
    static SmallFileSums smallFilesScan(List<InputFile> files, long stopRows) throws IOException {
        long rows = 0;
        long idSum = 0;
        long pickupSum = 0;
        long passengerSum = 0;
        long zoneSum = 0;
        double distanceSum = 0;
        double fareSum = 0;
        long start = System.nanoTime();
        long consumed = start;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.openAll(files, context);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns(SMALL_FILE_COLUMNS)).build()) {
            ColumnReader id = cols.getColumnReader(0);
            ColumnReader pickup = cols.getColumnReader(1);
            ColumnReader passengers = cols.getColumnReader(2);
            ColumnReader zone = cols.getColumnReader(3);
            ColumnReader distance = cols.getColumnReader(4);
            ColumnReader fare = cols.getColumnReader(5);
            while (rows < stopRows && cols.nextBatch()) {
                long[] ids = id.getLongs();
                long[] pickups = pickup.getLongs();
                int[] passengerCounts = passengers.getInts();
                int[] zones = zone.getInts();
                double[] distances = distance.getDoubles();
                double[] fares = fare.getDoubles();
                int count = cols.getRecordCount();
                for (int i = 0; i < count; i++) {
                    idSum += ids[i];
                    pickupSum += pickups[i];
                    passengerSum += passengerCounts[i];
                    zoneSum += zones[i];
                    distanceSum += distances[i];
                    fareSum += fares[i];
                }
                rows += count;
            }
            consumed = System.nanoTime();
        }
        timed(start, consumed);
        return new SmallFileSums(rows, idSum, pickupSum, passengerSum, zoneSum, distanceSum, fareSum);
    }

    private static void timed(long start, long consumed) {
        lastConsumedNanos = consumed - start;
        lastCloseNanos = System.nanoTime() - consumed;
    }

    static S3Source source() {
        return S3Source.builder()
                .endpoint(System.getProperty("perf.s3.endpoint", "http://127.0.0.1:18081"))
                .region("us-east-1")
                .pathStyle(true)
                .credentials(S3Credentials.of("access", "secret"))
                .build();
    }

    static List<String> monthKeys() {
        List<String> keys = new ArrayList<>();
        for (YearMonth m = FIRST_MONTH; !m.isAfter(LAST_MONTH); m = m.plusMonths(1)) {
            keys.add(TaxiDataDownloader.formatFilename(m));
        }
        return keys;
    }

    /// Links (or copies) a fixture into the bucket directory the filesystem-backed S3Proxy serves.
    private static void publish(Path file, Path bucketDir) throws IOException {
        publish(file, bucketDir, file.getFileName().toString());
    }

    /// As [#publish(Path,Path)], under the key `key`.
    private static void publish(Path file, Path bucketDir, String key) throws IOException {
        Path target = bucketDir.resolve(key);
        if (Files.exists(target)) {
            // A link to the file, or a copy at least as new: anything else is a stale fixture.
            if (Files.isSameFile(target, file) || (Files.size(target) == Files.size(file)
                    && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(file)) >= 0)) {
                return;
            }
            Files.delete(target);
        }
        try {
            Files.createLink(target, file.toAbsolutePath());
        }
        catch (IOException | UnsupportedOperationException e) {
            Files.copy(file, target);
        }
    }

    /// Runs `read` over S3 and over the local file of `key`, checks they agree, and returns the S3
    /// read's request and byte counts, with those falling on the page index (given by `layout`)
    /// attributed apart.
    private static Fetches gate(String name, S3Source source, String key, PageIndexLayout layout,
            Function<InputFile, Double> read) {
        AttributingInputFile in = new AttributingInputFile(source.inputFile(BUCKET, key), layout);
        double s3 = read.apply(in);
        double local = read.apply(InputFile.of(localPath(key)));
        check(name, s3, local);
        Fetches fetches = new Fetches(in.requests(), in.bytes(), in.indexRequests(), in.indexBytes());
        String index = layout == PageIndexLayout.NONE ? ""
                : String.format("; page index %,d requests, %,d bytes", fetches.indexRequests(), fetches.indexBytes());
        System.out.printf("Gate passed [%s] — S3 and local reads agree (%,d requests, %,d bytes%s).%n", name,
                fetches.requests(), fetches.bytes(), index);
        return fetches;
    }

    /// As [#gate] for the absent probe of `shape`, which must match no row over S3 and locally,
    /// and checks that its present probe matches its one row over S3 and locally, so a filter read
    /// wrongly shows as a missing row.
    private static Fetches gateLookup(String name, S3Source source, BloomLookupGenerator.Shape shape)
            throws IOException {
        String key = shape.file().getFileName().toString();
        BloomLookupGenerator.Probes probes = BloomLookupGenerator.probes(shape);
        List<Supplier<InputFile>> files = List.of(() -> source.inputFile(BUCKET, key),
                () -> InputFile.of(localPath(key)));
        for (Supplier<InputFile> file : files) {
            Lookup absent = bloomLookup(file.get(), probes.absent());
            Lookup present = bloomLookup(file.get(), probes.present());
            if (absent.rows() != 0 || present.rows() != 1 || present.sum() != probes.presentValue()) {
                throw new IllegalStateException(String.format(
                        "[%s] absent probe matched %d rows, present probe %d rows with value %f (expected %f)",
                        name, absent.rows(), present.rows(), present.sum(), probes.presentValue()));
            }
        }
        return gate(name, source, key, PageIndexLayout.NONE, unchecked(in -> bloomLookup(in, probes.absent()).sum()));
    }

    private static void check(String name, double s3, double local) {
        if (Math.abs(s3 - local) > 1e-6 * Math.max(1.0, Math.abs(local))) {
            throw new IllegalStateException(String.format("[%s] S3 read %.3f, local read %.3f", name, s3, local));
        }
    }

    private static Path localPath(String key) {
        return Path.of(System.getProperty("perf.s3.dataDir"), BUCKET, key);
    }

    /// As [#gate], for a read over all of `keys` at once; also returns the requests it had in
    /// flight, at most and on average ([#inFlight]).
    private static long[] gateAll(String name, S3Source source, List<String> keys, List<String> meta)
            throws IOException {
        List<S3InputFile> s3Files = new ArrayList<>();
        List<InputFile> localFiles = new ArrayList<>();
        for (String key : keys) {
            s3Files.add(source.inputFile(BUCKET, key));
            localFiles.add(InputFile.of(localPath(key)));
        }
        RequestRecorder recorder = new RequestRecorder();
        List<InputFile> recorded = s3Files.stream().map(recorder::wrap).toList();
        recorder.started();
        double s3 = multiFileScan(recorded, Long.MAX_VALUE).sum();
        recorder.ended();
        double local = multiFileScan(localFiles, Long.MAX_VALUE).sum();
        long requests = 0;
        long bytes = 0;
        for (S3InputFile in : s3Files) {
            requests += in.networkRequestCount();
            bytes += in.networkBytesFetched();
        }
        check(name, s3, local);
        System.out.printf("Gate passed [%s] — S3 and local reads agree (%d files, %,d requests, %,d bytes).%n", name,
                keys.size(), requests, bytes);
        meta.addAll(inFlight(name, "multiFileScan", recorder));
        return new long[] { requests, bytes };
    }

    /// Prints the requests `recorder` saw in flight, at most and on average over the read, and
    /// returns them as sidecar pairs under `inFlight.<metaName>`.
    private static List<String> inFlight(String name, String metaName, RequestRecorder recorder) {
        System.out.printf("In flight [%s] — at most %d requests, %.1f on average over the read.%n", name,
                recorder.peak(), recorder.mean());
        return List.of("inFlight." + metaName + ".peak", Integer.toString(recorder.peak()),
                "inFlight." + metaName + ".mean", String.format("%.1f", recorder.mean()));
    }

    /// A multi-file read over the files given, in order.
    @FunctionalInterface
    private interface FilesRead<T> {
        T apply(List<InputFile> files) throws IOException;
    }

    /// As [#gate], for a read over all of `keys` whose consumer closes the reader once it has
    /// taken the rows of the first `reached` files, `stopRows`: checks it against the same read
    /// of the local files and that it took exactly those rows, and returns its sidecar pairs
    /// under `earlyClose.<metaName>`: its requests and bytes over the files it reached and over
    /// those past them, and the requests counted only once the reader had closed, waiting
    /// [#SETTLE_MS] for those in flight. The counted read follows [#EARLY_CLOSE_WARMUP] reads of
    /// the same files, each over files of its own.
    private static <T> List<String> gateEarlyClose(String name, String metaName, S3Source source, List<String> keys,
            int reached, long stopRows, FilesRead<T> read, ToLongFunction<T> rows)
            throws IOException, InterruptedException {
        List<S3InputFile> s3Files = new ArrayList<>();
        List<InputFile> localFiles = new ArrayList<>();
        for (String key : keys) {
            s3Files.add(source.inputFile(BUCKET, key));
            localFiles.add(InputFile.of(localPath(key)));
        }
        StringBuilder warmupTimes = new StringBuilder();
        for (int i = 0; i < EARLY_CLOSE_WARMUP; i++) {
            List<InputFile> warmup = new ArrayList<>();
            for (String key : keys) {
                warmup.add(source.inputFile(BUCKET, key));
            }
            read.apply(warmup);
            warmupTimes.append(warmupTimes.isEmpty() ? "" : ", ").append(consumedAndClose());
        }
        RequestRecorder recorder = new RequestRecorder();
        List<InputFile> recorded = s3Files.stream().map(recorder::wrap).toList();
        recorder.started();
        T s3 = read.apply(recorded);
        recorder.ended();
        String times = consumedAndClose();
        long consumedNanos = lastConsumedNanos;
        long closeNanos = lastCloseNanos;
        long atClose = s3Files.stream().mapToLong(S3InputFile::networkRequestCount).sum();
        Thread.sleep(SETTLE_MS);
        T local = read.apply(localFiles);
        if (!s3.equals(local) || rows.applyAsLong(s3) != stopRows) {
            throw new IllegalStateException(String.format("[%s] S3 read %s, local read %s, expected %d rows", name,
                    s3, local, stopRows));
        }
        long reachedRequests = 0;
        long reachedBytes = 0;
        long pastRequests = 0;
        long pastBytes = 0;
        int pastFiles = 0;
        for (int i = 0; i < s3Files.size(); i++) {
            long requests = s3Files.get(i).networkRequestCount();
            long bytes = s3Files.get(i).networkBytesFetched();
            if (i < reached) {
                reachedRequests += requests;
                reachedBytes += bytes;
            }
            else {
                pastRequests += requests;
                pastBytes += bytes;
                pastFiles += requests > 0 ? 1 : 0;
            }
        }
        long requests = reachedRequests + pastRequests;
        System.out.printf("Gate passed [%s] — S3 and local reads agree (%d files, %,d requests, %,d bytes; "
                + "%d files reached: %,d requests, %,d bytes; past them %d files opened: %,d requests, %,d bytes; "
                + "%,d requests counted after close).%n", name, keys.size(), requests, reachedBytes + pastBytes,
                reached, reachedRequests, reachedBytes, pastFiles, pastRequests, pastBytes, requests - atClose);
        String prefix = "earlyClose." + metaName + ".";
        List<String> meta = new ArrayList<>(List.of(
                prefix + "reachedFiles", Integer.toString(reached),
                prefix + "reachedRequests", Long.toString(reachedRequests),
                prefix + "reachedBytes", Long.toString(reachedBytes),
                prefix + "pastFilesOpened", Integer.toString(pastFiles),
                prefix + "pastRequests", Long.toString(pastRequests),
                prefix + "pastBytes", Long.toString(pastBytes),
                prefix + "requestsAfterClose", Long.toString(requests - atClose)));
        System.out.printf("Close [%s] — consumed + closed in %s ms%s.%n", name, times,
                warmupTimes.isEmpty() ? "" : "; warm-up reads " + warmupTimes);
        meta.add(prefix + "consumedMs");
        meta.add(String.format("%.0f", consumedNanos / 1e6));
        meta.add(prefix + "closeMs");
        meta.add(String.format("%.0f", closeNanos / 1e6));
        meta.addAll(inFlight(name, metaName + "EarlyClose", recorder));
        return meta;
    }

    /// The last scan's two times, as `consumed + close` in milliseconds.
    private static String consumedAndClose() {
        return String.format("%.0f + %.0f", lastConsumedNanos / 1e6, lastCloseNanos / 1e6);
    }

    /// As [#gate], for the many-small-files read: checks it against the local files and returns
    /// its sidecar pairs, the totals, requests per file and their distribution, and the files,
    /// requests and bytes of each band of file size.
    private static List<String> gateSmallFiles(S3Source source, SmallFileSet set) throws IOException {
        List<S3InputFile> s3Files = new ArrayList<>();
        List<InputFile> localFiles = new ArrayList<>();
        for (SmallFileSet.Entry entry : set.files()) {
            s3Files.add(source.inputFile(BUCKET, set.key(entry)));
            localFiles.add(InputFile.of(localPath(set.key(entry))));
        }
        RequestRecorder recorder = new RequestRecorder();
        List<InputFile> recorded = s3Files.stream().map(recorder::wrap).toList();
        recorder.started();
        SmallFileSums s3 = smallFilesScan(recorded, Long.MAX_VALUE);
        recorder.ended();
        SmallFileSums local = smallFilesScan(localFiles, Long.MAX_VALUE);
        if (!s3.equals(local) || s3.rows() != set.totalRows()) {
            throw new IllegalStateException(String.format("[many small files] S3 read %s, local read %s, manifest %d rows",
                    s3, local, set.totalRows()));
        }
        int bands = SMALL_FILE_BANDS.length + 1;
        long[] bandFiles = new long[bands];
        long[] bandRequests = new long[bands];
        long[] bandBytes = new long[bands];
        Map<Long, Integer> perFile = new TreeMap<>();
        for (int i = 0; i < s3Files.size(); i++) {
            int band = band(set.files().get(i).size());
            long requests = s3Files.get(i).networkRequestCount();
            bandFiles[band]++;
            bandRequests[band] += requests;
            bandBytes[band] += s3Files.get(i).networkBytesFetched();
            perFile.merge(requests, 1, Integer::sum);
        }
        long requests = 0;
        long bytes = 0;
        for (int b = 0; b < bands; b++) {
            requests += bandRequests[b];
            bytes += bandBytes[b];
        }
        String distribution = String.join(",", perFile.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue()).toList());
        System.out.printf("Gate passed [many small files] — S3 and local reads agree (%d files, %,d bytes; "
                + "%,d requests, %.2f per file (requests:files %s), %,d bytes).%n", s3Files.size(), set.totalBytes(),
                requests, (double) requests / s3Files.size(), distribution, bytes);
        List<String> meta = new ArrayList<>(List.of(
                "requests.manySmallFiles", Long.toString(requests),
                "bytes.manySmallFiles", Long.toString(bytes),
                "manySmallFiles.files", Integer.toString(s3Files.size()),
                "manySmallFiles.fileBytes", Long.toString(set.totalBytes()),
                "manySmallFiles.rows", Long.toString(set.totalRows()),
                "manySmallFiles.requestsPerFile", String.format("%.2f", (double) requests / s3Files.size()),
                "manySmallFiles.requestsPerFileDistribution", distribution));
        for (int b = 0; b < bands; b++) {
            String name = "manySmallFiles.band." + bandName(b);
            meta.addAll(List.of(name + ".files", Long.toString(bandFiles[b]),
                    name + ".requests", Long.toString(bandRequests[b]),
                    name + ".bytes", Long.toString(bandBytes[b])));
        }
        meta.addAll(inFlight("many small files", "manySmallFiles", recorder));
        return meta;
    }

    /// The band of [#SMALL_FILE_BANDS] a file of `size` bytes falls into.
    private static int band(long size) {
        int band = 0;
        while (band < SMALL_FILE_BANDS.length && size >= SMALL_FILE_BANDS[band]) {
            band++;
        }
        return band;
    }

    /// Band `b`'s name, by its bounds in KiB: `0-64KiB`, `64-256KiB`, `256-1024KiB`, `1024KiB+`.
    private static String bandName(int b) {
        long lower = b == 0 ? 0 : SMALL_FILE_BANDS[b - 1] >> 10;
        return b == SMALL_FILE_BANDS.length ? lower + "KiB+" : lower + "-" + (SMALL_FILE_BANDS[b] >> 10) + "KiB";
    }

    /// Whether the run's `--include` selects the contender `method`, as JMH matches it.
    private static boolean included(String method) {
        return Pattern.compile(BenchReport.includePattern(S3ScanBenchmark.class))
                .matcher(S3ScanBenchmark.class.getName() + "." + method).find();
    }

    private static Function<InputFile, Double> unchecked(IoRead read) {
        return in -> {
            try {
                return read.apply(in);
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /// A gate read's network requests and bytes, and the share of each that fell on the page index.
    private record Fetches(long requests, long bytes, long indexRequests, long indexBytes) {

        /// The meta sidecar pairs for the contender `name`.
        String[] metaPairs(String name) {
            return new String[] {
                    "requests." + name, Long.toString(requests), "bytes." + name, Long.toString(bytes) };
        }

        /// The meta sidecar pairs of the share that fell on the page index.
        String[] indexMetaPairs(String name) {
            return new String[] {
                    "indexRequests." + name, Long.toString(indexRequests),
                    "indexBytes." + name, Long.toString(indexBytes) };
        }
    }

    @FunctionalInterface
    private interface IoRead {
        double apply(InputFile in) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        Path bucketDir = Path.of(System.getProperty("perf.s3.dataDir"), BUCKET);
        Files.createDirectories(bucketDir);
        List<Path> months = TaxiDataDownloader.ensure(FIRST_MONTH, LAST_MONTH);
        EventFileGenerator.ensure(FILTER_FILE, FILTER_ROWS);
        WideTableGenerator.ensure(WIDE_FILE);
        BloomLookupGenerator.ensure(BloomLookupGenerator.SMALL);
        BloomLookupGenerator.ensure(BloomLookupGenerator.LARGE);
        for (Path month : months) {
            publish(month, bucketDir);
        }
        publish(FILTER_FILE, bucketDir);
        publish(WIDE_FILE, bucketDir);
        publish(BloomLookupGenerator.SMALL.file(), bucketDir);
        publish(BloomLookupGenerator.LARGE.file(), bucketDir);
        SmallFileSet smallFiles = included("hardwoodManySmallFiles") || included("hardwoodManySmallFilesEarlyClose")
                ? SmallFileSet.load() : null;
        if (smallFiles != null) {
            for (SmallFileSet.Entry entry : smallFiles.files()) {
                publish(smallFiles.dir().resolve(entry.name()), bucketDir, smallFiles.key(entry));
            }
        }

        List<String> keys = monthKeys();
        String filterKey = FILTER_FILE.getFileName().toString();
        String wideKey = WIDE_FILE.getFileName().toString();
        PageIndexLayout wideLayout = PageIndexLayout.of(localPath(wideKey), Set.of(WideTableGenerator.FILTER_COLUMN),
                Set.of(WIDE_PROJECTED));
        System.out.printf("Wide table: page-index region %,d bytes, of which the filtered read needs %,d.%n",
                wideLayout.regionBytes(), wideLayout.neededBytes());
        Fetches projected;
        Fetches filtered;
        Fetches wideFiltered;
        Fetches bloomLookup;
        Fetches largeBloomLookup;
        long[] multiFile;
        List<String> smallFilesMeta = new ArrayList<>();
        List<String> multiFileMeta = new ArrayList<>();
        try (S3Source source = source()) {
            // The taxi files carry no page index, and the event file's lies in the footer tail
            // S3InputFile.open() fetches, so only the wide read's index requests are attributed.
            projected = gate("projected scan", source, keys.get(0), PageIndexLayout.NONE,
                    unchecked(S3ScanBenchmark::projectedScan));
            filtered = gate("filtered scan", source, filterKey, PageIndexLayout.NONE,
                    unchecked(S3ScanBenchmark::filteredScan));
            wideFiltered = gate("wide filtered scan", source, wideKey, wideLayout,
                    unchecked(S3ScanBenchmark::wideFilteredScan));
            bloomLookup = gateLookup("bloom lookup", source, BloomLookupGenerator.SMALL);
            largeBloomLookup = gateLookup("large bloom lookup", source, BloomLookupGenerator.LARGE);
            multiFile = gateAll("multi-file scan", source, keys, multiFileMeta);
            long firstMonthRows = BenchReport.totalRows(months.subList(0, 1));
            multiFileMeta.addAll(gateEarlyClose("multi-file scan, early close", "multiFileScan", source, keys, 1,
                    firstMonthRows, files -> multiFileScan(files, firstMonthRows), Scanned::rows));
            if (smallFiles != null) {
                if (included("hardwoodManySmallFiles")) {
                    smallFilesMeta.addAll(gateSmallFiles(source, smallFiles));
                }
                if (included("hardwoodManySmallFilesEarlyClose")) {
                    long reachedRows = smallFiles.rows(SMALL_FILES_REACHED);
                    smallFilesMeta.addAll(gateEarlyClose("many small files, early close", "manySmallFiles", source,
                            smallFiles.keys(), SMALL_FILES_REACHED, reachedRows,
                            files -> smallFilesScan(files, reachedRows), SmallFileSums::rows));
                }
            }
            Path local = localPath(keys.get(0));
            try (HardwoodContext context = BenchContext.create()) {
                check("local beside remote", projectedScan(InputFile.of(local), context),
                        projectedScan(InputFile.of(local)));
            }
        }
        if (Boolean.getBoolean("perf.gate")) {
            return;
        }
        List<String> meta = new ArrayList<>();
        meta.addAll(List.of(projected.metaPairs("projectedScan")));
        meta.addAll(List.of(filtered.metaPairs("filteredScan")));
        meta.addAll(List.of(wideFiltered.metaPairs("wideFilteredScan")));
        meta.addAll(List.of(wideFiltered.indexMetaPairs("wideFilteredScan")));
        meta.addAll(List.of(bloomLookup.metaPairs("bloomLookup")));
        meta.addAll(List.of(largeBloomLookup.metaPairs("largeBloomLookup")));
        meta.addAll(List.of("requests.multiFileScan", Long.toString(multiFile[0]),
                "bytes.multiFileScan", Long.toString(multiFile[1]),
                "wide.indexRegionBytes", Long.toString(wideLayout.regionBytes()),
                "wide.neededIndexBytes", Long.toString(wideLayout.neededBytes()),
                "latencyMs", System.getProperty("perf.s3.latencyMs", "?"),
                "bandwidthKBps", System.getProperty("perf.s3.bandwidthKBps", "?")));
        meta.addAll(multiFileMeta);
        meta.addAll(smallFilesMeta);
        BenchReport.writeRunParams(BenchReport.totalRows(months.subList(0, 1)), Files.size(months.get(0)),
                null, null, meta.toArray(String[]::new));

        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(S3ScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
        System.getProperties().forEach((k, v) -> {
            if (((String) k).startsWith("perf.")) {
                opts.jvmArgsAppend("-D" + k + "=" + v);
            }
        });
        String prof = System.getProperty("perf.prof");
        if (prof != null && !prof.isBlank()) {
            for (String raw : prof.split(",")) {
                String p = raw.trim();
                int colon = p.indexOf(':');
                if (colon > 0) {
                    opts.addProfiler(p.substring(0, colon), p.substring(colon + 1));
                }
                else {
                    opts.addProfiler(p);
                }
            }
        }
        Collection<RunResult> results = new Runner(opts.build()).run();
        BenchReport.appendMsPerOpTsv(results, S3ScanBenchmark.class);
    }
}
