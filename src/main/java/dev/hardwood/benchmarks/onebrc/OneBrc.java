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
        StationTable table = OneBrcScans.columnSplits(Path.of(args[0]), Runtime.getRuntime().availableProcessors());
        new PrintStream(System.out, true, StandardCharsets.UTF_8).println(table.format());
    }
}
