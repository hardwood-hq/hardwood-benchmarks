"""PyArrow and DuckDB contenders for the S3 write benchmark (run-s3-write-python.sh).

Rewrites the file the Java benchmark's gate wrote (target/s3-write-fixture.parquet, Hardwood's
output for the same fixture), so every writer encodes exactly the same values: to memory or a
local file, and to the emulated object store's write port. ZSTD, row groups of the fixture file's
size. PyArrow runs at its default ZSTD level (1) and at level 3, the level of the other writers,
and writes the page index and page checksums, as the Java contenders do. DuckDB's upload parts are
set to 8 MB, the Java contenders' size, from its default of about 80 MB; PyArrow uploads in parts
of its own fixed size, in the background. Every output is read back once and compared with the
source table before its timings are reported. Times are the mean and median of the measured runs.
"""
import os
import statistics
import sys
import time

import duckdb
import pyarrow as pa
import pyarrow.fs as pafs
import pyarrow.parquet as pq

FIXTURE = 'target/s3-write-fixture.parquet'
ENDPOINT = os.environ.get('PERF_S3_WRITE_ENDPOINT', '127.0.0.1:18082')
WARMUP = int(os.environ.get('PERF_WARMUP', '2'))
MEASURED = int(os.environ.get('PERF_MEAS', '5'))


def bench(name, write, verify):
    """Times `write`, then checks its output once with `verify`, which returns the output's size."""
    times = []
    for i in range(WARMUP + MEASURED):
        start = time.perf_counter()
        write()
        elapsed = (time.perf_counter() - start) * 1000
        if i >= WARMUP:
            times.append(elapsed)
    size = verify()
    print(f'{name:32s} mean {statistics.mean(times):7.1f} ms  median {statistics.median(times):7.1f} ms'
          f'  {size:,} bytes', flush=True)


def check_equal(name, read, source):
    """Fails unless `read` holds the same values as `source`, column by column."""
    if read.num_rows != source.num_rows:
        sys.exit(f'{name}: read back {read.num_rows:,} rows, wrote {source.num_rows:,}')
    for field in source.schema:
        if not read.column(field.name).cast(field.type).equals(source.column(field.name)):
            sys.exit(f'{name}: column {field.name} differs from the source')


def main():
    if not os.path.exists(FIXTURE):
        sys.exit(f'{FIXTURE} is missing; run ./run-s3-write.sh --gate first')
    table = pq.read_table(FIXTURE)
    row_group = pq.ParquetFile(FIXTURE).metadata.row_group(0).num_rows
    print(f'{table.num_rows:,} rows, row groups of {row_group:,}; pyarrow {pa.__version__}, duckdb {duckdb.__version__}')

    s3 = pafs.S3FileSystem(access_key='access', secret_key='secret', endpoint_override=ENDPOINT,
                           scheme='http', region='us-east-1')

    options = dict(compression='zstd', row_group_size=row_group, write_page_index=True, write_page_checksum=True)
    key = 'test-bucket/write/pyarrow.parquet'
    for level in (None, 3):
        label = 'default level' if level is None else f'level {level}'
        sink = {}

        def pyarrow_memory():
            sink['buffer'] = pa.BufferOutputStream()
            pq.write_table(table, sink['buffer'], compression_level=level, **options)

        def verify_memory():
            data = sink['buffer'].getvalue()
            check_equal('pyarrowMemory', pq.read_table(pa.BufferReader(data)), table)
            return data.size

        def pyarrow_s3():
            pq.write_table(table, key, filesystem=s3, compression_level=level, **options)

        def verify_s3():
            check_equal('pyarrowS3', pq.read_table(key, filesystem=s3), table)
            return s3.get_file_info(key).size

        bench(f'pyarrowMemory [{label}]', pyarrow_memory, verify_memory)
        bench(f'pyarrowS3 [{label}]', pyarrow_s3, verify_s3)

    con = duckdb.connect()
    con.execute('INSTALL httpfs; LOAD httpfs;')
    con.execute(f"CREATE SECRET (TYPE s3, KEY_ID 'access', SECRET 'secret', ENDPOINT '{ENDPOINT}', "
                "URL_STYLE 'path', USE_SSL false, REGION 'us-east-1')")
    # Parts of 8 MB, the Java contenders' size: DuckDB sizes a part as this limit / 10,000 parts.
    con.execute("SET s3_uploader_max_filesize='80GB'")
    con.register('fixture', table)
    con.execute('CREATE TABLE src AS SELECT * FROM fixture')
    local = 'target/s3-write-duckdb.parquet'
    for threads in (1, max(1, (os.cpu_count() or 2) - 1)):
        con.execute(f'SET threads={threads}')

        def duckdb_local():
            con.execute(f"COPY src TO '{local}' (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE {row_group})")

        def verify_local():
            check_equal('duckdbLocal', pq.read_table(local), table)
            return os.path.getsize(local)

        def duckdb_s3():
            con.execute("COPY src TO 's3://test-bucket/write/duckdb.parquet' "
                        f"(FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE {row_group})")

        def verify_s3():
            check_equal('duckdbS3', pq.read_table('test-bucket/write/duckdb.parquet', filesystem=s3), table)
            return s3.get_file_info('test-bucket/write/duckdb.parquet').size

        bench(f'duckdbLocal [threads={threads}]', duckdb_local, verify_local)
        bench(f'duckdbS3 [threads={threads}]', duckdb_s3, verify_s3)
    os.remove(local)


if __name__ == '__main__':
    main()
