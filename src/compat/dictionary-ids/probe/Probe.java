/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
import dev.hardwood.reader.BinaryDictionary;
import dev.hardwood.reader.ColumnReader;

/// Compiles against a Hardwood version that has `ColumnReader.getDictionaryIds()` and `getBinaryDictionary()`; never part of a build.
class Probe {

    int[] ids(ColumnReader column) {
        BinaryDictionary dictionary = column.getBinaryDictionary();
        return column.getDictionaryIds();
    }
}
