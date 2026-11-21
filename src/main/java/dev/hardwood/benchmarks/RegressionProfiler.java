/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;

/// What a regression run records of each iteration besides its time, in the fork that runs it:
/// the process's CPU time, with the JIT compiler's and the collector's apart, the bytes every
/// thread allocated, threads that have ended included, and the live-set peak, the most heap in
/// use right after a collection during the iteration (`NaN` when none ran). Each value is per
/// operation but the peak.
///
/// It appends one JSON line per iteration, warmup and measurement, to the file
/// `-Dperf.metrics` names, which `run-regression.sh` reads to tell whether a contender settled
/// and whether its forks agree; JMH prints the values beside each iteration's score as well.
public final class RegressionProfiler implements InternalProfiler {

    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();
    private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final Set<String> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .map(MemoryPoolMXBean::getName)
            .collect(Collectors.toSet());
    /// The most heap in use after a collection since the iteration began, `-1` before one.
    private static final AtomicLong LIVE_PEAK = new AtomicLong(-1);

    static {
        for (GarbageCollectorMXBean collector : COLLECTORS) {
            if (collector instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener((notification, handback) -> {
                    if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                        Map<String, MemoryUsage> after = GarbageCollectionNotificationInfo
                                .from((CompositeData) notification.getUserData()).getGcInfo().getMemoryUsageAfterGc();
                        long used = 0;
                        for (Map.Entry<String, MemoryUsage> pool : after.entrySet()) {
                            if (HEAP_POOLS.contains(pool.getKey())) {
                                used += pool.getValue().getUsed();
                            }
                        }
                        LIVE_PEAK.accumulateAndGet(used, Math::max);
                    }
                }, null, null);
            }
        }
    }

    private long cpuNanos;
    private long jitMillis;
    private long gcMillis;
    private long allocated;
    private int warmup;
    private int measurement;

    @Override
    public String getDescription() {
        return "Hardwood regression metrics: CPU, JIT and GC time, allocation and live-set peak per iteration";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        cpuNanos = OS.getProcessCpuTime();
        jitMillis = JIT.getTotalCompilationTime();
        gcMillis = gcMillis();
        allocated = THREADS.getTotalThreadAllocatedBytes();
        LIVE_PEAK.set(-1);
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams,
                                                       IterationResult result) {
        double ops = result.getMetadata().getMeasuredOps();
        double cpuMs = (OS.getProcessCpuTime() - cpuNanos) / 1e6 / ops;
        double jitMs = (JIT.getTotalCompilationTime() - jitMillis) / ops;
        double gcMs = (gcMillis() - gcMillis) / ops;
        double allocBytes = (THREADS.getTotalThreadAllocatedBytes() - allocated) / ops;
        long peak = LIVE_PEAK.get();
        double liveBytes = peak < 0 ? Double.NaN : peak;
        boolean measured = iterationParams.getType() == IterationType.MEASUREMENT;
        int iteration = measured ? ++measurement : ++warmup;

        String path = System.getProperty("perf.metrics");
        if (path != null && !path.isBlank()) {
            append(Path.of(path), benchmarkParams, measured, iteration, result.getPrimaryResult().getScore(),
                    result.getPrimaryResult().getScoreUnit(), cpuMs, jitMs, gcMs, allocBytes, liveBytes);
        }
        List<Result> results = new ArrayList<>();
        results.add(new ScalarResult("·cpu", cpuMs, "ms/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("·jit", jitMs, "ms/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("·gc", gcMs, "ms/op", AggregationPolicy.AVG));
        results.add(new ScalarResult("·alloc", allocBytes, "B/op", AggregationPolicy.AVG));
        if (peak >= 0) {
            results.add(new ScalarResult("·live", liveBytes, "B", AggregationPolicy.MAX));
        }
        return results;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean collector : COLLECTORS) {
            total += Math.max(0, collector.getCollectionTime());
        }
        return total;
    }

    private static void append(Path path, BenchmarkParams benchmark, boolean measured, int iteration, double score,
                               String unit, double cpuMs, double jitMs, double gcMs, double allocBytes,
                               double liveBytes) {
        Map<String, String> params = new TreeMap<>();
        for (String key : benchmark.getParamsKeys()) {
            params.put(key, benchmark.getParam(key));
        }
        String line = String.format(Locale.ROOT,
                "{\"benchmark\":%s,\"params\":%s,\"fork\":%d,\"phase\":\"%s\",\"iteration\":%d,"
                        + "\"score\":%s,\"unit\":%s,\"cpuMs\":%s,\"jitMs\":%s,\"gcMs\":%s,\"allocBytes\":%s,"
                        + "\"liveBytes\":%s}%n",
                quote(benchmark.getBenchmark()), params.entrySet().stream()
                        .map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
                        .collect(Collectors.joining(",", "{", "}")),
                ProcessHandle.current().pid(), measured ? "measurement" : "warmup", iteration, number(score),
                quote(unit), number(cpuMs), number(jitMs), number(gcMs), number(allocBytes), number(liveBytes));
        try {
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
