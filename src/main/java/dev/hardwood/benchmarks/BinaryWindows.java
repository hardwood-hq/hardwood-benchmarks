/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import dev.hardwood.reader.ColumnReader;

/// A column reader's binary values in a batch, read through whichever accessors the Hardwood
/// version on the classpath has: a window per value (`BinaryValues`, 1.1.0.CR1 and later), or
/// the batch's byte buffer with each value's start and end (earlier versions). Value `i` is
/// `[start(i), end(i))` of `buffer(i)`.
///
/// The accessors are resolved once into constant method handles, which the JIT inlines, so a
/// benchmark compiles against every version and pays what a direct call costs.
public final class BinaryWindows {

    /// Whether the version on the classpath has `BinaryValues`.
    private static final boolean WINDOWS;
    /// `(ColumnReader) Object`: `getBinaryValues()`, a `BinaryValues` or a `byte[]`.
    private static final MethodHandle VALUES;
    /// `(Object, int) byte[]`, `(Object, int) int`: the window's accessors; `null` before CR1.
    private static final MethodHandle BUFFER;
    private static final MethodHandle START;
    private static final MethodHandle END;
    /// `(ColumnReader) int[]`: `getBinaryStarts()` and `getBinaryEnds()`; `null` from CR1 on.
    private static final MethodHandle STARTS;
    private static final MethodHandle ENDS;

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> windows = windowsClass();
        try {
            WINDOWS = windows != null;
            Class<?> valuesType = WINDOWS ? windows : byte[].class;
            VALUES = lookup.findVirtual(ColumnReader.class, "getBinaryValues", MethodType.methodType(valuesType))
                    .asType(MethodType.methodType(Object.class, ColumnReader.class));
            BUFFER = WINDOWS ? windowAccessor(lookup, windows, "buffer", byte[].class) : null;
            START = WINDOWS ? windowAccessor(lookup, windows, "start", int.class) : null;
            END = WINDOWS ? windowAccessor(lookup, windows, "end", int.class) : null;
            STARTS = WINDOWS ? null : rangeAccessor(lookup, "getBinaryStarts");
            ENDS = WINDOWS ? null : rangeAccessor(lookup, "getBinaryEnds");
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object values;
    private final int[] starts;
    private final int[] ends;

    private BinaryWindows(Object values, int[] starts, int[] ends) {
        this.values = values;
        this.starts = starts;
        this.ends = ends;
    }

    /// The binary values of `column`'s current batch.
    public static BinaryWindows of(ColumnReader column) {
        try {
            Object values = (Object) VALUES.invokeExact(column);
            if (WINDOWS) {
                return new BinaryWindows(values, null, null);
            }
            return new BinaryWindows(values, (int[]) STARTS.invokeExact(column), (int[]) ENDS.invokeExact(column));
        }
        catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /// The array value `i` is read from.
    public byte[] buffer(int i) {
        if (!WINDOWS) {
            return (byte[]) values;
        }
        try {
            return (byte[]) BUFFER.invokeExact(values, i);
        }
        catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /// Where value `i` starts in [#buffer(int)], inclusive.
    public int start(int i) {
        if (!WINDOWS) {
            return starts[i];
        }
        try {
            return (int) START.invokeExact(values, i);
        }
        catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /// Where value `i` ends in [#buffer(int)], exclusive.
    public int end(int i) {
        if (!WINDOWS) {
            return ends[i];
        }
        try {
            return (int) END.invokeExact(values, i);
        }
        catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private static Class<?> windowsClass() {
        try {
            return Class.forName("dev.hardwood.reader.BinaryValues", false, ColumnReader.class.getClassLoader());
        }
        catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static MethodHandle windowAccessor(MethodHandles.Lookup lookup, Class<?> windows, String name,
            Class<?> type) throws ReflectiveOperationException {
        return lookup.findVirtual(windows, name, MethodType.methodType(type, int.class))
                .asType(MethodType.methodType(type, Object.class, int.class));
    }

    private static MethodHandle rangeAccessor(MethodHandles.Lookup lookup, String name)
            throws ReflectiveOperationException {
        return lookup.findVirtual(ColumnReader.class, name, MethodType.methodType(int[].class))
                .asType(MethodType.methodType(int[].class, ColumnReader.class));
    }

    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(e);
    }
}
