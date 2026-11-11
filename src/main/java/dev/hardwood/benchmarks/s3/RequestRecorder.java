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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import dev.hardwood.InputFile;
import dev.hardwood.s3.S3InputFile;

/// Records when the network requests of one read are in flight, across all of its files, for
/// the untimed gate read: the most in flight at once and the mean over the read.
///
/// A call into a file ([RecordingFile]) counts as a request when the file's request count grew
/// while it ran, and each growth is claimed by one call only. Where a network call and a call the
/// file serves from its tail overlap on one file, the growth may go to the second; such calls are
/// rare (one per file, on its tail), so the figures are close but not exact.
final class RequestRecorder {

    private final List<long[]> intervals = new ArrayList<>();
    private long start;
    private long end;

    /// Wraps `file` so that its requests are recorded here.
    InputFile wrap(S3InputFile file) {
        return new RecordingFile(file, this);
    }

    /// Marks the start of the read.
    void started() {
        start = System.nanoTime();
    }

    /// Marks the end of the read, its consumer done and the reader closed.
    void ended() {
        end = System.nanoTime();
    }

    private synchronized void add(long from, long to) {
        intervals.add(new long[] { from, to });
    }

    /// The most requests in flight at one time.
    synchronized int peak() {
        List<long[]> events = new ArrayList<>();
        for (long[] interval : intervals) {
            events.add(new long[] { interval[0], 1 });
            events.add(new long[] { interval[1], -1 });
        }
        // at equal times an end goes first, so back-to-back requests do not count as overlapping
        events.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int inFlight = 0;
        int peak = 0;
        for (long[] event : events) {
            inFlight += (int) event[1];
            peak = Math.max(peak, inFlight);
        }
        return peak;
    }

    /// The requests in flight on average between [#started] and [#ended]: their summed duration
    /// over the read's.
    synchronized double mean() {
        long busy = 0;
        for (long[] interval : intervals) {
            busy += interval[1] - interval[0];
        }
        return end > start ? (double) busy / (end - start) : 0;
    }

    /// The requests that ran after [#ended]: ones the read had in flight when its reader closed.
    synchronized long afterEnd() {
        return intervals.stream().filter(i -> i[1] > end).count();
    }

    private static final class RecordingFile implements InputFile {

        private final S3InputFile delegate;
        private final RequestRecorder recorder;
        /// the file's requests already claimed by a call
        private final AtomicLong claimed = new AtomicLong();

        RecordingFile(S3InputFile delegate, RequestRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public void open() throws IOException {
            long from = System.nanoTime();
            delegate.open();
            claim(from);
        }

        @Override
        public ByteBuffer readRange(long offset, int length) throws IOException {
            long from = System.nanoTime();
            ByteBuffer result = delegate.readRange(offset, length);
            claim(from);
            return result;
        }

        /// Records the call begun at `from` as a request if the file made one not yet claimed.
        private void claim(long from) {
            long to = System.nanoTime();
            long made = delegate.networkRequestCount();
            long seen;
            do {
                seen = claimed.get();
                if (seen >= made) {
                    return;
                }
            } while (!claimed.compareAndSet(seen, seen + 1));
            recorder.add(from, to);
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
        public Optional<String> identity() {
            return delegate.identity();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
