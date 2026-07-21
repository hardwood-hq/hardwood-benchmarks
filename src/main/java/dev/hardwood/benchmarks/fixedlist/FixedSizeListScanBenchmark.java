/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.fixedlist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
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
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.ReaderConfig;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;

/// End-to-end fixed-size-list read benchmark: a full scan of a `LIST<float32>`
/// column of fixed-width vectors, with the fixed-size-list fast path enabled and
/// disabled, across a sweep of vector lengths `k`. This reads the vectors the way
/// an application would, through both public readers, and compares Hardwood to
/// itself (fast path on vs off) — there is no cross-engine contender, so the run
/// is all-cores only.
///
/// - `columnFast` / `columnBaseline` — the [ColumnReader] batch path, fast path on
///   / off. Off is the record-reconstruction baseline being beaten.
/// - `rowFast` / `rowBaseline` — the [RowReader] path materializing a [PqList] per
///   row, fast path on / off (the ergonomic, per-row path).
/// - `flatFloor` — the identical values as a plain float32 column read through the
///   **column** reader: the absolute decode floor, the fastest these bytes move
///   with no list structure at all. Both readers are measured against this one
///   columnar floor — a row-read of the flat column would not be a floor, since it
///   iterates `k`× more rows (one float each) so per-row overhead dominates. `main`
///   prints each reader's time as a multiple of it (`column/floor`, `row/floor`).
///
/// The gap between a `*Baseline` and its `*Fast` is the reconstruction work the
/// detector skips; the gap between `*Fast` and `flatFloor` is what list structure
/// still costs once reconstruction is gone. `k` spans every rep-level regime: the
/// small-`k` bit-packed tiled compare (`k <= 8`), the scalar-verified notch where
/// rows are not byte-aligned (`k` in 9..15), and the large-`k` RLE-stride tiled
/// compare (`k >= 16`). `k = 3` (3-D points) and `k = 768` (embedding vectors)
/// are the two headline cases.
///
/// The corpus is generated on demand by [FixedSizeListFileGenerator] (required
/// `LIST` of required elements — max def level 1), so a plain `run-fixedlist.sh`
/// both generates and measures. A warm-cache, in-memory read is what this measures,
/// so the files should fit in page cache. The fast path is opt-in (off by
/// default), so both configs set the option explicitly; it changes only decode
/// speed, never the values — `--gate` proves `fast` and `baseline` fold to an
/// identical sum before any timing counts.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = { "-Xms4g", "-Xmx4g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class FixedSizeListScanBenchmark {

    /// Leaf path of the vector column (3-level compliant `list.element`), the
    /// top-level field name the row reader resolves, and the flat floor column.
    private static final String LIST_COLUMN = "vec.list.element";
    private static final String LIST_FIELD = "vec";
    private static final String FLAT_COLUMN = "value";

    private static final String DEFAULT_DATA_DIR = "target/fixed-size-list-data";
    private static final long DEFAULT_TOTAL_VALUES = 8_000_000L;

    /// The default `k` sweep, kept in sync with [#K_SWEEP] (used by `--gate` and by
    /// generation): evenly geometric on the log-`k` axis so no region is over- or
    /// under-sampled. `1` anchors the short-list edge (guarding against a fast-path
    /// penalty); `2, 3, 4, 8` resolve the steep small-`k` rise; `9, 12, 15` cover the
    /// scalar-verified notch where rows are not byte-aligned; `16, 32, 64, 128, 256,
    /// 512` sample the large-`k` plateau densely enough that the row reader's peak near
    /// `k = 64` reads as a real feature; `768, 1536` are the embedding-scale tail.
    /// `k = 3` (points) and `k = 768` (embeddings) are the two headline points, also
    /// labelled by the charts.
    @Param({ "1", "2", "3", "4", "8", "9", "12", "15", "16", "32", "64", "128", "256", "512", "768", "1536" })
    private int k;

    private static final int[] K_SWEEP = { 1, 2, 3, 4, 8, 9, 12, 15, 16, 32, 64, 128, 256, 512, 768, 1536 };

    private Path listPath;
    private Path flatPath;

    /// One shared context (thread pool + native pools) backs both configs; the fast
    /// path enabled and disabled differ only in [ReaderConfig], measured on the
    /// same file.
    private HardwoodContext context;
    private ReaderConfig fastConfig;
    private ReaderConfig noFastConfig;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        Path dir = Path.of(dataDir());
        // main() already generated the corpus into this dir; ensure() is an
        // idempotent stat when present (and a safety net if a fork's dataDir was
        // not forwarded), so the fork never measures a missing file.
        long totalValues = totalValues();
        FixedSizeListFileGenerator.ensure(dir, k, totalValues);
        listPath = FixedSizeListFileGenerator.listFile(dir, k, totalValues);
        flatPath = FixedSizeListFileGenerator.flatFile(dir, k, totalValues);
        context = HardwoodContext.create();
        fastConfig = ReaderConfig.builder().option("hardwood.fixed-list-fast-path", "true").build();
        noFastConfig = ReaderConfig.builder().option("hardwood.fixed-list-fast-path", "false").build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        context.close();
    }

    @Benchmark
    public double columnFast() throws IOException {
        return sumColumn(listPath, LIST_COLUMN, context, fastConfig, k);
    }

    @Benchmark
    public double columnBaseline() throws IOException {
        return sumColumn(listPath, LIST_COLUMN, context, noFastConfig, k);
    }

    @Benchmark
    public double rowFast() throws IOException {
        return sumRows(listPath, LIST_FIELD, context, fastConfig);
    }

    @Benchmark
    public double rowBaseline() throws IOException {
        return sumRows(listPath, LIST_FIELD, context, noFastConfig);
    }

    @Benchmark
    public double flatFloor() throws IOException {
        return sumColumn(flatPath, FLAT_COLUMN, context, fastConfig, 1);
    }

    /// Full scan of a float leaf through the column reader: pull each batch's
    /// `float[]` and fold every value. On the LIST column this is what the fast
    /// path accelerates; on the flat column it is the decode floor.
    private static double sumColumn(Path path, String column, HardwoodContext context,
                                    ReaderConfig config, int valuesPerRow) throws IOException {
        // -Dperf.batchSize forces an explicit batch size, overriding the fan-out-aware
        // auto-sizing. It is expressed in leaf *values* and converted to the reader's
        // record count (`values / valuesPerRow`: k for the LIST column, 1 for the flat
        // column), so every contender gets the same value-batch bytes. Used to control
        // for batch shape when comparing the fast path to the flat floor: the auto-sizer
        // gives the repeated column a smaller value batch (it also budgets for the level
        // arrays), so equalizing the size isolates batch-residency from the structural
        // decode difference.
        int batchValues = Integer.getInteger("perf.batchSize", 0);
        double sum = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path), context, config);
             ColumnReader col = batchValues > 0
                     ? reader.buildColumnReader(column).batchSize(Math.max(1, batchValues / valuesPerRow)).build()
                     : reader.columnReader(column)) {
            while (col.nextBatch()) {
                float[] values = col.getFloats();
                int n = col.getValueCount();
                for (int i = 0; i < n; i++) {
                    sum += values[i];
                }
            }
        }
        return sum;
    }

    /// Full scan through the row reader: materialize the vector [PqList] per row
    /// and fold its elements. This is the ergonomic per-row path — `floats()` views
    /// the `k` elements as a `List<Float>`; the fast-vs-baseline delta is the
    /// reconstruction the detector removes (the per-element boxing is common to
    /// both, so it cancels out of the delta).
    private static double sumRows(Path path, String field, HardwoodContext context,
                                  ReaderConfig config) throws IOException {
        double sum = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path), context, config);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                PqList vec = rows.getList(field);
                if (vec == null) {
                    continue;
                }
                List<Float> floats = vec.floats();
                int size = vec.size();
                for (int i = 0; i < size; i++) {
                    sum += floats.get(i);
                }
            }
        }
        return sum;
    }

    private static String dataDir() {
        String dir = System.getProperty("perf.dataDir");
        return dir == null || dir.isBlank() ? DEFAULT_DATA_DIR : dir;
    }

    private static long totalValues() {
        return Long.getLong("perf.totalValues", DEFAULT_TOTAL_VALUES);
    }

    /// Generates the corpus for every `k` (or the `-Dperf.k` subset) up front, in
    /// the launcher JVM, so the forks only read.
    private static void generate(int[] ks) throws IOException {
        Path dir = Path.of(dataDir());
        long totalValues = totalValues();
        for (int k : ks) {
            FixedSizeListFileGenerator.ensure(dir, k, totalValues);
        }
    }

    /// Writes the run's throughput denominator to `bench-meta-*.tsv` next to the
    /// results TSV (`values` = leaf floats per file, constant across `k`; `bytes` =
    /// their float payload), so make-fixedlist-bars-chart.py can turn each
    /// contender's ms/op into M values/s. The shared [BenchReport#writeMeta(String...)]
    /// appends the JVM and Hardwood build. No-op when `-Dperf.results` is unset.
    private static void writeMeta() {
        long values = totalValues();
        BenchReport.writeMeta("values", Long.toString(values), "bytes", Long.toString(values * 4L),
                "compression", System.getProperty("perf.compression", "UNCOMPRESSED").toUpperCase());
    }

    /// Correctness gate: for each `k`, the fast path and the reconstruction baseline
    /// must decode **bit-identical** values — every element, in order, through both
    /// readers — and both must agree with the flat-column floor. `fast == baseline`
    /// is asserted bit-for-bit (raw float bits, leaf order), streamed in lockstep so
    /// the whole column is never held in memory; the cross-path checks (row vs column
    /// vs flat) fold the values in different orders, so they allow a rounding epsilon.
    private static void gate(int[] ks) throws IOException {
        generate(ks);
        Path dir = Path.of(dataDir());
        System.out.println("Correctness gate — fast path vs reconstruction baseline vs flat floor:");
        try (HardwoodContext context = HardwoodContext.create()) {
            ReaderConfig fast = ReaderConfig.builder().option("hardwood.fixed-list-fast-path", "true").build();
            ReaderConfig base = ReaderConfig.builder().option("hardwood.fixed-list-fast-path", "false").build();
            long totalValues = totalValues();
            for (int k : ks) {
                Path listPath = FixedSizeListFileGenerator.listFile(dir, k, totalValues);
                Path flatPath = FixedSizeListFileGenerator.flatFile(dir, k, totalValues);
                // Bit-exact fast-vs-baseline comparison; the returned sum is folded from
                // the fast side and reused for the rounding-tolerant cross-path checks.
                double columnSum = compareColumn("k=" + k + " column fast vs baseline",
                        listPath, LIST_COLUMN, context, fast, base, k);
                double rowSum = compareRows("k=" + k + " row fast vs baseline",
                        listPath, LIST_FIELD, context, fast, base);
                double flat = sumColumn(flatPath, FLAT_COLUMN, context, fast, 1);
                requireClose("k=" + k + " column vs flat floor", columnSum, flat);
                requireClose("k=" + k + " row vs column", rowSum, columnSum);
                System.out.printf("  OK  k=%-5d sum=%s%n", k, columnSum);
            }
        }
        System.out.println("Gate passed — the fast path decodes bit-identical values to the baseline.");
    }

    /// Scans the LIST column with the fast path on and off in lockstep, asserting every
    /// leaf float is bit-for-bit equal (raw bits, so `-0.0`/`NaN` are not glossed over),
    /// and returns the fast side's value sum. A fixed record batch is forced on both
    /// readers so their batch boundaries align for the pairwise compare.
    private static double compareColumn(String label, Path path, String column,
                                        HardwoodContext context, ReaderConfig fast, ReaderConfig base,
                                        int valuesPerRow) throws IOException {
        int batchRecords = Math.max(1, (1 << 20) / Math.max(1, valuesPerRow));
        double sum = 0;
        long index = 0;
        try (ParquetFileReader fr = ParquetFileReader.open(InputFile.of(path), context, fast);
             ParquetFileReader br = ParquetFileReader.open(InputFile.of(path), context, base);
             ColumnReader fc = fr.buildColumnReader(column).batchSize(batchRecords).build();
             ColumnReader bc = br.buildColumnReader(column).batchSize(batchRecords).build()) {
            while (true) {
                boolean fastNext = fc.nextBatch();
                boolean baseNext = bc.nextBatch();
                if (fastNext != baseNext) {
                    throw new IllegalStateException(label + ": batch count differs after " + index + " values");
                }
                if (!fastNext) {
                    break;
                }
                int count = fc.getValueCount();
                if (count != bc.getValueCount()) {
                    throw new IllegalStateException(label + ": batch value count differs at value " + index);
                }
                float[] fastValues = fc.getFloats();
                float[] baseValues = bc.getFloats();
                for (int i = 0; i < count; i++, index++) {
                    if (Float.floatToRawIntBits(fastValues[i]) != Float.floatToRawIntBits(baseValues[i])) {
                        throw new IllegalStateException(
                                label + ": value " + index + " differs: " + fastValues[i] + " != " + baseValues[i]);
                    }
                    sum += fastValues[i];
                }
            }
        }
        return sum;
    }

    /// Scans the row reader with the fast path on and off in lockstep, asserting every
    /// element of every list is bit-for-bit equal, and returns the fast side's value sum.
    private static double compareRows(String label, Path path, String field,
                                      HardwoodContext context, ReaderConfig fast, ReaderConfig base) throws IOException {
        double sum = 0;
        long row = 0;
        try (ParquetFileReader fr = ParquetFileReader.open(InputFile.of(path), context, fast);
             ParquetFileReader br = ParquetFileReader.open(InputFile.of(path), context, base);
             RowReader fastRows = fr.rowReader();
             RowReader baseRows = br.rowReader()) {
            while (true) {
                boolean fastNext = fastRows.hasNext();
                boolean baseNext = baseRows.hasNext();
                if (fastNext != baseNext) {
                    throw new IllegalStateException(label + ": row count differs at row " + row);
                }
                if (!fastNext) {
                    break;
                }
                fastRows.next();
                baseRows.next();
                PqList fastVec = fastRows.getList(field);
                PqList baseVec = baseRows.getList(field);
                if ((fastVec == null) != (baseVec == null)) {
                    throw new IllegalStateException(label + ": null-list mismatch at row " + row);
                }
                if (fastVec != null) {
                    int size = fastVec.size();
                    if (size != baseVec.size()) {
                        throw new IllegalStateException(
                                label + ": list size differs at row " + row + " (" + size + " != " + baseVec.size() + ")");
                    }
                    List<Float> fastFloats = fastVec.floats();
                    List<Float> baseFloats = baseVec.floats();
                    for (int i = 0; i < size; i++) {
                        if (Float.floatToRawIntBits(fastFloats.get(i)) != Float.floatToRawIntBits(baseFloats.get(i))) {
                            throw new IllegalStateException(
                                    label + ": row " + row + " element " + i + " differs: "
                                            + fastFloats.get(i) + " != " + baseFloats.get(i));
                        }
                        sum += fastFloats.get(i);
                    }
                }
                row++;
            }
        }
        return sum;
    }

    private static void requireClose(String what, double a, double b) {
        double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
        if (Math.abs(a - b) > 1e-6 * scale) {
            throw new IllegalStateException("Mismatch (" + what + "): " + a + " vs " + b);
        }
    }

    private static int[] parseKs(String csv) {
        String[] parts = csv.split(",");
        int[] ks = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ks[i] = Integer.parseInt(parts[i].trim());
        }
        return ks;
    }

    public static void main(String[] args) throws Exception {
        // A single k (or comma list) can be selected with -Dperf.k, e.g. for the
        // two headline files generated at ~512 MB (`-Dperf.k=768 -Dperf.totalValues=128000000`).
        String kOverride = System.getProperty("perf.k");
        int[] ks = kOverride != null && !kOverride.isBlank() ? parseKs(kOverride) : K_SWEEP;

        if (Boolean.getBoolean("perf.gate")) {
            gate(ks);
            return;
        }
        generate(ks);
        writeMeta();

        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(FixedSizeListScanBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(2))
                .measurementTime(TimeValue.seconds(2))
                .forks(Integer.getInteger("perf.forks", 1));
        if (kOverride != null && !kOverride.isBlank()) {
            opts.param("k", kOverride.split(","));
        }
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the data directory, etc. jvmArgsAppend overwrites
        // on each call, so collect every forwarded prop and pass them in one call.
        List<String> forwarded = new ArrayList<>();
        System.getProperties().forEach((key, value) -> {
            if (((String) key).startsWith("perf.")) {
                forwarded.add("-D" + key + "=" + value);
            }
        });
        if (!forwarded.isEmpty()) {
            opts.jvmArgsAppend(forwarded.toArray(new String[0]));
        }
        // Optional profilers, e.g. -Dperf.prof=gc,stack or -Dperf.prof=perfnorm
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
        java.util.Collection<RunResult> results = new Runner(opts.build()).run();
        // ms/op per (contender, k); the k is folded into the contender label as
        // `columnFast[768]`, which make-fixedlist-chart.py pivots into the
        // speedup-vs-k curve.
        BenchReport.appendMsPerOpTsv(results, FixedSizeListScanBenchmark.class);
        printFloorComparison(results);
    }

    /// Prints each reader's fast-path time as a multiple of the columnar flat floor
    /// (`column/floor`, `row/floor`; `1.00x` = at the floor), so the "how close to a
    /// no-list decode" reference is visible without eyeballing the ms/op table. The
    /// floor is a single columnar read of the flat column — the fastest these bytes
    /// move — and both readers are measured against it.
    private static void printFloorComparison(java.util.Collection<RunResult> results) {
        Map<Integer, Map<String, Double>> byK = new TreeMap<>();
        for (RunResult result : results) {
            String benchmark = result.getParams().getBenchmark();
            String method = benchmark.substring(benchmark.lastIndexOf('.') + 1);
            String kParam = result.getParams().getParam("k");
            if (kParam == null) {
                continue;
            }
            byK.computeIfAbsent(Integer.parseInt(kParam), unused -> new HashMap<>())
                    .put(method, result.getPrimaryResult().getScore());
        }
        System.out.println();
        System.out.println("=== Fast path vs. flat-column decode floor (columnar - the fastest these bytes move) ===");
        System.out.printf("    %6s %12s %10s %11s %14s %11s%n",
                "k", "columnFast", "rowFast", "flatFloor", "column/floor", "row/floor");
        for (Map.Entry<Integer, Map<String, Double>> entry : byK.entrySet()) {
            Map<String, Double> byMethod = entry.getValue();
            Double floor = byMethod.get("flatFloor");
            Double columnFast = byMethod.get("columnFast");
            Double rowFast = byMethod.get("rowFast");
            if (floor == null || floor <= 0) {
                continue;
            }
            System.out.printf("    %6d %12.3f %10.3f %11.3f %13.2fx %10.2fx%n",
                    entry.getKey(),
                    columnFast == null ? Double.NaN : columnFast,
                    rowFast == null ? Double.NaN : rowFast,
                    floor,
                    columnFast == null ? Double.NaN : columnFast / floor,
                    rowFast == null ? Double.NaN : rowFast / floor);
        }
    }
}
