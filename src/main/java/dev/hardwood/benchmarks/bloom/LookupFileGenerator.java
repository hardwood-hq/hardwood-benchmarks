/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.bloom;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/// Generates the bloom-filter point-lookup corpus: a **unique, unclustered 64-bit
/// key** column plus a payload column, written once into a bloom-filter-bearing file
/// and a statistics-only twin holding identical rows.
///
/// Why generated rather than real data: the benchmark needs a column where *only* a
/// bloom filter can prune, which requires high cardinality **and** no clustering. No
/// column in the TLC taxi data is both — its highest-cardinality column
/// (`tpep_pickup_datetime`, 48% unique) is time-ordered, so min/max statistics prune
/// it and the bloom filter would measure nothing, while `total_amount` has only
/// ~45k distinct values across all of 2025 (0.09% of rows), which is neither
/// high-cardinality nor a realistic point-lookup key.
///
/// A unique key is also the workload bloom filters actually exist for — a point
/// lookup on an id (`order_id`, `trace_id`, `user_id`) — and it makes the benchmark's
/// preconditions *intrinsic* rather than forced by writer config:
///
/// - **Statistics cannot prune.** [#key] spreads keys pseudorandomly over the whole
///   64-bit range, so every row group's `[min, max]` spans essentially all of it and
///   any probe is in range.
/// - **The dictionary cannot prune.** With every value distinct the dictionary
///   overruns its page-size budget almost immediately and parquet-java falls back to
///   plain encoding on its own — no `withDictionaryEncoding(false)` needed.
/// - **NDV is known exactly.** Distinct values per column chunk == rows per row
///   group, so the bloom filter can be sized for a true 1% false-positive rate
///   instead of a guess.
///
/// Keys come from [#key], the SplitMix64 finalizer, which is a *bijection* on 64
/// bits: distinct row indices give distinct keys, so uniqueness is guaranteed
/// without holding the key set in memory. It also makes the corpus fully
/// reproducible from the row count alone, and gives a guaranteed-absent probe for
/// free — any `key(i)` with `i >= rows` cannot collide with a written key.
public final class LookupFileGenerator {

    /// The probe column: unique per row and unclustered, so only a bloom filter prunes.
    static final String PROBE_COLUMN = "key";

    /// The payload column, summed row-exactly over the filter matches.
    static final String PAYLOAD_COLUMN = "value";

    /// Default corpus size. At ~16 incompressible bytes/row (an 8-byte key and an
    /// 8-byte payload, both effectively random) a 128 MB row group holds ~8.4M rows,
    /// so this spans ~10 row groups — enough for a present probe that keeps one and
    /// drops the rest to show a real pruning win.
    public static final long DEFAULT_ROWS = 84_000_000L;

    /// Row-group size: Parquet's own default ([ParquetWriter#DEFAULT_BLOCK_SIZE]).
    /// The benchmark reports the default layout rather than a tuned one.
    public static final long ROW_GROUP_BYTES = ParquetWriter.DEFAULT_BLOCK_SIZE;

    /// Expected distinct values per column chunk. Every key is distinct, so this is
    /// just rows per row group — see [#bloomNdv].
    static final long BLOOM_FPP_NDV = ROW_GROUP_BYTES / 16L;

    /// Bloom-filter budget per column chunk, and in practice the filter's *actual*
    /// size. parquet-java sizes the filter from (NDV, FPP), rounds that **up to a
    /// power of two**, and then clamps to this cap — so at ~8.4M distinct values,
    /// where 1% needs ~9.7 MB (1.21 bytes/value), the power-of-two step lands on
    /// 16 MB and the cap is what actually decides the size. 10 MB yields a measured
    /// 0.85% false-positive rate, just under the 1% target, and still holds below 1%
    /// if a row group runs to ~8.7M rows.
    ///
    /// The cap is the trap in this whole setup: it truncates silently, so a value
    /// below the sized filter does not degrade the FPP a little — it destroys it.
    /// Parquet's 1 MB default would give ~99.7% here and the 2 MB this benchmark
    /// used previously ~86%: a filter that matches everything and prunes nothing,
    /// with nothing in the output to say why.
    static final int MAX_BLOOM_FILTER_BYTES = 10 * 1024 * 1024;

    /// Target false-positive rate (also parquet-java's default).
    static final double BLOOM_FPP = 0.01;

    private LookupFileGenerator() {
    }

    /// The key for row `i`: the SplitMix64 finalizer. Every step (xor-shift by more
    /// than half the width, multiply by an odd constant) is invertible, so the whole
    /// mix is a bijection on 64 bits — distinct `i` always yield distinct keys, which
    /// is what makes the column exactly unique and its NDV exactly known. The output
    /// is pseudorandom, so keys are unclustered and every row group's `[min, max]`
    /// covers nearly the full range.
    static long key(long i) {
        long z = i + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /// The payload for row `i`: a uniform value in `[0, 100)` built from 53 fresh
    /// pseudorandom bits, so the payload column is incompressible too and rows per
    /// row group stay close to the estimate [#BLOOM_FPP_NDV] is derived from.
    static double payload(long i) {
        return ((key(i ^ 0x5deece66dL) >>> 11) * 0x1.0p-53) * 100.0;
    }

    /// A `present` probe: the key of a row that was written, taken from the middle of
    /// the corpus. Being unique, it matches exactly one row in exactly one row group,
    /// so the bloom filter keeps that row group and drops every other one.
    public static long presentProbe(long rows) {
        return key(rows / 2);
    }

    /// An `absent` probe: the key of a row index just past the end of the corpus.
    /// [#key] is a bijection, so this value is the image of an index that was never
    /// written and therefore cannot collide with any key in the file — yet it is an
    /// ordinary 64-bit value inside every row group's `[min, max]`, so statistics
    /// keep every row group and only the bloom filter can drop them.
    public static long absentProbe(long rows) {
        return key(rows + 1);
    }

    /// Path of the bloom-filter-bearing file for a row count.
    public static Path bloomFile(long rows) {
        return Path.of("target/lookup_bloom_" + rows + ".parquet");
    }

    /// Path of the statistics-only (no bloom filter) twin.
    public static Path noBloomFile(long rows) {
        return Path.of("target/lookup_nobloom_" + rows + ".parquet");
    }

    private static Path probesFile(long rows) {
        return Path.of("target/lookup_probes_" + rows + ".properties");
    }

    /// Writes both files (and the probe sidecar) if missing. Both are produced in one
    /// pass so their rows are identical; only the bloom flag differs.
    public static void ensure(long rows) throws IOException {
        Path bloom = bloomFile(rows);
        Path noBloom = noBloomFile(rows);
        Path probes = probesFile(rows);
        if (Files.exists(bloom) && Files.size(bloom) > 0
                && Files.exists(noBloom) && Files.size(noBloom) > 0
                && Files.exists(probes)) {
            return;
        }
        Files.createDirectories(bloom.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-row lookup corpus (unique %s, %d MB row groups, bloom NDV %,d @ %.0f%% FPP)...%n",
                rows, PROBE_COLUMN, ROW_GROUP_BYTES / (1024 * 1024), BLOOM_FPP_NDV, BLOOM_FPP * 100);

        Schema schema = SchemaBuilder.record("lookup").fields()
                .requiredLong(PROBE_COLUMN)
                .requiredDouble(PAYLOAD_COLUMN)
                .endRecord();

        try (ParquetWriter<GenericRecord> bloomWriter = writer(bloom, schema, true);
             ParquetWriter<GenericRecord> noBloomWriter = writer(noBloom, schema, false)) {
            for (long i = 0; i < rows; i++) {
                GenericRecord out = new GenericData.Record(schema);
                out.put(PROBE_COLUMN, key(i));
                out.put(PAYLOAD_COLUMN, payload(i));
                bloomWriter.write(out);
                noBloomWriter.write(out);
            }
        }

        writeProbes(probes, presentProbe(rows), absentProbe(rows));
        System.out.printf("Wrote %,d rows to %s (%,d MB) and %s; present=%d absent=%d%n",
                rows, bloom, Files.size(bloom) / 1_000_000, noBloom, presentProbe(rows), absentProbe(rows));
    }

    private static ParquetWriter<GenericRecord> writer(Path file, Schema schema, boolean bloom) throws IOException {
        Configuration conf = new Configuration();
        conf.set("parquet.writer.version", "v2");
        AvroParquetWriter.Builder<GenericRecord> builder = AvroParquetWriter.<GenericRecord>builder(
                new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString()))
                .withSchema(schema)
                .withConf(conf)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupSize(ROW_GROUP_BYTES)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false);
        // Dictionary encoding is left at its default: every key is distinct, so the
        // dictionary overruns its budget and parquet-java falls back to plain on its
        // own. The gate asserts the fallback actually happened, rather than forcing it.
        if (bloom) {
            builder.withBloomFilterEnabled(PROBE_COLUMN, true)
                    .withBloomFilterNDV(PROBE_COLUMN, BLOOM_FPP_NDV)
                    .withBloomFilterFPP(PROBE_COLUMN, BLOOM_FPP)
                    .withMaxBloomFilterBytes(MAX_BLOOM_FILTER_BYTES);
        }
        return builder.build();
    }

    private static void writeProbes(Path file, long present, long absent) throws IOException {
        Properties p = new Properties();
        p.setProperty("present", Long.toString(present));
        p.setProperty("absent", Long.toString(absent));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Bloom benchmark probes for " + PROBE_COLUMN);
        }
    }

    /// Reads back the recorded probes (unused by the benchmark, which derives them
    /// from the row count, but kept so a generated corpus is self-describing).
    static Properties loadProbes(long rows) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(probesFile(rows))) {
            p.load(in);
        }
        return p;
    }
}
