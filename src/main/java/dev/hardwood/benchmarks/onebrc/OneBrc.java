/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.onebrc;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;

/// Hardwood's contender in `run-1brc-engines.sh`: prints the 1BRC aggregate of the file named by
/// the first argument, computed with the column reader over one row-group byte-range split per
/// available processor.
public final class OneBrc {

    private OneBrc() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: OneBrc <measurements.parquet>");
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        String mode = System.getProperty("onebrc.mode", "splits");
        StationTable table = switch (mode) {
            case "splits" -> OneBrcScans.columnSplits(Path.of(args[0]), cpus);
            case "fanout" -> {
                int workers = Integer.getInteger("onebrc.workers", cpus);
                int decode = Integer.getInteger("onebrc.decode", cpus);
                try (HardwoodContext context = HardwoodContext.create(decode);
                        ParquetFileReader reader = ParquetFileReader.open(InputFile.of(Path.of(args[0])), context)) {
                    yield OneBrcScans.fanOut(reader, workers);
                }
            }
            case "ids" -> OneBrcScans.columnIds(Path.of(args[0]));
            default -> throw new IllegalArgumentException(mode);
        };
        new PrintStream(System.out, true, StandardCharsets.UTF_8).println(table.format());
    }
}
