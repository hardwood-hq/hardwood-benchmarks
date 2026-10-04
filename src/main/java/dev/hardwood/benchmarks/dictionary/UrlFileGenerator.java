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

/// Generates the dictionary-filter benchmark file: one required string column `url`
/// whose values are drawn uniformly at random from [#DISTINCT] URLs of [#url]'s shape,
/// about 40 bytes each. The pool's bytes are far below parquet-java's dictionary page
/// limit, so every data page is dictionary-encoded; the gate checks that rather than
/// assuming it.
public final class UrlFileGenerator {

    /// Distinct values in the `url` column.
    public static final int DISTINCT = 1_000;

    private UrlFileGenerator() {
    }

    /// The `i`-th value of the pool.
    public static String url(int i) {
        return String.format("https://example.com/catalog/items/%05d", i);
    }

    /// Writes the file if it is not already present. Cached across runs, keyed on the
    /// path only, so a caller changing the row count must encode it in the file name.
    public static void ensure(Path file, long rows) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-row dictionary-encoded URL file at %s...%n", rows, file);

        Schema schema = SchemaBuilder.record("visit").fields()
                .requiredString("url")
                .endRecord();
        String[] pool = new String[DISTINCT];
        for (int i = 0; i < DISTINCT; i++) {
            pool[i] = url(i);
        }

        Configuration conf = new Configuration();
        conf.set("parquet.writer.version", "v2");
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());

        Random rng = new Random(42);
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hPath)
                .withSchema(schema)
                .withConf(conf)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withDictionaryEncoding(true)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false)
                .build()) {
            for (long i = 0; i < rows; i++) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("url", pool[rng.nextInt(DISTINCT)]);
                writer.write(record);
            }
        }
        System.out.printf("Generated %s (%,d MB)%n", file, Files.size(file) / 1_000_000);
    }
}
