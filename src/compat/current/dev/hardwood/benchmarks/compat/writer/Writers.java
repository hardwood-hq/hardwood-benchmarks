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
import dev.hardwood.writer.Compression;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.WriterConfig;

/// The parts of Hardwood's writer API that differ between versions, as this version has them:
/// `WriterConfig.Builder.compression`, `FileSchema.Builder.primitive` and a writer created on a
/// `HardwoodContext` (1.1.0.CR1 and later).
public final class Writers {

    private Writers() {
    }

    /// Compresses page bodies with `codec` at its default level.
    public static WriterConfig.Builder codec(WriterConfig.Builder config, String codec) {
        return config.compression(Compression.of(CompressionCodec.valueOf(codec)));
    }

    /// Creates a writer whose work runs on `context`'s pool.
    public static ParquetFileWriter create(OutputFile out, FileSchema schema, WriterConfig config,
                                           HardwoodContext context) throws IOException {
        return ParquetFileWriter.create(out, schema, config, context);
    }

    /// Appends an unannotated primitive column.
    public static FileSchema.Builder primitive(FileSchema.Builder schema, String name, PhysicalType type,
                                               RepetitionType repetition) {
        return schema.primitive(name, type, repetition);
    }

    /// Appends a primitive column annotated with `logicalType`.
    public static FileSchema.Builder primitive(FileSchema.Builder schema, String name, PhysicalType type,
                                               RepetitionType repetition, LogicalType logicalType) {
        return schema.primitive(name, type, repetition, c -> c.logicalType(logicalType));
    }
}
