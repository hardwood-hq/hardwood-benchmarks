/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3;

import java.io.IOException;
import java.nio.ByteBuffer;

import dev.hardwood.InputFile;
import dev.hardwood.s3.S3InputFile;

/// An [S3InputFile] whose network requests are attributed to the page index or to the rest of
/// the file: a request whose range overlaps the file's page-index region counts as a page-index
/// request, every other one (footer, dictionary and data pages) as a data request.
///
/// Reads are serialized, so that the request and byte counters the delegate reports before and
/// after a read belong to that read alone. It is for the untimed gate read only: serializing
/// changes the timing, not the requests.
final class AttributingInputFile implements InputFile {

    private final S3InputFile delegate;
    private final PageIndexLayout layout;
    private long indexRequests;
    private long indexBytes;

    AttributingInputFile(S3InputFile delegate, PageIndexLayout layout) {
        this.delegate = delegate;
        this.layout = layout;
    }

    @Override
    public void open() throws IOException {
        delegate.open();
    }

    @Override
    public synchronized ByteBuffer readRange(long offset, int length) throws IOException {
        long requestsBefore = delegate.networkRequestCount();
        long bytesBefore = delegate.networkBytesFetched();
        ByteBuffer result = delegate.readRange(offset, length);
        if (layout.overlaps(offset, length)) {
            indexRequests += delegate.networkRequestCount() - requestsBefore;
            indexBytes += delegate.networkBytesFetched() - bytesBefore;
        }
        return result;
    }

    @Override
    public long length() throws IOException {
        return delegate.length();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    synchronized long requests() {
        return delegate.networkRequestCount();
    }

    synchronized long bytes() {
        return delegate.networkBytesFetched();
    }

    synchronized long indexRequests() {
        return indexRequests;
    }

    synchronized long indexBytes() {
        return indexBytes;
    }
}
