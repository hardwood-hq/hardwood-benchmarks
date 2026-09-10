#!/usr/bin/env python3
"""Generate the nested full-read chart from a benchmark run.

Reads the nested throughput TSV (default
target/bench-throughput-NestedScanBenchmark.tsv) and fills the SVG template in
charts/templates/nested/. The chart plots throughput (M rows/s, higher is
better), the axis sized to the data, with the named (ergonomic) pair leading and
the indexed (positional) pair second — the same six-bar record layout as the flat
scan's chart 2, over the same contender names.

Row count, on-disk size, JVM, Vector API status, the Overture release, and the
schema composition come from the run's bench-meta-NestedScanBenchmark.tsv
sidecar; generation fails early if it is missing. The composition reaches the
footnote because this workload is nested in shape but string-dominated in bytes,
and a reader of the chart should be told so.

Usage:
    python charts/make-nested-chart.py [--results-dir DIR]   # default target/ -> target/charts/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
from pathlib import Path

from chartlib import (PLOT, Y0, cell, fmt_tick, geom, load, nice_step, render,
                      render_pngs, require_meta, size_label)


def fmt_nested(value, pinned=False):
    """Value label. Nested records are heavy, so whole-file throughput lands under
    1 M rows/s and chartlib's shared `fmt` (1 decimal below 100) would print two
    distinct bars with the same label. Two decimals below 10 keeps them apart.
    Pinned (1-core) bars carry the * marker, as elsewhere in the suite."""
    if value >= 100:
        s = "{:.0f}".format(value)
    elif value >= 10:
        s = "{:.1f}".format(value)
    else:
        s = "{:.2f}".format(value)
    return s + ("*" if pinned else "")


def nested_axis(values, target=6):
    """Data-driven y-axis: a clean tick step with `axis_max` at the next step at or
    above the tallest bar, so bars never overrun the top tick. Returns the
    px-per-unit scale and the gridline/label SVG ($gridlines / $ticklabels)."""
    step = nice_step(max(values), target)
    axis_max = math.ceil(max(values) / step - 1e-9) * step
    scale = PLOT / axis_max
    grid, labels = [], ['<text x="62" y="404">0</text>']
    for k in range(1, int(round(axis_max / step)) + 1):
        val = k * step
        y = Y0 - val * scale
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
    return scale, "\n    ".join(grid), "\n    ".join(labels)


def nested_block(prefix, row, scale, pinned=False):
    g = geom(row["rows"], scale)
    return {prefix + "_y": g["y"], prefix + "_h": g["h"], prefix + "_ly": g["ly"],
            prefix + "_v": fmt_nested(row["rows"], pinned)}


def nested_descriptor(meta):
    """Nested subtitle: release · rows · size, from the run's meta."""
    return "{} · {:.1f}M rows · {}".format(
        meta["release"], float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))


def chart(data, name):
    hwn = cell(data, "hardwoodRowReaderNamed", "unpinned", name)
    hwnp = cell(data, "hardwoodRowReaderNamed", "pinned", name)
    avn = cell(data, "avroParquetReaderNamed", "unpinned", name)
    hwi = cell(data, "hardwoodRowReaderIndexed", "unpinned", name)
    hwip = cell(data, "hardwoodRowReaderIndexed", "pinned", name)
    avi = cell(data, "avroParquetReaderIndexed", "unpinned", name)
    scale, grid, labels = nested_axis(
        [hwn["rows"], hwnp["rows"], avn["rows"], hwi["rows"], hwip["rows"], avi["rows"]])
    subst = {"gridlines": grid, "ticklabels": labels}
    subst.update(nested_block("r_hwn", hwn, scale))
    subst.update(nested_block("r_hwnp", hwnp, scale, True))
    subst.update(nested_block("r_avn", avn, scale))
    subst.update(nested_block("r_hwi", hwi, scale))
    subst.update(nested_block("r_hwip", hwip, scale, True))
    subst.update(nested_block("r_avi", avi, scale))
    subst["r_named_oob"] = "{:.1f}".format(hwn["rows"] / avn["rows"])
    subst["r_idx_oob"] = "{:.1f}".format(hwi["rows"] / avi["rows"])
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target",
                    help="directory holding bench-throughput/-meta-NestedScanBenchmark.tsv "
                         "(default target; point at a captured run, e.g. "
                         "results/2026-09-10-nested/run-2)")
    ap.add_argument("--results", default=None,
                    help="override nested throughput TSV")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--machine", default=None,
                    help="override the hardware label (default: the `machine` key from "
                         "the meta sidecar, recorded by the capture script)")
    args = ap.parse_args()

    results = args.results or os.path.join(args.results_dir, "bench-throughput-NestedScanBenchmark.tsv")
    if not os.path.exists(results):
        raise SystemExit("no nested results at {} — run run-nested.sh first".format(results))
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    data = load(results)
    meta = require_meta(results, ["rows", "bytes", "java", "simd", "release", "leaves", "stringBytesPct"])
    subst = chart(data, results)
    subst["ds"] = nested_descriptor(meta)
    subst["java"] = meta["java"]
    subst["simd"] = "SIMD off (scalar)" if meta["simd"] == "scalar" else meta["simd"].replace("simd-", "SIMD ")
    subst["machine"] = args.machine or meta.get("machine") or "unknown machine"
    subst["leaves"] = meta["leaves"]
    subst["stringpct"] = meta["stringBytesPct"]
    render_pngs([render("nested/nested_record.svg.tmpl", out / "nested_record.svg", subst)])


if __name__ == "__main__":
    main()
