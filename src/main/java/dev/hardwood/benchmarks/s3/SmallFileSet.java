/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.s3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The many-small-files fixture: the files `python/small_files.py` wrote into one directory, listed
/// in its `manifest.tsv` in the order a read takes them. `run-s3.sh` writes the directory on first
/// use and passes it as `-Dperf.s3.smallFiles`; its name records every input it was written
/// with (parameters, pinned versions and a checksum of the generator), and it becomes the prefix of each file's key in the bucket, so a changed parameter
/// publishes new objects rather than reusing old ones.
record SmallFileSet(Path dir, List<Entry> files) {

    static final String PROPERTY = "perf.s3.smallFiles";

    /// One file: its name in [SmallFileSet#dir], its size in bytes and its row count.
    record Entry(String name, long size, long rows) {
    }

    /// The fixture `-Dperf.s3.smallFiles` names; fails if the property is unset or the manifest
    /// is missing or does not match the files beside it.
    static SmallFileSet load() throws IOException {
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("-D" + PROPERTY + " is not set; run-s3.sh sets it outside --regression");
        }
        Path root = Path.of(dir);
        List<Entry> files = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("manifest.tsv"))) {
            String[] fields = line.split("\t");
            Entry entry = new Entry(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            long actual = Files.size(root.resolve(entry.name()));
            if (actual != entry.size()) {
                throw new IllegalStateException(String.format("%s is %d bytes, its manifest says %d",
                        root.resolve(entry.name()), actual, entry.size()));
            }
            files.add(entry);
        }
        return new SmallFileSet(root, List.copyOf(files));
    }

    /// The key `entry` is published under.
    String key(Entry entry) {
        return dir.getFileName() + "-" + entry.name();
    }

    List<String> keys() {
        return files.stream().map(this::key).toList();
    }

    long totalBytes() {
        return files.stream().mapToLong(Entry::size).sum();
    }

    long totalRows() {
        return files.stream().mapToLong(Entry::rows).sum();
    }
}
