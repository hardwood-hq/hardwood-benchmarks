/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.util.HashMap;
import java.util.Map;

/// The features under `src/compat` that the Hardwood version on the classpath predates, as
/// `bench_build` found them and passed them in `-Dbench.unsupported` (`<feature>@<since>`,
/// comma-separated). A benchmark that runs on such a version without one of its contenders or
/// parameter values asks here, and reports what it leaves out.
public final class Features {

    /// Each unsupported feature, with the first Hardwood release that has it.
    private static final Map<String, String> UNSUPPORTED = parse(System.getProperty("bench.unsupported", ""));

    private Features() {
    }

    /// Whether the Hardwood version on the classpath has `feature`.
    public static boolean supported(String feature) {
        return !UNSUPPORTED.containsKey(feature);
    }

    /// Prints that `what` is left out because the Hardwood version lacks `feature`, in the form
    /// `run-regression.sh` lists under "Not run".
    public static void reportUnsupported(String what, String feature) {
        System.out.println("Not supported by this Hardwood version: " + what + " (needs " + feature + ", from "
                + UNSUPPORTED.get(feature) + ")");
    }

    private static Map<String, String> parse(String property) {
        Map<String, String> unsupported = new HashMap<>();
        for (String entry : property.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            int at = entry.indexOf('@');
            if (at < 0) {
                throw new IllegalArgumentException("bench.unsupported entry '" + entry + "' is not <feature>@<since>");
            }
            unsupported.put(entry.substring(0, at), entry.substring(at + 1));
        }
        return unsupported;
    }
}
