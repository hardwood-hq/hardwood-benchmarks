/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.flat;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecordBase;

/// Avro `SpecificRecord` for the 2025 TLC schema, used by the **internal-reference**
/// `avroParquetReaderSpecific` contender (not published — see that method). Lets
/// `AvroParquetReader` materialize a typed, generated-style record instead of
/// `GenericRecord`, so the record-path lead can be checked against the fastest Avro
/// record class, not just `GenericRecord`.
///
/// Hand-written (rather than codegen) to stay self-contained; field types and order
/// mirror the schema parquet-avro derives from the taxi files, so it resolves with
/// no schema-resolution gymnastics. `SCHEMA$` carries an alias for the file's
/// derived record name (`schema`) so the read schema (this class's full name)
/// resolves against the file. Nullable fields are boxed; timestamps are raw `long`
/// (no logical-type conversion), matching the `GenericRecord` baseline's folds.
public class SpecificTaxiTrip extends SpecificRecordBase {

    public static final Schema SCHEMA$ = new Schema.Parser().parse("""
        {
          "type": "record",
          "name": "SpecificTaxiTrip",
          "namespace": "dev.hardwood.benchmarks.flat",
          "aliases": ["schema"],
          "fields": [
            {"name": "VendorID", "type": ["null", "int"], "default": null},
            {"name": "tpep_pickup_datetime", "type": ["null", {"type": "long", "logicalType": "local-timestamp-micros"}], "default": null},
            {"name": "tpep_dropoff_datetime", "type": ["null", {"type": "long", "logicalType": "local-timestamp-micros"}], "default": null},
            {"name": "passenger_count", "type": ["null", "long"], "default": null},
            {"name": "trip_distance", "type": ["null", "double"], "default": null},
            {"name": "RatecodeID", "type": ["null", "long"], "default": null},
            {"name": "store_and_fwd_flag", "type": ["null", "string"], "default": null},
            {"name": "PULocationID", "type": ["null", "int"], "default": null},
            {"name": "DOLocationID", "type": ["null", "int"], "default": null},
            {"name": "payment_type", "type": ["null", "long"], "default": null},
            {"name": "fare_amount", "type": ["null", "double"], "default": null},
            {"name": "extra", "type": ["null", "double"], "default": null},
            {"name": "mta_tax", "type": ["null", "double"], "default": null},
            {"name": "tip_amount", "type": ["null", "double"], "default": null},
            {"name": "tolls_amount", "type": ["null", "double"], "default": null},
            {"name": "improvement_surcharge", "type": ["null", "double"], "default": null},
            {"name": "total_amount", "type": ["null", "double"], "default": null},
            {"name": "congestion_surcharge", "type": ["null", "double"], "default": null},
            {"name": "Airport_fee", "type": ["null", "double"], "default": null},
            {"name": "cbd_congestion_fee", "type": ["null", "double"], "default": null}
          ]
        }
        """);

    public Integer vendorId;
    public Long tpepPickupDatetime;
    public Long tpepDropoffDatetime;
    public Long passengerCount;
    public Double tripDistance;
    public Long ratecodeId;
    public CharSequence storeAndFwdFlag;
    public Integer puLocationId;
    public Integer doLocationId;
    public Long paymentType;
    public Double fareAmount;
    public Double extra;
    public Double mtaTax;
    public Double tipAmount;
    public Double tollsAmount;
    public Double improvementSurcharge;
    public Double totalAmount;
    public Double congestionSurcharge;
    public Double airportFee;
    public Double cbdCongestionFee;

    @Override
    public Schema getSchema() {
        return SCHEMA$;
    }

    @Override
    public Object get(int field) {
        return switch (field) {
            case 0 -> vendorId;
            case 1 -> tpepPickupDatetime;
            case 2 -> tpepDropoffDatetime;
            case 3 -> passengerCount;
            case 4 -> tripDistance;
            case 5 -> ratecodeId;
            case 6 -> storeAndFwdFlag;
            case 7 -> puLocationId;
            case 8 -> doLocationId;
            case 9 -> paymentType;
            case 10 -> fareAmount;
            case 11 -> extra;
            case 12 -> mtaTax;
            case 13 -> tipAmount;
            case 14 -> tollsAmount;
            case 15 -> improvementSurcharge;
            case 16 -> totalAmount;
            case 17 -> congestionSurcharge;
            case 18 -> airportFee;
            case 19 -> cbdCongestionFee;
            default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field);
        };
    }

    @Override
    public void put(int field, Object value) {
        switch (field) {
            case 0 -> vendorId = (Integer) value;
            case 1 -> tpepPickupDatetime = (Long) value;
            case 2 -> tpepDropoffDatetime = (Long) value;
            case 3 -> passengerCount = (Long) value;
            case 4 -> tripDistance = (Double) value;
            case 5 -> ratecodeId = (Long) value;
            case 6 -> storeAndFwdFlag = (CharSequence) value;
            case 7 -> puLocationId = (Integer) value;
            case 8 -> doLocationId = (Integer) value;
            case 9 -> paymentType = (Long) value;
            case 10 -> fareAmount = (Double) value;
            case 11 -> extra = (Double) value;
            case 12 -> mtaTax = (Double) value;
            case 13 -> tipAmount = (Double) value;
            case 14 -> tollsAmount = (Double) value;
            case 15 -> improvementSurcharge = (Double) value;
            case 16 -> totalAmount = (Double) value;
            case 17 -> congestionSurcharge = (Double) value;
            case 18 -> airportFee = (Double) value;
            case 19 -> cbdCongestionFee = (Double) value;
            default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field);
        }
    }
}
