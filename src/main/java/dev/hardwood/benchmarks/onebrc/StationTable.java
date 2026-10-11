/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.onebrc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Per-station minimum, maximum, sum and count of the 1BRC aggregate, in an open-addressed
/// table keyed by the station's UTF-8 bytes.
///
/// Measurements are accumulated in tenths of a degree as `long`s. The challenge's values have
/// exactly one fractional digit, so tenths are integral and the sums stay exact however many
/// rows are folded in: every contender, and the generator's expected result, produce the same
/// text, not merely close numbers.
public final class StationTable {

    private static final int CAPACITY = 1 << 14;
    private static final int MASK = CAPACITY - 1;

    private final byte[][] keys = new byte[CAPACITY][];
    private final long[] min = new long[CAPACITY];
    private final long[] max = new long[CAPACITY];
    private final long[] sum = new long[CAPACITY];
    private final long[] count = new long[CAPACITY];

    /// The slot of the station whose name is `buffer[from, to)`, created on first sight.
    int slot(byte[] buffer, int from, int to) {
        int index = hash(buffer, from, to) & MASK;
        while (true) {
            byte[] key = keys[index];
            if (key == null) {
                keys[index] = Arrays.copyOfRange(buffer, from, to);
                min[index] = Long.MAX_VALUE;
                max[index] = Long.MIN_VALUE;
                return index;
            }
            if (Arrays.equals(key, 0, key.length, buffer, from, to)) {
                return index;
            }
            index = (index + 1) & MASK;
        }
    }

    int slot(byte[] name) {
        return slot(name, 0, name.length);
    }

    void add(int slot, long tenths) {
        if (tenths < min[slot]) {
            min[slot] = tenths;
        }
        if (tenths > max[slot]) {
            max[slot] = tenths;
        }
        sum[slot] += tenths;
        count[slot]++;
    }

    /// Folds an aggregate accumulated elsewhere into the station at `slot`.
    void add(int slot, long otherMin, long otherMax, long otherSum, long otherCount) {
        min[slot] = Math.min(min[slot], otherMin);
        max[slot] = Math.max(max[slot], otherMax);
        sum[slot] += otherSum;
        count[slot] += otherCount;
    }

    void mergeFrom(StationTable other) {
        for (int i = 0; i < CAPACITY; i++) {
            byte[] key = other.keys[i];
            if (key != null) {
                add(slot(key), other.min[i], other.max[i], other.sum[i], other.count[i]);
            }
        }
    }

    long rows() {
        long total = 0;
        for (long c : count) {
            total += c;
        }
        return total;
    }

    /// The challenge's output: `{name=min/mean/max, ...}`, stations in the unsigned order of
    /// their UTF-8 bytes, each value with one fractional digit and the mean rounded half up.
    /// A station resolved from a dictionary but given no rows is not part of the result.
    String format() {
        Integer[] slots = new Integer[CAPACITY];
        int n = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (keys[i] != null && count[i] > 0) {
                slots[n++] = i;
            }
        }
        Arrays.sort(slots, 0, n, (a, b) -> Arrays.compareUnsigned(keys[a], keys[b]));
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            int slot = slots[i];
            if (i > 0) {
                out.append(", ");
            }
            // floor(sum / count + 1/2), in integers: exact for any row count.
            long meanTenths = Math.floorDiv(2 * sum[slot] + count[slot], 2 * count[slot]);
            out.append(new String(keys[slot], StandardCharsets.UTF_8)).append('=')
                    .append(tenths(min[slot])).append('/')
                    .append(tenths(meanTenths)).append('/')
                    .append(tenths(max[slot]));
        }
        return out.append('}').toString();
    }

    private static String tenths(long value) {
        long abs = Math.abs(value);
        return (value < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10);
    }

    private static int hash(byte[] buffer, int from, int to) {
        int hash = 0x811c9dc5;
        for (int i = from; i < to; i++) {
            hash = (hash ^ buffer[i]) * 0x01000193;
        }
        return hash ^ (hash >>> 15);
    }
}
