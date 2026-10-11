"""PyArrow contender of run-1brc-engines.sh: prints the 1BRC aggregate of the file in argv[1].

An Acero plan (scan, project, hash aggregate) streams the file, so memory stays bounded at
any row count."""
import sys

import pyarrow as pa
import pyarrow.acero as acero
import pyarrow.compute as pc
import pyarrow.dataset as ds

from onebrc_output import format_stations

tenths = pc.round(pc.multiply(pc.field("measurement"), 10)).cast(pa.int64())
plan = acero.Declaration.from_sequence([
    acero.Declaration("scan", acero.ScanNodeOptions(ds.dataset(sys.argv[1]), columns=["station", "measurement"])),
    acero.Declaration("project", acero.ProjectNodeOptions([pc.field("station"), tenths], ["station", "t"])),
    acero.Declaration("aggregate", acero.AggregateNodeOptions(
        [("t", "hash_min", None, "min"), ("t", "hash_max", None, "max"),
         ("t", "hash_sum", None, "sum"), ("t", "hash_count", None, "count")],
        keys=["station"])),
])
result = plan.to_table(use_threads=True)
print(format_stations(zip(*(result[c].to_pylist() for c in ("station", "min", "max", "sum", "count")))))
