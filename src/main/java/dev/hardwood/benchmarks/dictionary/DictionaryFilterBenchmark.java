/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.dictionary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.column.EncodingStats;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.util.HadoopInputFile;
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
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Dictionary-space filter benchmark: `SELECT url WHERE url IN (5 values)` over a
/// dictionary-encoded column of about 40-byte URLs.
///
/// Witnesses dictionary-space predicate evaluation (hardwood-hq/hardwood#859): a
/// binary predicate is decided once per dictionary entry and rows are answered by
/// their entry id, rather than comparing every row's bytes against every `IN` member.
/// The row reader runs the predicate on its column worker; reading the matched values
/// with `getString` resolves them through the same entry ids, so the read also needs
/// no per-value byte view of the column.
///
/// `hardwoodRowInWide` and `hardwoodRowLtWide` read a second file whose `url` dictionary
/// holds 200,000 entries, filtered on `url IN` 50 values and on `url <` a literal that
/// about 2 % of the rows sort below. Setting up a dictionary that size is a large share
/// of a selective read, which the range contender witnesses
/// (hardwood-hq/hardwood#1507).
///
/// The benchmark is regression-only: no publication cites it, and each dataset has one
/// size, so runs of any two Hardwood versions read the same files.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class DictionaryFilterBenchmark {

    private static final long ROWS = 5_000_000L;
    private static final Path FILE = Path.of("target/dictionary_filter_benchmark_" + ROWS + ".parquet");
    private static final Path WIDE_FILE = Path.of("target/dictionary_filter_wide_benchmark_" + ROWS + ".parquet");

    /// The literal `hardwoodRowLtWide` compares `url` against: the 2,000th of the wide pool's
    /// 200,000 values, which sort in pool order.
    private static final String WIDE_LITERAL = UrlFileGenerator.wideUrl(2_000);

    /// The `IN` members: five of the pool's values, about 0.5 % of the rows.
    private static final String[] MEMBERS = {
            UrlFileGenerator.url(7), UrlFileGenerator.url(191), UrlFileGenerator.url(404),
            UrlFileGenerator.url(613), UrlFileGenerator.url(998) };

    /// The wide file's `IN` members: 50 of its pool's values, about 0.025 % of the rows.
    private static final String[] WIDE_MEMBERS = wideMembers();

    /// Matched rows, and the summed lengths of their values.
    public record Result(long count, long length) {}

    private FilterPredicate filter;
    private FilterPredicate wideFilter;
    private FilterPredicate wideRangeFilter;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        UrlFileGenerator.ensure(FILE, ROWS);
        UrlFileGenerator.ensureWide(WIDE_FILE, ROWS);
        filter = predicate();
        wideFilter = widePredicate();
        wideRangeFilter = wideRangePredicate();
    }

    private static String[] wideMembers() {
        String[] members = new String[50];
        for (int i = 0; i < members.length; i++) {
            members[i] = UrlFileGenerator.wideUrl(i * 4_000 + 7);
        }
        return members;
    }

    @SuppressWarnings("deprecation")
    private static FilterPredicate widePredicate() {
        return FilterPredicate.inStrings("url", WIDE_MEMBERS);
    }

    private static FilterPredicate wideRangePredicate() {
        return FilterPredicate.lt("url", WIDE_LITERAL);
    }

    /// `url IN (MEMBERS)`, spelled `inStrings` because 1.0 has no `in(String, String...)`
    /// and a regression run compares against 1.0.
    @SuppressWarnings("deprecation")
    private static FilterPredicate predicate() {
        return FilterPredicate.inStrings("url", MEMBERS);
    }

    /// Row reader projecting `url`, filtered on `url IN (...)`, reading each match as a `String`.
    @Benchmark
    public Result hardwoodRowIn() throws IOException {
        return hardwoodRowIn(FILE, filter);
    }

    /// Row reader projecting the wide file's `url`, filtered on `url IN (WIDE_MEMBERS)`.
    @Benchmark
    public Result hardwoodRowInWide() throws IOException {
        return hardwoodRowIn(WIDE_FILE, wideFilter);
    }

    /// Row reader projecting the wide file's `url`, filtered on `url < WIDE_LITERAL`.
    @Benchmark
    public Result hardwoodRowLtWide() throws IOException {
        return hardwoodRowIn(WIDE_FILE, wideRangeFilter);
    }

    private static Result hardwoodRowIn(Path file, FilterPredicate filter) throws IOException {
        long count = 0;
        long length = 0;
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context);
             RowReader rows = reader.buildRowReader()
                     .projection(ColumnProjection.columns("url"))
                     .filter(filter)
                     .build()) {
            while (rows.hasNext()) {
                rows.next();
                length += rows.getString(0).length();
                count++;
            }
        }
        return new Result(count, length);
    }

    /// Correctness gate, outside JMH: every data page of `url` must be dictionary-encoded,
    /// so the benchmark measures what it claims to, and each filtered Hardwood read must
    /// agree with a parquet-java scan that applies the predicate itself.
    private static void gate() throws IOException {
        Set<String> members = Set.of(MEMBERS);
        Set<String> wideMembers = Set.of(WIDE_MEMBERS);
        gate("hardwoodRowIn", FILE, predicate(), members::contains);
        gate("hardwoodRowInWide", WIDE_FILE, widePredicate(), wideMembers::contains);
        // The URLs are ASCII, so String order is the column's byte order
        gate("hardwoodRowLtWide", WIDE_FILE, wideRangePredicate(), url -> url.compareTo(WIDE_LITERAL) < 0);
    }

    /// Gates one contender, whose filter keeps the rows whose `url` satisfies `matches`.
    private static void gate(String contender, Path file, FilterPredicate filter, Predicate<String> matches)
            throws IOException {
        Configuration conf = new Configuration();
        HadoopInputFile input = HadoopInputFile.fromPath(
                new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString()), conf);
        try (org.apache.parquet.hadoop.ParquetFileReader reader =
                     org.apache.parquet.hadoop.ParquetFileReader.open(input)) {
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                for (ColumnChunkMetaData column : block.getColumns()) {
                    if (!column.getPath().toDotString().equals("url")) {
                        continue;
                    }
                    EncodingStats stats = column.getEncodingStats();
                    if (stats == null || !stats.hasDictionaryPages() || stats.hasNonDictionaryEncodedPages()) {
                        throw new IllegalStateException(
                                "Column " + column.getPath() + " has pages that are not dictionary-encoded: " + stats);
                    }
                }
            }
        }

        long count = 0;
        long length = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(input)
                .withConf(conf).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                String url = record.get("url").toString();
                if (matches.test(url)) {
                    count++;
                    length += url.length();
                }
            }
        }
        Result ref = new Result(count, length);
        Result actual = hardwoodRowIn(file, filter);
        if (!actual.equals(ref)) {
            throw new IllegalStateException(
                    contender + " disagrees with parquet-java: " + actual + " vs " + ref);
        }
        System.out.printf("Gate passed for %s: every url page is dictionary-encoded, and the filtered "
                + "Hardwood row reader agrees with parquet-java (%d rows, %d bytes).%n",
                contender, ref.count(), ref.length());
    }

    public static void main(String[] args) throws Exception {
        UrlFileGenerator.ensure(FILE, ROWS);
        UrlFileGenerator.ensureWide(WIDE_FILE, ROWS);
        System.out.printf("Datasets: %,d rows, %,d distinct URLs (%s); %,d rows, %,d distinct URLs (%s)%n",
                ROWS, UrlFileGenerator.DISTINCT, FILE, ROWS, UrlFileGenerator.WIDE_DISTINCT, WIDE_FILE);
        // Gate-check mode: verify Hardwood agrees with parquet-java, then exit — no JMH.
        if (Boolean.getBoolean("perf.gate")) {
            gate();
            return;
        }
        BenchReport.writeRunParams(ROWS, Files.size(FILE), "distinct", Integer.toString(UrlFileGenerator.DISTINCT));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(DictionaryFilterBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
        // JMH forks a fresh JVM that does not inherit -D props; forward perf.* so
        // the forked benchmark sees the same settings.
        System.getProperties().forEach((k, v) -> {
            if (((String) k).startsWith("perf.")) {
                opts.jvmArgsAppend("-D" + k + "=" + v);
            }
        });
        // Optional profilers, e.g. -Dperf.prof=gc,stack
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
        BenchReport.appendMsPerOpTsv(results, DictionaryFilterBenchmark.class);
    }
}
