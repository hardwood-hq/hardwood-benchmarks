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
import java.time.YearMonth;
import java.util.List;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.util.HadoopInputFile;

import dev.hardwood.benchmarks.flat.TaxiDataDownloader;

/// Derives the bloom-filter benchmark files from the **real** NYC TLC yellow-taxi
/// data. The published TLC files (parquet-cpp / Arrow) carry no bloom filters, and
/// Hardwood cannot yet *write* them, so this rewrites the real rows through
/// parquet-java's `AvroParquetWriter` with a bloom filter added on `total_amount`.
///
/// It projects the two columns the benchmark touches — `total_amount` (the probe
/// column) and `trip_distance` (the summed payload) — keeping their real values
/// and distribution, and skips rows where either is null so the read path stays
/// null-free. Two files are written from one pass over the source so their rows are
/// identical: [#bloomFile] carries a bloom filter on `total_amount`; [#noBloomFile]
/// carries none. Dictionary encoding is disabled on `total_amount` so parquet-java's
/// dictionary filter cannot prune either — leaving the bloom filter as the sole
/// pruner, the thing the benchmark isolates.
///
/// `total_amount` is a real, unclustered, high-cardinality fare column, so a probe
/// for a value that was never charged (an in-range amount with sub-cent precision)
/// is absent from every row group's bloom filter — the case min/max statistics
/// cannot catch. The two probe values (a real present amount and a synthetic absent
/// one derived from the observed range) are recorded in a sidecar `.properties`
/// file so the benchmark uses them without rescanning.
public final class TaxiBloomGenerator {

    /// The probe column: a real, high-cardinality, unclustered fare amount.
    static final String PROBE_COLUMN = "total_amount";

    /// The summed payload column, read row-exactly over the filter matches.
    static final String PAYLOAD_COLUMN = "trip_distance";

    private TaxiBloomGenerator() {
    }

    private static String key(YearMonth start, YearMonth end, long limit) {
        return start + "_" + end + "_" + (limit <= 0 ? "all" : Long.toString(limit));
    }

    /// Path of the bloom-filter-bearing file for a source window and row cap.
    public static Path bloomFile(YearMonth start, YearMonth end, long limit) {
        return Path.of("target/taxi_bloom_" + key(start, end, limit) + ".parquet");
    }

    /// Path of the statistics-only (no bloom filter) twin.
    public static Path noBloomFile(YearMonth start, YearMonth end, long limit) {
        return Path.of("target/taxi_nobloom_" + key(start, end, limit) + ".parquet");
    }

    private static Path probesFile(YearMonth start, YearMonth end, long limit) {
        return Path.of("target/taxi_probes_" + key(start, end, limit) + ".properties");
    }

    /// A `present` probe: a real `total_amount` that appears in the data. Whether it
    /// prunes depends on how localized it is; it always keeps the row group(s) that
    /// hold it. Read from the sidecar written by [#ensure].
    public static double presentProbe(YearMonth start, YearMonth end, long limit) throws IOException {
        return Double.parseDouble(loadProbes(start, end, limit).getProperty("present"));
    }

    /// An `absent` probe: an in-range `total_amount` that was never charged, so it is
    /// in no bloom filter and every row group is dropped. Read from the sidecar.
    public static double absentProbe(YearMonth start, YearMonth end, long limit) throws IOException {
        return Double.parseDouble(loadProbes(start, end, limit).getProperty("absent"));
    }

    private static Properties loadProbes(YearMonth start, YearMonth end, long limit) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(probesFile(start, end, limit))) {
            p.load(in);
        }
        return p;
    }

    /// Writes both files (and the probe sidecar) if missing. Ensures the source TLC
    /// files for `[start, end]` are present first (downloading if needed), then
    /// rewrites them once into the bloom / no-bloom pair. `limit <= 0` uses every row.
    public static void ensure(YearMonth start, YearMonth end, long limit) throws IOException {
        Path bloom = bloomFile(start, end, limit);
        Path noBloom = noBloomFile(start, end, limit);
        Path probes = probesFile(start, end, limit);
        if (Files.exists(bloom) && Files.size(bloom) > 0
                && Files.exists(noBloom) && Files.size(noBloom) > 0
                && Files.exists(probes)) {
            return;
        }
        List<Path> sources = TaxiDataDownloader.ensure(start, end);
        if (sources.isEmpty()) {
            throw new IOException("No TLC source files for " + start + ".." + end);
        }
        Files.createDirectories(bloom.toAbsolutePath().getParent());
        System.out.printf("Rewriting real TLC taxi data (%s..%s%s) with a bloom filter on %s...%n",
                start, end, limit > 0 ? ", limit " + limit : "", PROBE_COLUMN);

        Schema schema = SchemaBuilder.record("taxi_lookup").fields()
                .requiredDouble(PROBE_COLUMN)
                .requiredDouble(PAYLOAD_COLUMN)
                .endRecord();

        // Anchor the probes to a common ~$20 fare rather than the global range: the
        // `total_amount` distribution has extreme outliers (rare five-figure fares),
        // so a midpoint value would sit above most row groups' max and be pruned by
        // *statistics*, not the bloom filter. A central fare is inside every row
        // group's [min, max], so only the bloom filter can drop it — which is the
        // whole point.
        final double target = 20.0;
        long written = 0;
        double present = Double.NaN;
        double bestDist = Double.POSITIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        try (ParquetWriter<GenericRecord> bloomWriter = writer(bloom, schema, true);
             ParquetWriter<GenericRecord> noBloomWriter = writer(noBloom, schema, false)) {
            outer:
            for (Path source : sources) {
                try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                        HadoopInputFile.fromPath(
                                new org.apache.hadoop.fs.Path(source.toAbsolutePath().toString()),
                                new Configuration()))
                        .build()) {
                    GenericRecord in;
                    while ((in = reader.read()) != null) {
                        Object ta = in.get(PROBE_COLUMN);
                        Object td = in.get(PAYLOAD_COLUMN);
                        if (ta == null) {
                            continue; // keep total_amount non-null so the read stays null-free
                        }
                        double amount = ((Number) ta).doubleValue();
                        double distance = td == null ? 0.0 : ((Number) td).doubleValue();

                        GenericRecord out = new GenericData.Record(schema);
                        out.put(PROBE_COLUMN, amount);
                        out.put(PAYLOAD_COLUMN, distance);
                        bloomWriter.write(out);
                        noBloomWriter.write(out);

                        double dist = Math.abs(amount - target);
                        if (dist < bestDist) {
                            bestDist = dist;
                            present = amount;
                        }
                        min = Math.min(min, amount);
                        max = Math.max(max, amount);
                        written++;
                        if (limit > 0 && written >= limit) {
                            break outer;
                        }
                    }
                }
            }
        }
        if (written == 0) {
            throw new IOException("No non-null " + PROBE_COLUMN + " rows in " + start + ".." + end);
        }

        // `present` is a real central fare; `absent` nudges it by a tenth of a cent,
        // an amount a whole-cent fare never lands on — in-band (so statistics keep
        // every row group) yet never charged (so the bloom filter drops them all).
        // The gate verifies the absent probe truly returns zero rows.
        double absent = present + 0.001;
        writeProbes(probesFile(start, end, limit), present, absent);

        System.out.printf("Wrote %,d rows to %s (%,d MB) and %s; %s in [%.2f, %.2f], present=%.4f absent=%.4f%n",
                written, bloom, Files.size(bloom) / 1_000_000, noBloom, PROBE_COLUMN, min, max, present, absent);
    }

    private static ParquetWriter<GenericRecord> writer(Path file, Schema schema, boolean bloom) throws IOException {
        Configuration conf = new Configuration();
        conf.set("parquet.writer.version", "v2");
        conf.set("parquet.bloom.filter.max.bytes", "2097152"); // 2 MB, low FPP for a big distinct-count
        AvroParquetWriter.Builder<GenericRecord> builder = AvroParquetWriter.<GenericRecord>builder(
                new org.apache.hadoop.fs.Path(file.toAbsolutePath().toString()))
                .withSchema(schema)
                .withConf(conf)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                // 16 MB row groups (vs the 128 MB default): the projected two-column
                // file is small (~8 bytes/row for total_amount), so smaller row groups
                // let a single month's few million rows span several of them — making
                // per-row-group bloom pruning observable at accessible scale. Finer row
                // groups are also a realistic layout for point-lookup workloads, which
                // is exactly where bloom filters live.
                .withRowGroupSize(16L * 1024 * 1024)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withPageWriteChecksumEnabled(false)
                // Disable dictionary on total_amount so parquet-java's dictionary filter
                // can't prune — leaving the bloom filter as the only pruner.
                .withDictionaryEncoding(PROBE_COLUMN, false);
        if (bloom) {
            builder.withBloomFilterEnabled(PROBE_COLUMN, true)
                    .withBloomFilterNDV(PROBE_COLUMN, 1_000_000L);
        }
        return builder.build();
    }

    private static void writeProbes(Path file, double present, double absent) throws IOException {
        Properties p = new Properties();
        p.setProperty("present", Double.toString(present));
        p.setProperty("absent", Double.toString(absent));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Bloom benchmark probes for " + PROBE_COLUMN);
        }
    }
}
