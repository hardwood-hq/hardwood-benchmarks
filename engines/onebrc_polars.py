"""Polars contender of run-1brc-engines.sh: prints the 1BRC aggregate of the file in argv[1]."""
import sys

import polars as pl

from onebrc_output import format_stations

t = (pl.col("measurement") * 10).round().cast(pl.Int64)
result = (pl.scan_parquet(sys.argv[1])
          .group_by("station")
          .agg(t.min().alias("min"), t.max().alias("max"), t.sum().alias("sum"), pl.len().alias("count"))
          .collect())
print(format_stations(result.iter_rows()))
