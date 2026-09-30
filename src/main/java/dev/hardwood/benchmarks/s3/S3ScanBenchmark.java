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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

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

import dev.hardwood.InputFile;
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
/// | `hardwoodWideFilteredScan` | selective range predicate projecting 3 of 200 columns, 40 row groups with a large page index | page-index reads across many row groups; each row group's data is under the coalescing gap, so it is fetched whole |
/// | `hardwoodBloomLookup` | equality probe for an absent unique key that every bloom filter rejects, 200 row groups with small bloom filters | bloom-filter reads across many row groups, each of which its filter drops |
/// | `hardwoodLargeBloomLookup` | the same kind of probe, 6 row groups with 16 MB bloom filters | bloom-filter reads each larger than one page-index window |
///
/// Before timing, each read is checked against the same read of the local file, and its request
/// and byte counts go into the meta sidecar (`requests.*`, `bytes.*`): a change in the fetch
/// plan shows up there exactly, even where the time does not move. For the wide read, the
/// requests overlapping the file's page-index region and their bytes are recorded apart
/// (`indexRequests.*`, `indexBytes.*`), beside the region's size and the index bytes the read
/// needs (`wide.indexRegionBytes`, `wide.neededIndexBytes`). Run with `run-s3.sh`.
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

    @Benchmark
    public double hardwoodMultiFileScan() throws IOException {
        List<InputFile> files = new ArrayList<>();
        for (String key : monthKeys) {
            files.add(source.inputFile(BUCKET, key));
        }
        return multiFileScan(files);
    }

    static double projectedScan(InputFile file) throws IOException {
        double sum = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(file);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns(PROJECTED)).build()) {
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
        try (ParquetFileReader reader = ParquetFileReader.open(file);
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
        try (ParquetFileReader reader = ParquetFileReader.open(file);
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
        try (ParquetFileReader reader = ParquetFileReader.open(file);
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

    static double multiFileScan(List<InputFile> files) throws IOException {
        double sum = 0;
        try (ParquetFileReader reader = ParquetFileReader.openAll(files);
             ColumnReaders cols = reader.buildColumnReaders(ColumnProjection.columns("fare_amount")).build()) {
            ColumnReader fare = cols.getColumnReader(0);
            while (cols.nextBatch()) {
                double[] values = fare.getDoubles();
                for (int i = 0; i < fare.getRecordCount(); i++) {
                    sum += values[i];
                }
            }
        }
        return sum;
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
        Path target = bucketDir.resolve(file.getFileName().toString());
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

    /// As [#gate], for a read over all of `keys` at once.
    private static long[] gateAll(String name, S3Source source, List<String> keys) throws IOException {
        List<S3InputFile> s3Files = new ArrayList<>();
        List<InputFile> localFiles = new ArrayList<>();
        for (String key : keys) {
            s3Files.add(source.inputFile(BUCKET, key));
            localFiles.add(InputFile.of(localPath(key)));
        }
        double s3 = multiFileScan(new ArrayList<>(s3Files));
        double local = multiFileScan(localFiles);
        long requests = 0;
        long bytes = 0;
        for (S3InputFile in : s3Files) {
            requests += in.networkRequestCount();
            bytes += in.networkBytesFetched();
        }
        check(name, s3, local);
        System.out.printf("Gate passed [%s] — S3 and local reads agree (%,d requests, %,d bytes).%n", name, requests, bytes);
        return new long[] { requests, bytes };
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
            multiFile = gateAll("multi-file scan", source, keys);
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
