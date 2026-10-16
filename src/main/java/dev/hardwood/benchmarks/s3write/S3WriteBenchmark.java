/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3write;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
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

import dev.hardwood.benchmarks.BenchReport;
import dev.hardwood.benchmarks.write.MemoryOutputFile;
import dev.hardwood.benchmarks.write.WriteBenchmark;
import dev.hardwood.benchmarks.write.WriteFixture;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.s3.S3Credentials;
import dev.hardwood.s3.S3Source;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.WriterConfig;

/// S3 write benchmark: the taxi-shaped records of [WriteBenchmark] written to a local S3 endpoint
/// that emulates upload latency and bandwidth (`s3-env.sh`, port 18082), by Hardwood's column
/// writer through `S3Source.outputFile` and by parquet-java's `ExampleParquetWriter` through
/// Hadoop's S3A filesystem.
///
/// | Contender | Sink |
/// |---|---|
/// | `hardwoodS3` | `S3Source.outputFile`: up to four parts uploading in the background while the writer fills the next |
/// | `parquetJavaS3` | S3A with its default block upload (`fs.s3a.fast.upload.active.blocks` 4, likewise four parts uploading while writing continues), buffered on the heap. Creating the file with overwrite first probes the key, one extra round trip |
/// | `hardwoodMemory` | the same write to memory: the encode cost alone |
/// | `parquetJavaMemory` | the same write to memory |
///
/// Both S3 contenders use 8 MiB parts, S3A's `fs.s3a.multipart.size` lowered from its 64 MiB
/// default to match, and the same page targets (1 MiB or 20,000 rows), row-group target, dictionary
/// page limit and page checksums. The S3 time less the memory time of the same writer is what the
/// upload adds. Before timing, each S3 contender's object is read back and every column checked
/// against the fixture ([WriteBenchmark#checkReadBack]); `--gate` does only that. Run with
/// `run-s3-write.sh`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 4)
@Measurement(iterations = 4, time = 4)
@Fork(1)
public class S3WriteBenchmark {

    private static final int ROWS = Integer.getInteger("perf.rows", 4_000_000);
    private static final String ENDPOINT = System.getProperty("perf.s3.endpoint", "http://127.0.0.1:18082");
    private static final String BUCKET = "test-bucket";
    /// The Hardwood-written file of the gate, kept for `run-s3-write-python.sh`, whose PyArrow and
    /// DuckDB contenders rewrite exactly these values.
    static final String LOCAL_COPY = "target/s3-write-fixture.parquet";
    private static final int PART_SIZE = 8 << 20;
    private static final int PAGE_TARGET_BYTES = 1 << 20;
    private static final int PAGE_TARGET_ROWS = 20_000;
    private static final int DICTIONARY_PAGE_LIMIT_BYTES = 1 << 20;
    private static final long ROW_GROUP_TARGET_BYTES = 16L << 20;
    private static final String PARQUET_JAVA_SCHEMA = """
            message flat {
              required int64 id;
              required int64 pickup_ts (TIMESTAMP(MICROS,true));
              optional int32 passenger_count;
              required double fare;
              required binary payment_type (STRING);
              optional binary vendor (STRING);
            }
            """;

    @Param({ "ZSTD" })
    private String codec;

    private WriteFixture fixture;
    private FileSchema schema;
    private WriterConfig config;
    private S3Source source;
    private Configuration hadoopConf;
    private MessageType parquetJavaSchema;
    private MemoryOutputFile hardwoodMemoryOut;
    private ParquetMemoryOutputFile parquetJavaMemoryOut;

    @Setup(Level.Trial)
    public void setup() {
        fixture = new WriteFixture(ROWS);
        schema = WriteFixture.schema();
        config = hardwoodConfig(codec);
        source = source();
        hadoopConf = s3aConf();
        parquetJavaSchema = MessageTypeParser.parseMessageType(PARQUET_JAVA_SCHEMA);
        hardwoodMemoryOut = new MemoryOutputFile();
        parquetJavaMemoryOut = new ParquetMemoryOutputFile();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        source.close();
    }

    @Benchmark
    public long hardwoodS3() throws IOException {
        WriteBenchmark.writeColumnar(source.outputFile(BUCKET, "write/hardwood.parquet"), fixture, schema, config);
        return fixture.rows;
    }

    @Benchmark
    public long parquetJavaS3() throws IOException {
        writeGroups(ExampleParquetWriter.builder(new Path("s3a://" + BUCKET + "/write/parquet-java.parquet")),
                fixture, parquetJavaSchema, hadoopConf, codec);
        return fixture.rows;
    }

    @Benchmark
    public long hardwoodMemory() throws IOException {
        WriteBenchmark.writeColumnar(hardwoodMemoryOut, fixture, schema, config);
        return hardwoodMemoryOut.position();
    }

    @Benchmark
    public long parquetJavaMemory() throws IOException {
        writeGroups(ExampleParquetWriter.builder(parquetJavaMemoryOut), fixture, parquetJavaSchema, hadoopConf, codec);
        return parquetJavaMemoryOut.bytes.size();
    }

    private static WriterConfig hardwoodConfig(String codec) {
        return WriterConfig.builder()
                .pageTargetBytes(PAGE_TARGET_BYTES)
                .pageTargetRows(PAGE_TARGET_ROWS)
                .rowGroupBufferTargetBytes(ROW_GROUP_TARGET_BYTES)
                .codec(CompressionCodec.valueOf(codec))
                .build();
    }

    private static S3Source source() {
        return S3Source.builder()
                .endpoint(ENDPOINT)
                .region("us-east-1")
                .pathStyle(true)
                .credentials(S3Credentials.of("access", "secret"))
                .uploadPartSize(PART_SIZE)
                .build();
    }

    private static Configuration s3aConf() {
        Configuration conf = new Configuration();
        conf.set("fs.s3a.endpoint", ENDPOINT);
        conf.set("fs.s3a.endpoint.region", "us-east-1");
        conf.setBoolean("fs.s3a.path.style.access", true);
        conf.setBoolean("fs.s3a.connection.ssl.enabled", false);
        conf.set("fs.s3a.access.key", "access");
        conf.set("fs.s3a.secret.key", "secret");
        conf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
        conf.set("fs.s3a.multipart.size", Integer.toString(PART_SIZE));
        conf.set("fs.s3a.fast.upload.buffer", "array");
        return conf;
    }

    /// parquet-java's record-shaped write: one `SimpleGroup` per record, as in the main repository's
    /// `FlatWriteBenchmark`.
    private static void writeGroups(ExampleParquetWriter.Builder builder, WriteFixture f, MessageType schema,
            Configuration conf, String codec) throws IOException {
        try (ParquetWriter<Group> writer = builder
                .withConf(conf)
                .withType(schema)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withCompressionCodec(CompressionCodecName.valueOf(codec))
                .withPageSize(PAGE_TARGET_BYTES)
                .withPageRowCountLimit(PAGE_TARGET_ROWS)
                .withRowGroupSize(ROW_GROUP_TARGET_BYTES)
                .withDictionaryEncoding(true)
                .withDictionaryPageSize(DICTIONARY_PAGE_LIMIT_BYTES)
                .withPageWriteChecksumEnabled(true)
                .withWriterVersion(WriterVersion.PARQUET_1_0)
                .build()) {
            SimpleGroupFactory groups = new SimpleGroupFactory(schema);
            for (int b = 0; b < f.batchCount(); b++) {
                for (int r = 0; r < f.id[b].length; r++) {
                    Group group = groups.newGroup()
                            .append("id", f.id[b][r])
                            .append("pickup_ts", f.pickupMicros[b][r])
                            .append("fare", f.fare[b][r])
                            .append("payment_type", f.paymentType[b][r]);
                    if (!f.passengerCountNulls[b][r]) {
                        group.append("passenger_count", f.passengerCount[b][r]);
                    }
                    if (!f.vendorNulls[b][r]) {
                        group.append("vendor", f.vendor[b][r]);
                    }
                    writer.write(group);
                }
            }
        }
    }

    /// Writes each S3 contender's object once and checks every column of it read back through
    /// Hardwood against the fixture. Returns the two objects' compressed column-chunk bytes.
    private static long[] gate(WriteFixture f, String codec) throws IOException {
        long[] sizes = new long[2];
        try (S3Source source = source()) {
            WriteBenchmark.writeColumnar(source.outputFile(BUCKET, "write/hardwood.parquet"), f, WriteFixture.schema(),
                    hardwoodConfig(codec));
            writeGroups(ExampleParquetWriter.builder(new Path("s3a://" + BUCKET + "/write/parquet-java.parquet")), f,
                    MessageTypeParser.parseMessageType(PARQUET_JAVA_SCHEMA), s3aConf(), codec);
            sizes[0] = WriteBenchmark.checkReadBack(source.inputFile(BUCKET, "write/hardwood.parquet"), f, codec + ", hardwoodS3");
            sizes[1] = WriteBenchmark.checkReadBack(source.inputFile(BUCKET, "write/parquet-java.parquet"), f, codec + ", parquetJavaS3");
        }
        MemoryOutputFile local = new MemoryOutputFile();
        WriteBenchmark.writeColumnar(local, f, WriteFixture.schema(), hardwoodConfig(codec));
        Files.write(Paths.get(LOCAL_COPY), local.toByteArray());
        System.out.printf("Gate passed [%s] — both S3 objects read back %,d rows, every column matching "
                + "(%,d chunk bytes Hardwood, %,d parquet-java).%n", codec, f.rows, sizes[0], sizes[1]);
        return sizes;
    }

    /// parquet-java's `OutputFile` over a heap buffer, for the memory contender.
    private static final class ParquetMemoryOutputFile implements OutputFile {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public PositionOutputStream create(long blockSizeHint) {
            return createOrOverwrite(blockSizeHint);
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) {
            bytes.reset();
            return new PositionOutputStream() {
                @Override
                public long getPos() {
                    return bytes.size();
                }

                @Override
                public void write(int b) {
                    bytes.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    bytes.write(b, off, len);
                }
            };
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0;
        }
    }

    public static void main(String[] args) throws Exception {
        String codec = System.getProperty("perf.param", "ZSTD");
        long[] sizes = gate(new WriteFixture(ROWS), codec);
        if (Boolean.getBoolean("perf.gate")) {
            return;
        }
        BenchReport.writeMeta("rows", Long.toString(ROWS), "bytes.hardwood", Long.toString(sizes[0]),
                "bytes.parquetJava", Long.toString(sizes[1]), "partBytes", Integer.toString(PART_SIZE),
                "latencyMs", System.getProperty("perf.s3.latencyMs", "?"),
                "bandwidthKBps", System.getProperty("perf.s3.bandwidthKBps", "?"));
        ChainedOptionsBuilder opts = new OptionsBuilder()
                .include(BenchReport.includePattern(S3WriteBenchmark.class))
                .warmupIterations(Integer.getInteger("perf.warmup", 2))
                .measurementIterations(Integer.getInteger("perf.meas", 4))
                .warmupTime(TimeValue.seconds(Integer.getInteger("perf.time", 4)))
                .measurementTime(TimeValue.seconds(Integer.getInteger("perf.time", 4)))
                .forks(Integer.getInteger("perf.forks", 1))
                .param("codec", codec);
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
        BenchReport.appendMsPerOpTsv(results, S3WriteBenchmark.class);
    }
}
