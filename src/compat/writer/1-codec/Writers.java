/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.compat.writer;

import java.io.IOException;

import dev.hardwood.HardwoodContext;
import dev.hardwood.OutputFile;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.WriterConfig;

/// The parts of Hardwood's writer API that differ between versions, as 1.1.0.Beta1 to
/// 1.1.0.Beta3 have them: `WriterConfig.Builder.codec`, `FileSchema.Builder.addColumn`, and a
/// writer that takes no `HardwoodContext`.
public final class Writers {

    private Writers() {
    }

    /// Compresses page bodies with `codec` at its default level.
    public static WriterConfig.Builder codec(WriterConfig.Builder config, String codec) {
        return config.codec(CompressionCodec.valueOf(codec));
    }

    /// Creates a writer. These versions take no context, so the writer runs on a pool of its own
    /// and `context` is unused.
    public static ParquetFileWriter create(OutputFile out, FileSchema schema, WriterConfig config,
                                           HardwoodContext context) throws IOException {
        return ParquetFileWriter.create(out, schema, config);
    }

    /// Appends an unannotated primitive column.
    public static FileSchema.Builder primitive(FileSchema.Builder schema, String name, PhysicalType type,
                                               RepetitionType repetition) {
        return schema.addColumn(name, type, repetition);
    }

    /// Appends a primitive column annotated with `logicalType`.
    public static FileSchema.Builder primitive(FileSchema.Builder schema, String name, PhysicalType type,
                                               RepetitionType repetition, LogicalType logicalType) {
        return schema.addColumn(name, type, repetition, logicalType);
    }
}
