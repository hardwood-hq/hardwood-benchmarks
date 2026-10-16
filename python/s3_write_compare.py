"""PyArrow and DuckDB contenders for the S3 write benchmark (run-s3-write-python.sh).

Rewrites the file the Java benchmark's gate wrote (target/s3-write-fixture.parquet, Hardwood's
output for the same fixture), so every writer encodes exactly the same values: to memory or a
local file, and to the emulated object store's write port. ZSTD, row groups of the fixture file's
size. PyArrow runs at its default ZSTD level (1) and at level 3, the level of the other writers.
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


def bench(name, fn, rows):
    times, size = [], None
    for i in range(WARMUP + MEASURED):
        start = time.perf_counter()
        size = fn()
        elapsed = (time.perf_counter() - start) * 1000
        if i >= WARMUP:
            times.append(elapsed)
    size_text = f'  {size:,} bytes' if size else ''
    print(f'{name:40s} median {statistics.median(times):7.1f} ms  min {min(times):7.1f} ms{size_text}', flush=True)
    return statistics.median(times)


def main():
    if not os.path.exists(FIXTURE):
        sys.exit(f'{FIXTURE} is missing; run ./run-s3-write.sh --gate first')
    table = pq.read_table(FIXTURE)
    row_group = pq.ParquetFile(FIXTURE).metadata.row_group(0).num_rows
    print(f'{table.num_rows:,} rows, row groups of {row_group:,}; pyarrow {pa.__version__}, duckdb {duckdb.__version__}')

    s3 = pafs.S3FileSystem(access_key='access', secret_key='secret', endpoint_override=ENDPOINT,
                           scheme='http', region='us-east-1')

    def pyarrow_memory(level):
        def run():
            sink = pa.BufferOutputStream()
            pq.write_table(table, sink, compression='zstd', compression_level=level, row_group_size=row_group)
            return sink.getvalue().size
        return run

    def pyarrow_s3(level):
        def run():
            key = 'test-bucket/write/pyarrow.parquet'
            pq.write_table(table, key, filesystem=s3, compression='zstd', compression_level=level,
                           row_group_size=row_group)
            return s3.get_file_info(key).size
        return run

    for level in (None, 3):
        label = 'default level' if level is None else f'level {level}'
        bench(f'pyarrowMemory [{label}]', pyarrow_memory(level), table.num_rows)
        bench(f'pyarrowS3 [{label}]', pyarrow_s3(level), table.num_rows)

    con = duckdb.connect()
    con.execute('INSTALL httpfs; LOAD httpfs;')
    con.execute(f"CREATE SECRET (TYPE s3, KEY_ID 'access', SECRET 'secret', ENDPOINT '{ENDPOINT}', "
                "URL_STYLE 'path', USE_SSL false, REGION 'us-east-1')")
    con.register('fixture', table)
    con.execute('CREATE TABLE src AS SELECT * FROM fixture')
    local = 'target/s3-write-duckdb.parquet'
    for threads in (1, max(1, (os.cpu_count() or 2) - 1)):
        con.execute(f'SET threads={threads}')

        def duckdb_local():
            con.execute(f"COPY src TO '{local}' (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE {row_group})")
            return os.path.getsize(local)

        def duckdb_s3():
            con.execute("COPY src TO 's3://test-bucket/write/duckdb.parquet' "
                        f"(FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE {row_group})")

        bench(f'duckdbLocal [threads={threads}]', duckdb_local, table.num_rows)
        bench(f'duckdbS3 [threads={threads}]', duckdb_s3, table.num_rows)
    os.remove(local)


if __name__ == '__main__':
    main()
