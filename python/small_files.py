"""Writes run-s3.sh's many-small-files fixture: COUNT Parquet files of one row group each, their
sizes spread log-uniformly from MIN_KIB to MAX_KIB and shuffled, written by PyArrow with its
defaults (SNAPPY, dictionary encoding, 1 MiB data pages, statistics, the ARROW:schema footer
entry) and the page index on.

    python small_files.py OUT_DIR COUNT MIN_KIB MAX_KIB SEED

OUT_DIR receives small_NNNN.parquet and manifest.tsv (file name, size in bytes, rows), written
into OUT_DIR.tmp and renamed into place once complete. Each file's row count is fitted to its
target size from a first write, so sizes land within a few percent of the target.
"""
import os
import shutil
import sys

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

# taxi-like numeric columns: an increasing key and timestamp, two low-cardinality integers that
# PyArrow dictionary-encodes, and two random doubles that bound how far SNAPPY shrinks a file.
SCHEMA = pa.schema([
    ("id", pa.int64()),
    ("pickup_us", pa.int64()),
    ("passenger_count", pa.int32()),
    ("zone", pa.int32()),
    ("trip_distance", pa.float64()),
    ("fare_amount", pa.float64()),
])


def table(rows, first_id, rng):
    ids = np.arange(first_id, first_id + rows, dtype=np.int64)
    return pa.table({
        "id": ids,
        "pickup_us": 1_735_689_600_000_000 + ids * 1_500_000 + rng.integers(0, 1_000_000, rows),
        "passenger_count": rng.integers(0, 7, rows, dtype=np.int32),
        "zone": rng.integers(1, 266, rows, dtype=np.int32),
        "trip_distance": np.round(rng.exponential(3.0, rows), 2),
        "fare_amount": np.round(rng.uniform(3.0, 120.0, rows), 2),
    }, schema=SCHEMA)


def write(path, rows, first_id, seed):
    data = table(rows, first_id, np.random.default_rng(seed))
    pq.write_table(data, path, row_group_size=rows, write_page_index=True)
    return os.path.getsize(path)


def main():
    out, count, min_kib, max_kib, seed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
    if count < 2 or not 0 < min_kib < max_kib:
        sys.exit(f"small_files.py: need COUNT >= 2 and 0 < MIN_KIB < MAX_KIB, got {count}, {min_kib}, {max_kib}")
    targets = [round(min_kib * 1024 * (max_kib / min_kib) ** (i / (count - 1))) for i in range(count)]
    np.random.default_rng(seed).shuffle(targets)
    tmp = out + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    lines = []
    first_id = 0
    for i, target in enumerate(targets):
        name = f"small_{i:04d}.parquet"
        path = os.path.join(tmp, name)
        file_seed = seed * 100_003 + i
        # A first write at an estimate of 20 bytes a row, then one at the rate it showed.
        rows = max(1, target // 20)
        size = write(path, rows, first_id, file_seed)
        rows = max(1, round(rows * target / size))
        size = write(path, rows, first_id, file_seed)
        if pq.ParquetFile(path).metadata.num_row_groups != 1:
            sys.exit(f"small_files.py: {name} has more than one row group")
        lines.append(f"{name}\t{size}\t{rows}\n")
        first_id += rows
    with open(os.path.join(tmp, "manifest.tsv"), "w") as manifest:
        manifest.writelines(lines)
    shutil.rmtree(out, ignore_errors=True)
    os.rename(tmp, out)


if __name__ == "__main__":
    main()
