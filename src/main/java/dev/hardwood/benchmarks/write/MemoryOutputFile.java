/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.write;

import java.nio.ByteBuffer;
import java.util.Arrays;

import dev.hardwood.OutputFile;

/// An [OutputFile] over a byte array kept from one file to the next: [#create()] rewinds it, so
/// once the array has grown to the file's size the sink allocates nothing and a write costs one
/// copy. It is the benchmark's own class rather than one of Hardwood's, so the sink stays the
/// same whichever Hardwood version is measured.
final class MemoryOutputFile implements OutputFile {

    private byte[] buffer = new byte[1 << 20];
    private int size;

    @Override
    public void create() {
        size = 0;
    }

    @Override
    public void write(ByteBuffer data) {
        int length = data.remaining();
        int end = Math.addExact(size, length);
        if (end > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(end, Math.toIntExact(Math.min(Integer.MAX_VALUE - 8, 2L * buffer.length))));
        }
        data.get(buffer, size, length);
        size = end;
    }

    @Override
    public long position() {
        return size;
    }

    @Override
    public void discard() {
        size = 0;
    }

    @Override
    public void close() {
    }

    /// A copy of the file written since the last [#create()].
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }
}
