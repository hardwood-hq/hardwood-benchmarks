/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.nested;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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

import dev.hardwood.InputFile;

/// The column readers of [NestedScanBenchmark] over the Overture Maps places file, as a
/// regression benchmark of their own (`run-nested-columns.sh`): every leaf column through
/// [dev.hardwood.reader.ColumnReaders], read the same way as
/// `NestedScanBenchmark.hardwoodColumnReaders`. Its preset reads the whole file, where the read
/// is bound by decoding, while `run-nested.sh`'s reads 20,000 rows; a class of its own gives the
/// run its own fixture in the meta sidecar. Its gate is [NestedScanBenchmark]'s, which checks
/// the column readers against `AvroParquetReader`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class NestedColumnScanBenchmark {

    private InputFile inputFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        inputFile = InputFile.of(OvertureMapsDownloader.ensure(NestedScanBenchmark.FILE));
    }

    @Benchmark
    public long hardwoodColumnReaders() throws IOException {
        return hardwoodColumnScan();
    }

    private long hardwoodColumnScan() throws IOException {
        return NestedScanBenchmark.columnScan(inputFile);
    }

    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("perf.gate")) {
            NestedScanBenchmark.gate();
            return;
        }
        NestedScanBenchmark.report(NestedScanBenchmark.run(NestedColumnScanBenchmark.class),
                NestedColumnScanBenchmark.class);
    }
}
