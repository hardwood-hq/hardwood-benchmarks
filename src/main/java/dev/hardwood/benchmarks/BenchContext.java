/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import dev.hardwood.HardwoodContext;

/// The context a Hardwood contender reads and writes on: a pool of `-Dperf.threads` threads
/// when that property is set, Hardwood's default size otherwise. A contender opens one per
/// operation where Hardwood would otherwise create and own one, and closes it after its reader
/// or writer, so the cost of starting the pool stays in the measurement.
///
/// [BenchReport#writeMeta] records the pool size in the meta sidecar, so a run at a set size is
/// labelled as one. Every Hardwood contender takes its context from here, so that the size
/// recorded is the size measured.
public final class BenchContext {

    private BenchContext() {
    }

    /// A new context of the configured size; the caller closes it.
    public static HardwoodContext create() {
        Integer threads = Integer.getInteger("perf.threads");
        return threads == null ? HardwoodContext.create() : HardwoodContext.create(threads);
    }

    /// The pool size [#create] gives, as the meta sidecar records it.
    static String recordedThreads() {
        Integer threads = Integer.getInteger("perf.threads");
        return threads == null ? Runtime.getRuntime().availableProcessors() + " (default)" : threads.toString();
    }
}
