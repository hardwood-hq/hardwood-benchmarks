/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.internal.hadoop.metadata.IndexReference;
import org.apache.parquet.io.LocalInputFile;

/// Where a file's page index lies, read from its footer by parquet-java: the byte region from
/// the first column index or offset index to the end of the last one, and how many of those
/// bytes a read needs, given the columns its predicate tests (their column indexes) and the
/// columns it projects (their offset indexes).
///
/// A file without a page index has an empty region at offset 0.
record PageIndexLayout(long regionStart, long regionEnd, long neededBytes) {

    long regionBytes() {
        return regionEnd - regionStart;
    }

    /// Whether `[offset, offset + length)` overlaps the page-index region.
    boolean overlaps(long offset, long length) {
        return offset < regionEnd && offset + length > regionStart;
    }

    static PageIndexLayout of(Path file, Set<String> filterColumns, Set<String> projectedColumns)
            throws IOException {
        long start = Long.MAX_VALUE;
        long end = 0;
        long needed = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
            for (BlockMetaData block : reader.getFooter().getBlocks()) {
                for (ColumnChunkMetaData column : block.getColumns()) {
                    String name = column.getPath().toDotString();
                    IndexReference columnIndex = column.getColumnIndexReference();
                    IndexReference offsetIndex = column.getOffsetIndexReference();
                    if (columnIndex != null) {
                        start = Math.min(start, columnIndex.getOffset());
                        end = Math.max(end, columnIndex.getOffset() + columnIndex.getLength());
                        if (filterColumns.contains(name)) {
                            needed += columnIndex.getLength();
                        }
                    }
                    if (offsetIndex != null) {
                        start = Math.min(start, offsetIndex.getOffset());
                        end = Math.max(end, offsetIndex.getOffset() + offsetIndex.getLength());
                        if (projectedColumns.contains(name)) {
                            needed += offsetIndex.getLength();
                        }
                    }
                }
            }
        }
        return end == 0 ? new PageIndexLayout(0, 0, 0) : new PageIndexLayout(start, end, needed);
    }
}
