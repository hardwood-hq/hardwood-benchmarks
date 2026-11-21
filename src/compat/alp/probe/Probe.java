/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
import dev.hardwood.writer.ColumnEncoding;

/// Compiles against a Hardwood version that has the `ALP` encoding; never part of a build.
class Probe {

    ColumnEncoding alp() {
        return ColumnEncoding.ALP;
    }
}
