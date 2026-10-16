/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.binary;

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

/// Generates the binary-scan benchmark file: two string columns without a dictionary, so every
/// data page holds its values `PLAIN`, length-prefixed. `text` is required, of 4 to 60 random
/// lowercase ASCII letters; `note` is optional, null in one row of ten, of 8 to 24 letters.
/// Written by parquet-java with `V1` pages, which keep `PLAIN` for a string column without a
/// dictionary (`V2` pages would give it `DELTA_BYTE_ARRAY`), compressed with SNAPPY.
public final class TextFileGenerator {

    private TextFileGenerator() {
    }

    /// Writes the file if it is not already present. Cached across runs, keyed on the path only,
    /// so a caller changing the row count must encode it in the file name.
    public static void ensure(Path file, long rows) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-row PLAIN string file at %s...%n", rows, file);

        Schema schema = SchemaBuilder.record("doc").fields()
                .requiredString("text")
                .optionalString("note")
                .endRecord();

        Configuration conf = new Configuration();
        conf.set("parquet.writer.version", "v1");
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString());

        Random rng = new Random(42);
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hPath)
                .withSchema(schema)
                .withConf(conf)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withDictionaryEncoding(false)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false)
                .build()) {
            for (long i = 0; i < rows; i++) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("text", letters(rng, 4 + rng.nextInt(57)));
                record.put("note", rng.nextInt(10) == 0 ? null : letters(rng, 8 + rng.nextInt(17)));
                writer.write(record);
            }
        }
        System.out.printf("Generated %s (%,d MB)%n", file, Files.size(file) / 1_000_000);
    }

    private static String letters(Random rng, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('a' + rng.nextInt(26));
        }
        return new String(chars);
    }
}
