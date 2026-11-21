/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
import dev.hardwood.OutputFile;
import dev.hardwood.s3.S3Source;

/// Compiles against a Hardwood version that has `S3Source.outputFile`; never part of a build.
class Probe {

    OutputFile out(S3Source source) {
        return source.outputFile("bucket", "key");
    }
}
