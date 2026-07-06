#!/usr/bin/env python3
"""Generate the flat full-scan charts (columnar + record) from a benchmark run.

Reads the flat throughput TSV (default
target/bench-throughput-FlatScanBenchmark.tsv) and fills the SVG templates in
charts/templates/flat/. Both charts plot throughput (M rows/s, higher is better),
the axis sized to the data. The Arrow Dataset / SpecificRecord contenders are
internal references and are never plotted.

Row count, on-disk size, JVM, and the date window come from the run's
bench-meta-FlatScanBenchmark.tsv sidecar; generation fails early if it is missing.

Usage:
    python charts/make-flat-chart.py [--results-dir DIR]   # default target/ -> target/charts/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
from pathlib import Path

from chartlib import (PLOT, Y0, cell, fmt, fmt_tick, geom, load, nice_step,
                      render, render_pngs, require_meta, size_label)


def flat_axis(values, target=6):
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


def flat_block(prefix, row, scale, pinned=False):
    g = geom(row["rows"], scale)
    return {prefix + "_y": g["y"], prefix + "_h": g["h"], prefix + "_ly": g["ly"],
            prefix + "_v": fmt(row["rows"], pinned)}


def flat_descriptor(meta):
    """Flat subtitle: window · rows · size, from the run's meta."""
    return "{} · {:.1f}M rows · {}".format(
        meta["window"], float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))


def chart1(data, name):
    hw = cell(data, "hardwoodColumnar", "unpinned", name)
    hwp = cell(data, "hardwoodColumnar", "pinned", name)
    pj = cell(data, "parquetJavaColumnar", "unpinned", name)
    scale, grid, labels = flat_axis([hw["rows"], hwp["rows"], pj["rows"]])
    subst = {"gridlines": grid, "ticklabels": labels}
    subst.update(flat_block("c_hw", hw, scale))
    subst.update(flat_block("c_hwp", hwp, scale, True))
    subst.update(flat_block("c_pj", pj, scale))
    subst["c_oob"] = "{:.1f}".format(hw["rows"] / pj["rows"])
    subst["c_efe"] = "{:.1f}".format(hwp["rows"] / pj["rows"])
    return subst


def chart2(data, name):
    hwi = cell(data, "hardwoodRowReaderIndexed", "unpinned", name)
    hwip = cell(data, "hardwoodRowReaderIndexed", "pinned", name)
    avi = cell(data, "avroParquetReaderIndexed", "unpinned", name)
    hwn = cell(data, "hardwoodRowReaderNamed", "unpinned", name)
    hwnp = cell(data, "hardwoodRowReaderNamed", "pinned", name)
    avn = cell(data, "avroParquetReaderNamed", "unpinned", name)
    scale, grid, labels = flat_axis(
        [hwi["rows"], hwip["rows"], avi["rows"], hwn["rows"], hwnp["rows"], avn["rows"]])
    subst = {"gridlines": grid, "ticklabels": labels}
    subst.update(flat_block("r_hwi", hwi, scale))
    subst.update(flat_block("r_hwip", hwip, scale, True))
    subst.update(flat_block("r_avi", avi, scale))
    subst.update(flat_block("r_hwn", hwn, scale))
    subst.update(flat_block("r_hwnp", hwnp, scale, True))
    subst.update(flat_block("r_avn", avn, scale))
    subst["r_idx_oob"] = "{:.1f}".format(hwi["rows"] / avi["rows"])
    subst["r_named_oob"] = "{:.1f}".format(hwn["rows"] / avn["rows"])
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target",
                    help="directory holding bench-throughput/-meta-FlatScanBenchmark.tsv "
                         "(default target; point at a captured run, e.g. "
                         "results/2026-06-25-hardwood-1.0/run-2)")
    ap.add_argument("--results", default=None,
                    help="override flat throughput TSV")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--machine", default="AWS m7i.2xlarge (8 vCPU / 4 physical cores)",
                    help="hardware label for the chart subtitle")
    args = ap.parse_args()

    results = args.results or os.path.join(args.results_dir, "bench-throughput-FlatScanBenchmark.tsv")
    if not os.path.exists(results):
        raise SystemExit("no flat results at {} — run run-flat.sh first".format(results))
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    data = load(results)
    meta = require_meta(results, ["rows", "bytes", "java", "window"])
    ds = flat_descriptor(meta)
    c1 = chart1(data, results); c1["ds"] = ds; c1["java"] = meta["java"]; c1["machine"] = args.machine
    c2 = chart2(data, results); c2["ds"] = ds; c2["java"] = meta["java"]; c2["machine"] = args.machine
    rendered = [
        render("flat/chart1_columnar.svg.tmpl", out / "flat_chart1_columnar.svg", c1),
        render("flat/chart2_record.svg.tmpl", out / "flat_chart2_record.svg", c2),
    ]
    render_pngs(rendered)


if __name__ == "__main__":
    main()
