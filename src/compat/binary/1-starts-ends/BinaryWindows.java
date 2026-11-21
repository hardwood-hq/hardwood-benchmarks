/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.compat.binary;

import dev.hardwood.reader.ColumnReader;

/// A column reader's binary values in a batch, through the batch's byte buffer with each
/// value's start and end (1.1.0.Beta2 and 1.1.0.Beta3). Value `i` is `[start(i), end(i))` of
/// `buffer(i)`.
public final class BinaryWindows {

    private final byte[] buffer;
    private final int[] starts;
    private final int[] ends;

    private BinaryWindows(byte[] buffer, int[] starts, int[] ends) {
        this.buffer = buffer;
        this.starts = starts;
        this.ends = ends;
    }

    /// The binary values of `column`'s current batch.
    public static BinaryWindows of(ColumnReader column) {
        return new BinaryWindows(column.getBinaryValues(), column.getBinaryStarts(), column.getBinaryEnds());
    }

    /// The array value `i` is read from.
    public byte[] buffer(int i) {
        return buffer;
    }

    /// Where value `i` starts in [#buffer(int)], inclusive.
    public int start(int i) {
        return starts[i];
    }

    /// Where value `i` ends in [#buffer(int)], exclusive.
    public int end(int i) {
        return ends[i];
    }
}
