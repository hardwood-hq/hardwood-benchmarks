/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.compat.binary;

import dev.hardwood.reader.BinaryValues;
import dev.hardwood.reader.ColumnReader;

/// A column reader's binary values in a batch, through a window per value (`BinaryValues`,
/// 1.1.0.CR1 and later). Value `i` is `[start(i), end(i))` of `buffer(i)`.
public final class BinaryWindows {

    private final BinaryValues values;

    private BinaryWindows(BinaryValues values) {
        this.values = values;
    }

    /// The binary values of `column`'s current batch.
    public static BinaryWindows of(ColumnReader column) {
        return new BinaryWindows(column.getBinaryValues());
    }

    /// The array value `i` is read from.
    public byte[] buffer(int i) {
        return values.buffer(i);
    }

    /// Where value `i` starts in [#buffer(int)], inclusive.
    public int start(int i) {
        return values.start(i);
    }

    /// Where value `i` ends in [#buffer(int)], exclusive.
    public int end(int i) {
        return values.end(i);
    }
}
