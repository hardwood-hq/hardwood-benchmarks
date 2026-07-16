#!/usr/bin/env python3
"""Generate the bloom-filter point-lookup chart from a benchmark run.

Reads the bloom ms/op TSV (default
target/bench-throughput-BloomFilterBenchmark.tsv) and fills
charts/templates/bloom/chart4_bloom.svg.tmpl. Plots ms/op (lower is better) on a
linear axis, two probe groups (present, absent), each with the four all-cores read
paths (Hardwood/parquet-java × bloom/no-bloom) plus a hatched single-core
(taskset-pinned) bar beside each Hardwood bar. The no-bloom full-scan bars set the
scale; the bloom bars prune to the matching row group(s), and the absent-probe
bloom bars drop every row group (a stub). The 1-core bars need the pinned pass, so
this requires a full run (not --no-pin).

Row count, on-disk size, JVM, and the date window come from the run's
bench-meta-BloomFilterBenchmark.tsv sidecar; generation fails early if it is
missing.

Usage:
    python charts/make-bloom-chart.py [--results-dir DIR]   # default target/ -> target/charts/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
from pathlib import Path

from chartlib import (PLOT, Y0, cell, fmt_tick, load, nice_step, render,
                      render_pngs, require_meta, size_label)


def flat_axis(values, target=6):
    """Data-driven linear y-axis: a clean tick step (nice_step) with `axis_max` at
    the next step at or above the tallest bar, so bars never overrun the top tick.
    Returns the px-per-unit scale and the gridline/label SVG ($gridlines /
    $ticklabels)."""
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


def fmt_ms(value):
    """ms value label: 2 decimals below 1 (the all-pruned absent-bloom bars are
    fractions of a ms), 1 decimal below 100, whole numbers above."""
    if value < 1:
        return "{:.2f}".format(value)
    if value < 100:
        return "{:.1f}".format(value)
    return "{:.0f}".format(value)


def bloom_descriptor(meta):
    """Bloom subtitle: window · rows · size, from the run's meta (the bloom file)."""
    return "{} · {:.1f}M rows · {}".format(
        meta["window"], float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))


def bloom_block(prefix, value, scale, floor=2.0):
    """ms bar geometry, but floors a tiny non-zero bar to `floor` px so an
    all-pruned (near-zero) probe still shows a readable stub instead of vanishing."""
    h = round(value * scale)
    if value > 0 and h < floor:
        h = floor
    y = round(Y0 - h)
    return {prefix + "_y": y, prefix + "_h": h, prefix + "_ly": y - 6,
            prefix + "_v": fmt_ms(value)}


def chart4(data, name, dataset_label):
    """Bloom point lookup: two probe groups (present, absent), each with the four
    all-cores read paths — Hardwood/parquet-java × bloom/no-bloom — plus a hatched
    single-core (taskset-pinned) bar next to each Hardwood bar. The no-bloom twins
    scan the whole column (tall); the bloom bars prune to the matching row group(s),
    and the absent-probe bloom bars drop every row group (a stub). The 1-core bars
    need the pinned pass, so the chart requires a full run (not --no-pin); only
    Hardwood is timed pinned (parquet-java is single-threaded). Lower is better."""
    ac = lambda c: cell(data, c, "unpinned", name)["ms"]
    pin = lambda c: cell(data, c, "pinned", name)["ms"]
    ac_bars = [
        ("p_hwb", "hardwoodBloom[present]"),
        ("p_hwn", "hardwoodNoBloom[present]"),
        ("p_pjb", "parquetJavaBloom[present]"),
        ("p_pjn", "parquetJavaNoBloom[present]"),
        ("a_hwb", "hardwoodBloom[absent]"),
        ("a_hwn", "hardwoodNoBloom[absent]"),
        ("a_pjb", "parquetJavaBloom[absent]"),
        ("a_pjn", "parquetJavaNoBloom[absent]"),
    ]
    pin_bars = [
        ("p_hwb1", "hardwoodBloom[present]"),
        ("p_hwn1", "hardwoodNoBloom[present]"),
        ("a_hwb1", "hardwoodBloom[absent]"),
        ("a_hwn1", "hardwoodNoBloom[absent]"),
    ]
    vals = {pre: ac(c) for pre, c in ac_bars}
    vals.update({pre: pin(c) for pre, c in pin_bars})
    scale, grid, labels = flat_axis(list(vals.values()))
    subst = {"gridlines": grid, "ticklabels": labels}
    for pre, v in vals.items():
        subst.update(bloom_block(pre, v, scale))
    # Bloom speedup = no-bloom ÷ bloom, per engine per probe.
    subst["p_hw_x"] = "{:.0f}".format(vals["p_hwn"] / vals["p_hwb"])
    subst["p_pj_x"] = "{:.0f}".format(vals["p_pjn"] / vals["p_pjb"])
    subst["a_hw_x"] = "{:.0f}".format(vals["a_hwn"] / vals["a_hwb"])
    subst["a_pj_x"] = "{:.0f}".format(vals["a_pjn"] / vals["a_pjb"])
    subst["bds"] = dataset_label
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target",
                    help="directory holding bench-throughput/-meta-BloomFilterBenchmark.tsv "
                         "(default target; point at a captured run, e.g. "
                         "results/2026-06-25-hardwood-1.0/run-2)")
    ap.add_argument("--results", default=None, help="override bloom throughput TSV")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--machine", default="AWS m7i.2xlarge (8 vCPU / 4 physical cores)",
                    help="hardware label for the chart subtitle")
    args = ap.parse_args()

    results = args.results or os.path.join(args.results_dir, "bench-throughput-BloomFilterBenchmark.tsv")
    if not os.path.exists(results):
        raise SystemExit("no bloom results at {} — run run-bloom.sh first".format(results))
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    data = load(results)
    meta = require_meta(results, ["rows", "bytes", "java", "window"])
    c4 = chart4(data, results, bloom_descriptor(meta))
    c4["java"] = meta["java"]; c4["machine"] = args.machine
    rendered = [render("bloom/chart4_bloom.svg.tmpl", out / "bloom_chart.svg", c4)]
    render_pngs(rendered)


if __name__ == "__main__":
    main()
