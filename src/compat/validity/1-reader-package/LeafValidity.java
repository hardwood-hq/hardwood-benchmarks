/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.compat.validity;

import dev.hardwood.reader.Validity;
import dev.hardwood.reader.ColumnReader;

/// The leaf validity of a column reader's current batch, through `dev.hardwood.reader.Validity` (1.0).
public final class LeafValidity {

    private final Validity validity;

    private LeafValidity(Validity validity) {
        this.validity = validity;
    }

    /// The leaf validity of `column`'s current batch.
    public static LeafValidity of(ColumnReader column) {
        return new LeafValidity(column.getLeafValidity());
    }

    /// Whether any value of the batch is null.
    public boolean hasNulls() {
        return validity.hasNulls();
    }

    /// Whether value `i` is null.
    public boolean isNull(int i) {
        return validity.isNull(i);
    }
}
