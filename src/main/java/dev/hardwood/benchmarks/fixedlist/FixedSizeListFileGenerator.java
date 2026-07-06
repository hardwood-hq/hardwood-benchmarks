/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.fixedlist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.avro.AvroWriteSupport;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/// Generates the corpus for [FixedSizeListScanBenchmark]: fixed-shape float32
/// vectors written as a **required** `LIST` of required elements under the 3-level
/// compliant list structure —
/// `required group vec (LIST) { repeated group list { required float element } }`
/// (leaf `vec.list.element`, max repetition level 1, max definition level 1). This
/// is the shape the reader's fast path accelerates; the old 2-level structure
/// (`repeated float array`) is not accelerated even though its levels are
/// identical, so `write-old-list-structure` is explicitly disabled.
///
/// For each `k` two files are written (both skipped if already present):
///
///   - `fixed_size_list_k{k}_n{totalValues}.parquet`       the `LIST<float32>` vectors.
///   - `fixed_size_list_k{k}_n{totalValues}_flat.parquet`  the identical values as a plain
///                                          `float32` column — the decode floor.
///
/// Both files hold the same values, so the flat floor decodes the exact same bytes
/// as the list column. The page format follows `-Dperf.pageVersion` (`v2` default,
/// `v1` also fast-pathed); no dictionary and no compression, so the benchmark
/// measures level handling and value decode rather than codec cost.
public final class FixedSizeListFileGenerator {

    private FixedSizeListFileGenerator() {
    }

    /// Both `k` and `totalValues` are in the filename so that changing either
    /// regenerates rather than silently reusing a stale file.
    public static Path listFile(Path dir, int k, long totalValues) {
        return dir.resolve("fixed_size_list_k" + k + "_n" + totalValues + ".parquet");
    }

    public static Path flatFile(Path dir, int k, long totalValues) {
        return dir.resolve("fixed_size_list_k" + k + "_n" + totalValues + "_flat.parquet");
    }

    /// Writes the vector file and the matching flat floor for `k` if either is
    /// missing. `totalValues` leaf floats are written (`rows = totalValues / k`),
    /// so files are comparable across `k`; ~128M leaf floats is ~512 MB. The count
    /// is part of the filename, so a different `totalValues` regenerates.
    public static void ensure(Path dir, int k, long totalValues) throws IOException {
        Path listPath = listFile(dir, k, totalValues);
        Path flatPath = flatFile(dir, k, totalValues);
        if (present(listPath) && present(flatPath)) {
            return;
        }
        Files.createDirectories(dir);
        long rows = totalValues / k;
        if (rows == 0) {
            throw new IllegalArgumentException("totalValues " + totalValues + " too small for k=" + k);
        }
        int count = Math.toIntExact(rows * k);
        System.out.printf("Generating fixed-size-list corpus k=%d (%,d rows x %d float32)...%n", k, rows, k);

        // Deterministic values shared by both files so the flat floor decodes the
        // exact same bytes as the list column.
        Random rng = new Random(1234L + k);
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = (float) rng.nextGaussian();
        }

        writeList(listPath, k, rows, values);
        writeFlat(flatPath, values);
        System.out.printf("  wrote %s (%,d MB) + flat floor%n",
                listPath.getFileName(), Files.size(listPath) / 1_000_000);
    }

    /// Required `array<float>` with a required element. With the 3-level compliant
    /// structure this maps to
    /// `required group vec (LIST) { repeated group list { required float element } }`
    /// (leaf `vec.list.element`, max rep 1, max def 1).
    private static void writeList(Path path, int k, long rows, float[] values) throws IOException {
        Schema schema = SchemaBuilder.record("vectors").fields()
                .name("vec").type().array().items().floatType().noDefault()
                .endRecord();
        try (ParquetWriter<GenericRecord> writer = writer(path, schema)) {
            int idx = 0;
            for (long r = 0; r < rows; r++) {
                List<Float> vec = new ArrayList<>(k);
                for (int j = 0; j < k; j++) {
                    vec.add(values[idx++]);
                }
                GenericRecord record = new GenericData.Record(schema);
                record.put("vec", vec);
                writer.write(record);
            }
        }
    }

    /// The identical values as a plain required `float` column — the decode floor.
    private static void writeFlat(Path path, float[] values) throws IOException {
        Schema schema = SchemaBuilder.record("flat").fields()
                .requiredFloat("value")
                .endRecord();
        try (ParquetWriter<GenericRecord> writer = writer(path, schema)) {
            for (float value : values) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("value", value);
                writer.write(record);
            }
        }
    }

    private static ParquetWriter<GenericRecord> writer(Path path, Schema schema) throws IOException {
        Configuration conf = new Configuration();
        // Old (2-level) list structure: `repeated float array`, giving max def 1
        // for a required list rather than the 3-level max def 3.
        // 3-level compliant list (`group list { element }`) — the structure the
        // reader's fast path accelerates. The 2-level old structure is not.
        conf.setBoolean(AvroWriteSupport.WRITE_OLD_LIST_STRUCTURE, false);
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(path.toAbsolutePath().toString());
        return AvroParquetWriter.<GenericRecord>builder(hPath)
                .withSchema(schema)
                .withConf(conf)
                // PARQUET_2_0 -> DataPageV2, PARQUET_1_0 -> DataPageV1; the fast path
                // detects both. Default V2; -Dperf.pageVersion=v1 selects V1.
                .withWriterVersion(writerVersion())
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
                .withDictionaryEncoding(false)
                .withRowGroupSize(512L * 1024 * 1024)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false)
                .build();
    }

    private static WriterVersion writerVersion() {
        String version = System.getProperty("perf.pageVersion", "v2");
        return "v1".equalsIgnoreCase(version) ? WriterVersion.PARQUET_1_0 : WriterVersion.PARQUET_2_0;
    }

    private static boolean present(Path path) throws IOException {
        return Files.exists(path) && Files.size(path) > 0;
    }
}
