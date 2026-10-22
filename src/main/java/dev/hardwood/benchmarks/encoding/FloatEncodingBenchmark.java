/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.OutputFile;
import dev.hardwood.Validity;
import dev.hardwood.benchmarks.BenchContext;
import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.TaxiDataDownloader;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnEncoding;
import dev.hardwood.writer.ColumnWriter;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.WriterConfig;

/// Floating-point encoding benchmark (regression-only): every `DOUBLE` column of one month of
/// NYC taxi trips, written and read by Hardwood under each encoding a `DOUBLE` column can take,
/// uncompressed and with ZSTD.
///
/// `AUTO` is what a column gets by default, dictionary or `PLAIN` per chunk; `PLAIN`,
/// `BYTE_STREAM_SPLIT` and `ALP` are named per file. Two contenders per pair:
/// `hardwoodRead` scans every column of the file with the column reader, `hardwoodWrite`
/// writes the columns to memory with the column writer. The compressed column-chunk bytes of
/// each pair are recorded in the meta sidecar (`bytes.<ENCODING>_<CODEC>`), so a version that
/// encodes larger shows there even where the time does not move.
///
/// The read files are written once, by the Hardwood version that runs first, and reused after,
/// so the versions of a regression run read identical bytes. A version without `ALP` fails to
/// build this package and runs nothing of it.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FloatEncodingBenchmark {

    private static final int ROWS = Integer.getInteger("perf.rows", 1_000_000);
    private static final YearMonth MONTH = YearMonth.of(2025, 1);
    private static final Path DIR = Path.of("target/float-encodings");
    /// Named as constants rather than strings, so a Hardwood version without `ALP` fails to
    /// compile this package, which `run-regression.sh` records as not run, rather than failing
    /// at runtime.
    private static final ColumnEncoding[] ENCODINGS = {
            ColumnEncoding.AUTO, ColumnEncoding.PLAIN, ColumnEncoding.BYTE_STREAM_SPLIT, ColumnEncoding.ALP };
    private static final String[] CODECS = { "UNCOMPRESSED", "ZSTD" };
    /// As `run-write.sh`: several row groups per file, so no column chunk holds the whole run.
    private static final long ROW_GROUP_TARGET_BYTES = 16L << 20;

    @Param({ "AUTO", "PLAIN", "BYTE_STREAM_SPLIT", "ALP" })
    private String encoding;

    @Param({ "UNCOMPRESSED", "ZSTD" })
    private String codec;

    private TaxiDoubles data;
    private FileSchema schema;
    private WriterConfig config;
    private MemoryOutputFile out;
    private Path file;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        data = load();
        schema = data.schema();
        config = config(encoding, codec);
        out = new MemoryOutputFile();
        file = ensureFile(data, encoding, codec);
    }

    /// Column reader over every column of the file, summing each value's raw bits.
    @Benchmark
    public TaxiDoubles.Checksum hardwoodRead() throws IOException {
        return scan(file, data.names);
    }

    /// Column writer over every column, to memory.
    @Benchmark
    public long hardwoodWrite() throws IOException {
        write(out, data, schema, config);
        return out.position();
    }

    private static TaxiDoubles load() throws IOException {
        List<Path> files = TaxiDataDownloader.ensure(MONTH, MONTH);
        return TaxiDoubles.load(files.getFirst(), ROWS);
    }

    private static WriterConfig config(String encoding, String codec) {
        return WriterConfig.builder()
                .encoding(ColumnEncoding.valueOf(encoding))
                .codec(CompressionCodec.valueOf(codec))
                .rowGroupBufferTargetBytes(ROW_GROUP_TARGET_BYTES)
                .build();
    }

    private static Path ensureFile(TaxiDoubles data, String encoding, String codec) throws IOException {
        Path path = DIR.resolve("taxi_doubles_" + MONTH + "_" + data.rows + "_" + encoding + "_" + codec + ".parquet");
        if (Files.exists(path) && Files.size(path) > 0) {
            return path;
        }
        Files.createDirectories(DIR);
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        write(OutputFile.of(partial), data, data.schema(), config(encoding, codec));
        Files.move(partial, path);
        return path;
    }

    static void write(OutputFile out, TaxiDoubles data, FileSchema schema, WriterConfig config) throws IOException {
        try (HardwoodContext context = BenchContext.create();
             ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config, context)) {
            ColumnWriter columns = writer.columnWriter();
            for (int b = 0; b < data.batchCount(); b++) {
                int batchIndex = b;
                columns.writeBatch(batch -> {
                    for (int c = 0; c < data.names.length; c++) {
                        if (data.nulls[c] == null) {
                            batch.doubles(c, data.values[c][batchIndex]);
                        }
                        else {
                            batch.doubles(c, data.values[c][batchIndex], data.nulls[c][batchIndex]);
                        }
                    }
                });
            }
        }
    }

    static TaxiDoubles.Checksum scan(Path file, String[] names) throws IOException {
        try (HardwoodContext context = BenchContext.create();
             ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context)) {
            return scan(reader, names);
        }
    }

    private static TaxiDoubles.Checksum scan(ParquetFileReader reader, String[] names) throws IOException {
        TaxiDoubles.Checksum sum = new TaxiDoubles.Checksum(names.length);
        for (int c = 0; c < names.length; c++) {
            try (ColumnReader column = reader.columnReader(names[c])) {
                while (column.nextBatch()) {
                    double[] values = column.getDoubles();
                    Validity validity = column.getLeafValidity();
                    int count = column.getValueCount();
                    for (int i = 0; i < count; i++) {
                        sum.add(c, validity.isNull(i), values[i]);
                    }
                }
            }
        }
        return sum;
    }

    /// The compressed size of every column chunk: what the encoding and codec produced,
    /// without the footer, whose `created_by` differs between versions.
    private static long compressedChunkBytes(FileMetaData metadata) {
        long bytes = 0;
        for (RowGroup rowGroup : metadata.rowGroups()) {
            for (ColumnChunk chunk : rowGroup.columns()) {
                bytes += chunk.metaData().totalCompressedSize();
            }
        }
        return bytes;
    }

    /// Writes every pair to memory, reads it back, and checks it against the data: the same
    /// raw bits and null count per column, and a named encoding on every column chunk.
    /// Returns the compressed column-chunk bytes of each pair, as `bytes.<ENCODING>_<CODEC>`
    /// key/value pairs.
    private static List<String> gate(TaxiDoubles data) throws IOException {
        TaxiDoubles.Checksum expected = data.checksum();
        List<String> sizes = new ArrayList<>();
        for (ColumnEncoding policy : ENCODINGS) {
            String encoding = policy.name();
            for (String codec : CODECS) {
                MemoryOutputFile out = new MemoryOutputFile();
                write(out, data, data.schema(), config(encoding, codec));
                try (HardwoodContext context = BenchContext.create();
                     ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())), context)) {
                    FileMetaData metadata = reader.getFileMetaData();
                    if (policy != ColumnEncoding.AUTO) {
                        Encoding named = Encoding.valueOf(encoding);
                        for (RowGroup rowGroup : metadata.rowGroups()) {
                            for (ColumnChunk chunk : rowGroup.columns()) {
                                if (!chunk.metaData().encodings().contains(named)) {
                                    throw new IllegalStateException(encoding + "/" + codec + ": column chunk "
                                            + chunk.metaData().pathInSchema() + " declares "
                                            + chunk.metaData().encodings());
                                }
                            }
                        }
                    }
                    TaxiDoubles.Checksum actual = scan(reader, data.names);
                    if (!actual.equals(expected)) {
                        throw new IllegalStateException(encoding + "/" + codec + " reads back " + actual
                                + ", expected " + expected);
                    }
                    long bytes = compressedChunkBytes(metadata);
                    sizes.add("bytes." + encoding + "_" + codec);
                    sizes.add(Long.toString(bytes));
                    System.out.printf("  %-17s %-12s %,12d bytes%n", encoding, codec, bytes);
                }
            }
        }
        return sizes;
    }

    public static void main(String[] args) throws Exception {
        TaxiDoubles data = load();
        System.out.printf("Dataset: %,d rows of %d DOUBLE columns from %s%n", data.rows, data.names.length, MONTH);
        List<String> sizes = gate(data);
        System.out.println("Gate passed: every pair reads back bit for bit, under the encoding it names.");
        if (Boolean.getBoolean("perf.gate")) {
            return;
        }
        List<String> meta = new ArrayList<>(List.of("rows", Integer.toString(data.rows)));
        meta.addAll(sizes);
        BenchReport.writeMeta(meta.toArray(new String[0]));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(FloatEncodingBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 3))
                .measurementIterations(Integer.getInteger("perf.meas", 5))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 2)))
                .forks(Integer.getInteger("perf.forks", 1));
        String encodings = System.getProperty("perf.encoding");
        if (encodings != null && !encodings.isBlank()) {
            opts.param("encoding", encodings.split(","));
        }
        String codecs = System.getProperty("perf.codec");
        if (codecs != null && !codecs.isBlank()) {
            opts.param("codec", codecs.split(","));
        }
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
        BenchReport.appendMsPerOpTsv(results, FloatEncodingBenchmark.class);
    }
}
