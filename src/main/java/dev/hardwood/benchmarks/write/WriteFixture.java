/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.write;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;

/// Flat, taxi-shaped records for the write benchmark, generated from a fixed seed and held in
/// batches of [#BATCH_ROWS]. Each column is kept in the form each write API takes it: arrays
/// and UTF-8 bytes for the column writer, `String`s and `Instant`s for the row writer, so
/// neither contender converts inside the measured region what a caller of its API already
/// holds.
final class WriteFixture {

    static final int BATCH_ROWS = 1024;

    private static final long SEED = 20_260_924L;
    private static final String[] PAYMENT_TYPES = { "CREDIT", "CASH", "NO_CHARGE", "DISPUTE" };
    private static final int VENDOR_COUNT = 20;
    private static final int PASSENGER_COUNT_NULL_PERCENT = 5;
    private static final int VENDOR_NULL_PERCENT = 10;
    private static final long BASE_MICROS = 1_704_067_200_000_000L;
    private static final int PICKUP_SPACING_MICROS = 1_000_000;
    private static final byte[] ABSENT = new byte[0];

    final int rows;
    final long[][] id;
    final long[][] pickupMicros;
    final Instant[][] pickup;
    final int[][] passengerCount;
    final boolean[][] passengerCountNulls;
    final double[][] fare;
    final byte[][][] paymentTypeBytes;
    final String[][] paymentType;
    final byte[][][] vendorBytes;
    final String[][] vendor;
    final boolean[][] vendorNulls;

    WriteFixture(int rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive: " + rows);
        }
        this.rows = rows;
        int batches = (rows + BATCH_ROWS - 1) / BATCH_ROWS;
        id = new long[batches][];
        pickupMicros = new long[batches][];
        pickup = new Instant[batches][];
        passengerCount = new int[batches][];
        passengerCountNulls = new boolean[batches][];
        fare = new double[batches][];
        paymentTypeBytes = new byte[batches][][];
        paymentType = new String[batches][];
        vendorBytes = new byte[batches][][];
        vendor = new String[batches][];
        vendorNulls = new boolean[batches][];

        String[] vendors = new String[VENDOR_COUNT];
        byte[][] vendorUtf8 = new byte[VENDOR_COUNT][];
        for (int v = 0; v < VENDOR_COUNT; v++) {
            vendors[v] = "vendor-" + v;
            vendorUtf8[v] = vendors[v].getBytes(StandardCharsets.UTF_8);
        }
        byte[][] paymentUtf8 = new byte[PAYMENT_TYPES.length][];
        for (int p = 0; p < PAYMENT_TYPES.length; p++) {
            paymentUtf8[p] = PAYMENT_TYPES[p].getBytes(StandardCharsets.UTF_8);
        }

        Random random = new Random(SEED);
        long nextId = 0;
        long micros = BASE_MICROS;
        for (int b = 0; b < batches; b++) {
            int length = Math.min(BATCH_ROWS, rows - b * BATCH_ROWS);
            id[b] = new long[length];
            pickupMicros[b] = new long[length];
            pickup[b] = new Instant[length];
            passengerCount[b] = new int[length];
            passengerCountNulls[b] = new boolean[length];
            fare[b] = new double[length];
            paymentTypeBytes[b] = new byte[length][];
            paymentType[b] = new String[length];
            vendorBytes[b] = new byte[length][];
            vendor[b] = new String[length];
            vendorNulls[b] = new boolean[length];
            for (int r = 0; r < length; r++) {
                id[b][r] = nextId++;
                micros += PICKUP_SPACING_MICROS + random.nextInt(PICKUP_SPACING_MICROS);
                pickupMicros[b][r] = micros;
                pickup[b][r] = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
                passengerCountNulls[b][r] = random.nextInt(100) < PASSENGER_COUNT_NULL_PERCENT;
                passengerCount[b][r] = passengerCountNulls[b][r] ? 0 : 1 + random.nextInt(6);
                fare[b][r] = 3.5 + random.nextDouble() * 96.5;
                int payment = random.nextInt(PAYMENT_TYPES.length);
                paymentTypeBytes[b][r] = paymentUtf8[payment];
                paymentType[b][r] = PAYMENT_TYPES[payment];
                vendorNulls[b][r] = random.nextInt(100) < VENDOR_NULL_PERCENT;
                int v = random.nextInt(VENDOR_COUNT);
                vendorBytes[b][r] = vendorNulls[b][r] ? ABSENT : vendorUtf8[v];
                vendor[b][r] = vendorNulls[b][r] ? null : vendors[v];
            }
        }
    }

    int batchCount() {
        return id.length;
    }

    static FileSchema schema() {
        return FileSchema.builder("flat")
                .addColumn("id", PhysicalType.INT64, RepetitionType.REQUIRED)
                .addColumn("pickup_ts", PhysicalType.INT64, RepetitionType.REQUIRED,
                        new LogicalType.TimestampType(true, LogicalType.TimeUnit.MICROS))
                .addColumn("passenger_count", PhysicalType.INT32, RepetitionType.OPTIONAL)
                .addColumn("fare", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
                .addColumn("payment_type", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, new LogicalType.StringType())
                .addColumn("vendor", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType())
                .build();
    }
}
