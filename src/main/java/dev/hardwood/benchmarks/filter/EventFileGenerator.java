/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.filter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/// Generates the benchmark file: a time-ordered event log clustered on
/// `event_time`, the layout where predicate push-down actually works.
///
/// `event_time` increases monotonically (so a range predicate prunes whole
/// row groups and pages); `amount`/`latency_ms`/`category` are the payload that
/// carries the decode cost. Written with a column index (parquet-mr default) and
/// **no bloom filters** — the range predicate is served entirely by
/// row-group/column-index statistics, so bloom filters would change nothing.
public final class EventFileGenerator {

    private EventFileGenerator() {
    }

    /// Writes the file if it is not already present. Cached across runs.
    public static void ensure(Path file, long rows) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-row time-clustered file at %s...%n", rows, file);

        Schema schema = SchemaBuilder.record("event").fields()
                .requiredLong("event_time")
                .requiredDouble("amount")
                .requiredDouble("latency_ms")
                .requiredInt("category")
                .endRecord();

        Configuration conf = new Configuration();
        conf.set("parquet.writer.version", "v2");          // default ~1MB pages -> a sane column index at scale
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());

        Random rng = new Random(42);
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hPath)
                .withSchema(schema)
                .withConf(conf)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupSize(128L * 1024 * 1024)       // realistic ~128MB row groups
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false)
                .build()) {
            for (long i = 0; i < rows; i++) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("event_time", i);               // monotonic -> clustered
                record.put("amount", rng.nextDouble() * 1000.0);
                record.put("latency_ms", rng.nextDouble() * 500.0);
                record.put("category", rng.nextInt(50));
                writer.write(record);
            }
        }
        System.out.printf("Generated %s (%,d MB)%n", file, Files.size(file) / 1_000_000);
    }
}
