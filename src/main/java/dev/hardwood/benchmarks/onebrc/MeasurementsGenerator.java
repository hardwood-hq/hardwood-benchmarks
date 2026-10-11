/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.onebrc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.util.HadoopOutputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

/// Generates the 1BRC measurements file: a required `STRING` column `station` and a required
/// `DOUBLE` column `measurement`.
///
/// The stations and the distribution are those of the original challenge's
/// `CreateMeasurements`: a station drawn uniformly from 413, a gaussian temperature around its
/// mean with a standard deviation of 10, rounded to one fractional digit. A seeded [Random]
/// makes the file reproducible.
///
/// parquet-java writes it, so the file is the same whichever Hardwood version is measured. The
/// layout follows Hardwood's writer defaults: row groups of 2^20 rows, pages bounded by size
/// alone, both columns dictionary-encoded, ZSTD. The generator also writes the aggregate it
/// expects next to the file ([#expected(Path)]), computed from the values as they are drawn,
/// so the gate checks every contender against a result no Parquet reader produced.
final class MeasurementsGenerator {

    private static final long SEED = 1_000_000_007L;
    private static final int ROW_GROUP_ROWS = 1 << 20;

    private static final MessageType SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("station")
            .required(PrimitiveTypeName.DOUBLE).named("measurement")
            .named("measurements");

    private record Station(String name, double mean) {
    }

    private MeasurementsGenerator() {
    }

    /// The file holding the aggregate expected from `file`.
    static Path expected(Path file) {
        return file.resolveSibling(file.getFileName() + ".expected.txt");
    }

    /// Writes the file and its expected aggregate if they are not already present. Cached
    /// across runs, keyed on the path only, so a caller changing the row count must encode it
    /// in the file name.
    static void ensure(Path file, long rows) throws IOException {
        Path expected = expected(file);
        if (Files.exists(file) && Files.exists(expected)) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        System.out.printf("Generating %,d-row 1BRC measurements file at %s...%n", rows, file);

        Station[] stations = stations();
        Binary[] names = new Binary[stations.length];
        StationTable table = new StationTable();
        int[] slots = new int[stations.length];
        for (int s = 0; s < stations.length; s++) {
            byte[] name = stations[s].name().getBytes(StandardCharsets.UTF_8);
            names[s] = Binary.fromConstantByteArray(name);
            slots[s] = table.slot(name);
        }

        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Configuration conf = new Configuration();
        OutputFile out = HadoopOutputFile.fromPath(
                new org.apache.hadoop.fs.Path(partial.toAbsolutePath().toString()), conf);
        Row row = new Row();
        Random random = new Random(SEED);
        try (ParquetWriter<Row> writer = new Builder(out)
                .withConf(conf)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withDictionaryEncoding(true)
                .withRowGroupRowCountLimit(ROW_GROUP_ROWS)
                .withRowGroupSize(Long.MAX_VALUE)
                .withPageRowCountLimit(Integer.MAX_VALUE)
                .withWriterVersion(ParquetProperties.WriterVersion.PARQUET_1_0)
                .withPageWriteChecksumEnabled(false)
                .build()) {
            for (long i = 0; i < rows; i++) {
                int s = random.nextInt(stations.length);
                long tenths = Math.round(random.nextGaussian(stations[s].mean(), 10) * 10.0);
                table.add(slots[s], tenths);
                row.station = names[s];
                row.measurement = tenths / 10.0;
                writer.write(row);
            }
        }
        Files.move(partial, file);
        Files.writeString(expected, table.format() + "\n");
        System.out.printf("Generated %s (%,d MB)%n", file, Files.size(file) / 1_000_000);
    }

    /// The row being written; one instance is reused for every row.
    private static final class Row {
        Binary station;
        double measurement;
    }

    private static final class Builder extends ParquetWriter.Builder<Row, Builder> {

        Builder(OutputFile file) {
            super(file);
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        protected WriteSupport<Row> getWriteSupport(Configuration conf) {
            return new RowWriteSupport();
        }
    }

    private static final class RowWriteSupport extends WriteSupport<Row> {

        private RecordConsumer consumer;

        @Override
        public WriteContext init(Configuration configuration) {
            return new WriteContext(SCHEMA, new HashMap<>());
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            consumer = recordConsumer;
        }

        @Override
        public void write(Row row) {
            consumer.startMessage();
            consumer.startField("station", 0);
            consumer.addBinary(row.station);
            consumer.endField("station", 0);
            consumer.startField("measurement", 1);
            consumer.addDouble(row.measurement);
            consumer.endField("measurement", 1);
            consumer.endMessage();
        }
    }

    private static Station[] stations() {
        // @formatter:off
        return new Station[] {
            new Station("Abha", 18.0),
            new Station("Abidjan", 26.0),
            new Station("Abéché", 29.4),
            new Station("Accra", 26.4),
            new Station("Addis Ababa", 16.0),
            new Station("Adelaide", 17.3),
            new Station("Aden", 29.1),
            new Station("Ahvaz", 25.4),
            new Station("Albuquerque", 14.0),
            new Station("Alexandra", 11.0),
            new Station("Alexandria", 20.0),
            new Station("Algiers", 18.2),
            new Station("Alice Springs", 21.0),
            new Station("Almaty", 10.0),
            new Station("Amsterdam", 10.2),
            new Station("Anadyr", -6.9),
            new Station("Anchorage", 2.8),
            new Station("Andorra la Vella", 9.8),
            new Station("Ankara", 12.0),
            new Station("Antananarivo", 17.9),
            new Station("Antsiranana", 25.2),
            new Station("Arkhangelsk", 1.3),
            new Station("Ashgabat", 17.1),
            new Station("Asmara", 15.6),
            new Station("Assab", 30.5),
            new Station("Astana", 3.5),
            new Station("Athens", 19.2),
            new Station("Atlanta", 17.0),
            new Station("Auckland", 15.2),
            new Station("Austin", 20.7),
            new Station("Baghdad", 22.77),
            new Station("Baguio", 19.5),
            new Station("Baku", 15.1),
            new Station("Baltimore", 13.1),
            new Station("Bamako", 27.8),
            new Station("Bangkok", 28.6),
            new Station("Bangui", 26.0),
            new Station("Banjul", 26.0),
            new Station("Barcelona", 18.2),
            new Station("Bata", 25.1),
            new Station("Batumi", 14.0),
            new Station("Beijing", 12.9),
            new Station("Beirut", 20.9),
            new Station("Belgrade", 12.5),
            new Station("Belize City", 26.7),
            new Station("Benghazi", 19.9),
            new Station("Bergen", 7.7),
            new Station("Berlin", 10.3),
            new Station("Bilbao", 14.7),
            new Station("Birao", 26.5),
            new Station("Bishkek", 11.3),
            new Station("Bissau", 27.0),
            new Station("Blantyre", 22.2),
            new Station("Bloemfontein", 15.6),
            new Station("Boise", 11.4),
            new Station("Bordeaux", 14.2),
            new Station("Bosaso", 30.0),
            new Station("Boston", 10.9),
            new Station("Bouaké", 26.0),
            new Station("Bratislava", 10.5),
            new Station("Brazzaville", 25.0),
            new Station("Bridgetown", 27.0),
            new Station("Brisbane", 21.4),
            new Station("Brussels", 10.5),
            new Station("Bucharest", 10.8),
            new Station("Budapest", 11.3),
            new Station("Bujumbura", 23.8),
            new Station("Bulawayo", 18.9),
            new Station("Burnie", 13.1),
            new Station("Busan", 15.0),
            new Station("Cabo San Lucas", 23.9),
            new Station("Cairns", 25.0),
            new Station("Cairo", 21.4),
            new Station("Calgary", 4.4),
            new Station("Canberra", 13.1),
            new Station("Cape Town", 16.2),
            new Station("Changsha", 17.4),
            new Station("Charlotte", 16.1),
            new Station("Chiang Mai", 25.8),
            new Station("Chicago", 9.8),
            new Station("Chihuahua", 18.6),
            new Station("Chișinău", 10.2),
            new Station("Chittagong", 25.9),
            new Station("Chongqing", 18.6),
            new Station("Christchurch", 12.2),
            new Station("City of San Marino", 11.8),
            new Station("Colombo", 27.4),
            new Station("Columbus", 11.7),
            new Station("Conakry", 26.4),
            new Station("Copenhagen", 9.1),
            new Station("Cotonou", 27.2),
            new Station("Cracow", 9.3),
            new Station("Da Lat", 17.9),
            new Station("Da Nang", 25.8),
            new Station("Dakar", 24.0),
            new Station("Dallas", 19.0),
            new Station("Damascus", 17.0),
            new Station("Dampier", 26.4),
            new Station("Dar es Salaam", 25.8),
            new Station("Darwin", 27.6),
            new Station("Denpasar", 23.7),
            new Station("Denver", 10.4),
            new Station("Detroit", 10.0),
            new Station("Dhaka", 25.9),
            new Station("Dikson", -11.1),
            new Station("Dili", 26.6),
            new Station("Djibouti", 29.9),
            new Station("Dodoma", 22.7),
            new Station("Dolisie", 24.0),
            new Station("Douala", 26.7),
            new Station("Dubai", 26.9),
            new Station("Dublin", 9.8),
            new Station("Dunedin", 11.1),
            new Station("Durban", 20.6),
            new Station("Dushanbe", 14.7),
            new Station("Edinburgh", 9.3),
            new Station("Edmonton", 4.2),
            new Station("El Paso", 18.1),
            new Station("Entebbe", 21.0),
            new Station("Erbil", 19.5),
            new Station("Erzurum", 5.1),
            new Station("Fairbanks", -2.3),
            new Station("Fianarantsoa", 17.9),
            new Station("Flores,  Petén", 26.4),
            new Station("Frankfurt", 10.6),
            new Station("Fresno", 17.9),
            new Station("Fukuoka", 17.0),
            new Station("Gabès", 19.5),
            new Station("Gaborone", 21.0),
            new Station("Gagnoa", 26.0),
            new Station("Gangtok", 15.2),
            new Station("Garissa", 29.3),
            new Station("Garoua", 28.3),
            new Station("George Town", 27.9),
            new Station("Ghanzi", 21.4),
            new Station("Gjoa Haven", -14.4),
            new Station("Guadalajara", 20.9),
            new Station("Guangzhou", 22.4),
            new Station("Guatemala City", 20.4),
            new Station("Halifax", 7.5),
            new Station("Hamburg", 9.7),
            new Station("Hamilton", 13.8),
            new Station("Hanga Roa", 20.5),
            new Station("Hanoi", 23.6),
            new Station("Harare", 18.4),
            new Station("Harbin", 5.0),
            new Station("Hargeisa", 21.7),
            new Station("Hat Yai", 27.0),
            new Station("Havana", 25.2),
            new Station("Helsinki", 5.9),
            new Station("Heraklion", 18.9),
            new Station("Hiroshima", 16.3),
            new Station("Ho Chi Minh City", 27.4),
            new Station("Hobart", 12.7),
            new Station("Hong Kong", 23.3),
            new Station("Honiara", 26.5),
            new Station("Honolulu", 25.4),
            new Station("Houston", 20.8),
            new Station("Ifrane", 11.4),
            new Station("Indianapolis", 11.8),
            new Station("Iqaluit", -9.3),
            new Station("Irkutsk", 1.0),
            new Station("Istanbul", 13.9),
            new Station("İzmir", 17.9),
            new Station("Jacksonville", 20.3),
            new Station("Jakarta", 26.7),
            new Station("Jayapura", 27.0),
            new Station("Jerusalem", 18.3),
            new Station("Johannesburg", 15.5),
            new Station("Jos", 22.8),
            new Station("Juba", 27.8),
            new Station("Kabul", 12.1),
            new Station("Kampala", 20.0),
            new Station("Kandi", 27.7),
            new Station("Kankan", 26.5),
            new Station("Kano", 26.4),
            new Station("Kansas City", 12.5),
            new Station("Karachi", 26.0),
            new Station("Karonga", 24.4),
            new Station("Kathmandu", 18.3),
            new Station("Khartoum", 29.9),
            new Station("Kingston", 27.4),
            new Station("Kinshasa", 25.3),
            new Station("Kolkata", 26.7),
            new Station("Kuala Lumpur", 27.3),
            new Station("Kumasi", 26.0),
            new Station("Kunming", 15.7),
            new Station("Kuopio", 3.4),
            new Station("Kuwait City", 25.7),
            new Station("Kyiv", 8.4),
            new Station("Kyoto", 15.8),
            new Station("La Ceiba", 26.2),
            new Station("La Paz", 23.7),
            new Station("Lagos", 26.8),
            new Station("Lahore", 24.3),
            new Station("Lake Havasu City", 23.7),
            new Station("Lake Tekapo", 8.7),
            new Station("Las Palmas de Gran Canaria", 21.2),
            new Station("Las Vegas", 20.3),
            new Station("Launceston", 13.1),
            new Station("Lhasa", 7.6),
            new Station("Libreville", 25.9),
            new Station("Lisbon", 17.5),
            new Station("Livingstone", 21.8),
            new Station("Ljubljana", 10.9),
            new Station("Lodwar", 29.3),
            new Station("Lomé", 26.9),
            new Station("London", 11.3),
            new Station("Los Angeles", 18.6),
            new Station("Louisville", 13.9),
            new Station("Luanda", 25.8),
            new Station("Lubumbashi", 20.8),
            new Station("Lusaka", 19.9),
            new Station("Luxembourg City", 9.3),
            new Station("Lviv", 7.8),
            new Station("Lyon", 12.5),
            new Station("Madrid", 15.0),
            new Station("Mahajanga", 26.3),
            new Station("Makassar", 26.7),
            new Station("Makurdi", 26.0),
            new Station("Malabo", 26.3),
            new Station("Malé", 28.0),
            new Station("Managua", 27.3),
            new Station("Manama", 26.5),
            new Station("Mandalay", 28.0),
            new Station("Mango", 28.1),
            new Station("Manila", 28.4),
            new Station("Maputo", 22.8),
            new Station("Marrakesh", 19.6),
            new Station("Marseille", 15.8),
            new Station("Maun", 22.4),
            new Station("Medan", 26.5),
            new Station("Mek'ele", 22.7),
            new Station("Melbourne", 15.1),
            new Station("Memphis", 17.2),
            new Station("Mexicali", 23.1),
            new Station("Mexico City", 17.5),
            new Station("Miami", 24.9),
            new Station("Milan", 13.0),
            new Station("Milwaukee", 8.9),
            new Station("Minneapolis", 7.8),
            new Station("Minsk", 6.7),
            new Station("Mogadishu", 27.1),
            new Station("Mombasa", 26.3),
            new Station("Monaco", 16.4),
            new Station("Moncton", 6.1),
            new Station("Monterrey", 22.3),
            new Station("Montreal", 6.8),
            new Station("Moscow", 5.8),
            new Station("Mumbai", 27.1),
            new Station("Murmansk", 0.6),
            new Station("Muscat", 28.0),
            new Station("Mzuzu", 17.7),
            new Station("N'Djamena", 28.3),
            new Station("Naha", 23.1),
            new Station("Nairobi", 17.8),
            new Station("Nakhon Ratchasima", 27.3),
            new Station("Napier", 14.6),
            new Station("Napoli", 15.9),
            new Station("Nashville", 15.4),
            new Station("Nassau", 24.6),
            new Station("Ndola", 20.3),
            new Station("New Delhi", 25.0),
            new Station("New Orleans", 20.7),
            new Station("New York City", 12.9),
            new Station("Ngaoundéré", 22.0),
            new Station("Niamey", 29.3),
            new Station("Nicosia", 19.7),
            new Station("Niigata", 13.9),
            new Station("Nouadhibou", 21.3),
            new Station("Nouakchott", 25.7),
            new Station("Novosibirsk", 1.7),
            new Station("Nuuk", -1.4),
            new Station("Odesa", 10.7),
            new Station("Odienné", 26.0),
            new Station("Oklahoma City", 15.9),
            new Station("Omaha", 10.6),
            new Station("Oranjestad", 28.1),
            new Station("Oslo", 5.7),
            new Station("Ottawa", 6.6),
            new Station("Ouagadougou", 28.3),
            new Station("Ouahigouya", 28.6),
            new Station("Ouarzazate", 18.9),
            new Station("Oulu", 2.7),
            new Station("Palembang", 27.3),
            new Station("Palermo", 18.5),
            new Station("Palm Springs", 24.5),
            new Station("Palmerston North", 13.2),
            new Station("Panama City", 28.0),
            new Station("Parakou", 26.8),
            new Station("Paris", 12.3),
            new Station("Perth", 18.7),
            new Station("Petropavlovsk-Kamchatsky", 1.9),
            new Station("Philadelphia", 13.2),
            new Station("Phnom Penh", 28.3),
            new Station("Phoenix", 23.9),
            new Station("Pittsburgh", 10.8),
            new Station("Podgorica", 15.3),
            new Station("Pointe-Noire", 26.1),
            new Station("Pontianak", 27.7),
            new Station("Port Moresby", 26.9),
            new Station("Port Sudan", 28.4),
            new Station("Port Vila", 24.3),
            new Station("Port-Gentil", 26.0),
            new Station("Portland (OR)", 12.4),
            new Station("Porto", 15.7),
            new Station("Prague", 8.4),
            new Station("Praia", 24.4),
            new Station("Pretoria", 18.2),
            new Station("Pyongyang", 10.8),
            new Station("Rabat", 17.2),
            new Station("Rangpur", 24.4),
            new Station("Reggane", 28.3),
            new Station("Reykjavík", 4.3),
            new Station("Riga", 6.2),
            new Station("Riyadh", 26.0),
            new Station("Rome", 15.2),
            new Station("Roseau", 26.2),
            new Station("Rostov-on-Don", 9.9),
            new Station("Sacramento", 16.3),
            new Station("Saint Petersburg", 5.8),
            new Station("Saint-Pierre", 5.7),
            new Station("Salt Lake City", 11.6),
            new Station("San Antonio", 20.8),
            new Station("San Diego", 17.8),
            new Station("San Francisco", 14.6),
            new Station("San Jose", 16.4),
            new Station("San José", 22.6),
            new Station("San Juan", 27.2),
            new Station("San Salvador", 23.1),
            new Station("Sana'a", 20.0),
            new Station("Santo Domingo", 25.9),
            new Station("Sapporo", 8.9),
            new Station("Sarajevo", 10.1),
            new Station("Saskatoon", 3.3),
            new Station("Seattle", 11.3),
            new Station("Ségou", 28.0),
            new Station("Seoul", 12.5),
            new Station("Seville", 19.2),
            new Station("Shanghai", 16.7),
            new Station("Singapore", 27.0),
            new Station("Skopje", 12.4),
            new Station("Sochi", 14.2),
            new Station("Sofia", 10.6),
            new Station("Sokoto", 28.0),
            new Station("Split", 16.1),
            new Station("St. John's", 5.0),
            new Station("St. Louis", 13.9),
            new Station("Stockholm", 6.6),
            new Station("Surabaya", 27.1),
            new Station("Suva", 25.6),
            new Station("Suwałki", 7.2),
            new Station("Sydney", 17.7),
            new Station("Tabora", 23.0),
            new Station("Tabriz", 12.6),
            new Station("Taipei", 23.0),
            new Station("Tallinn", 6.4),
            new Station("Tamale", 27.9),
            new Station("Tamanrasset", 21.7),
            new Station("Tampa", 22.9),
            new Station("Tashkent", 14.8),
            new Station("Tauranga", 14.8),
            new Station("Tbilisi", 12.9),
            new Station("Tegucigalpa", 21.7),
            new Station("Tehran", 17.0),
            new Station("Tel Aviv", 20.0),
            new Station("Thessaloniki", 16.0),
            new Station("Thiès", 24.0),
            new Station("Tijuana", 17.8),
            new Station("Timbuktu", 28.0),
            new Station("Tirana", 15.2),
            new Station("Toamasina", 23.4),
            new Station("Tokyo", 15.4),
            new Station("Toliara", 24.1),
            new Station("Toluca", 12.4),
            new Station("Toronto", 9.4),
            new Station("Tripoli", 20.0),
            new Station("Tromsø", 2.9),
            new Station("Tucson", 20.9),
            new Station("Tunis", 18.4),
            new Station("Ulaanbaatar", -0.4),
            new Station("Upington", 20.4),
            new Station("Ürümqi", 7.4),
            new Station("Vaduz", 10.1),
            new Station("Valencia", 18.3),
            new Station("Valletta", 18.8),
            new Station("Vancouver", 10.4),
            new Station("Veracruz", 25.4),
            new Station("Vienna", 10.4),
            new Station("Vientiane", 25.9),
            new Station("Villahermosa", 27.1),
            new Station("Vilnius", 6.0),
            new Station("Virginia Beach", 15.8),
            new Station("Vladivostok", 4.9),
            new Station("Warsaw", 8.5),
            new Station("Washington, D.C.", 14.6),
            new Station("Wau", 27.8),
            new Station("Wellington", 12.9),
            new Station("Whitehorse", -0.1),
            new Station("Wichita", 13.9),
            new Station("Willemstad", 28.0),
            new Station("Winnipeg", 3.0),
            new Station("Wrocław", 9.6),
            new Station("Xi'an", 14.1),
            new Station("Yakutsk", -8.8),
            new Station("Yangon", 27.5),
            new Station("Yaoundé", 23.8),
            new Station("Yellowknife", -4.3),
            new Station("Yerevan", 12.4),
            new Station("Yinchuan", 9.0),
            new Station("Zagreb", 10.7),
            new Station("Zanzibar City", 26.0),
            new Station("Zürich", 9.3)
        };
        // @formatter:on
    }
}
