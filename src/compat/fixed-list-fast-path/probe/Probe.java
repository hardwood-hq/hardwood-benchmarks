/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
import dev.hardwood.reader.ReaderConfig;

/// Compiles against a Hardwood version that has `ReaderConfig`, whose
/// `hardwood.fixed-list-fast-path` option switches the fixed-size-list fast path; never part of
/// a build.
class Probe {

    ReaderConfig config() {
        return ReaderConfig.builder().option("hardwood.fixed-list-fast-path", "true").build();
    }
}
