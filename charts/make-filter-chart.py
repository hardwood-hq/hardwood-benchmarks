#!/usr/bin/env python3
"""Generate the filtered-scan chart from a benchmark run.

Reads the filter ms/op TSV (default
target/bench-throughput-FilterBenchmark.tsv) and fills
charts/templates/filter/chart3_filtered.svg.tmpl. Plots ms/op (lower is better)
on a broken y-axis so parquet-java's match-all bar rises to a ceiling tick
instead of being flat-cut; selective scans stay tiny next to the full scans —
that contrast is the push-down win.

Row count, on-disk size, and JVM come from the run's
bench-meta-FilterBenchmark.tsv sidecar; generation fails early if it is missing.

Usage:
    python charts/make-filter-chart.py [--results-dir DIR]   # default target/ -> target/charts/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
from pathlib import Path

from chartlib import (cell, fmt, fmt_tick, load, nice_step, render, render_pngs,
                      require_meta, size_label)

# Broken y-axis: a main region (0..`lower`) that fills most of the height, then a
# compressed `lower`..`upper` region up to a ceiling tick, so the one giant
# match-all bar rises to a tick instead of being flat-cut. Y geometry is fixed (the
# break mark and ceiling line live at constant y in the template); only the tick
# values and the main-region gridlines/labels are data-driven.
FY0 = 440.0                        # filtered-chart baseline (value 0)
Y_LOW, Y_HIGH = 220.0, 106.0       # the `lower` line and the `upper` ceiling line
BREAK_Y = (Y_LOW + Y_HIGH) / 2.0   # break-mark centre, midway between lower and upper
BREAK_HALF = 10.0                  # the mark (and the bar's gap) is 2*BREAK_HALF tall
BREAK_PAD = 4.0                    # whitespace between the mark and each bar piece
MIN_CAP = 30.0                     # minimum height (px) of the broken bar's top piece


def break_mark(x, color):
    """The y-axis break, mirrored onto the bar at x..x+56, centred on BREAK_Y so the
    bar's gap lines up with the axis break."""
    cx = x + 28.0
    pts = " ".join("{:.1f},{:.1f}".format(cx - 7 + (14 if i % 2 else 0), (BREAK_Y - BREAK_HALF) + 2 * BREAK_HALF * i / 6.0) for i in range(7))
    return '<polyline points="{}" fill="none" stroke="{}" stroke-width="1.0"/>'.format(pts, color)


def chart3(data, name, dataset_label):
    """A shared ms axis broken into a 0..<lower> main region and a compressed
    <lower>..<upper> region above a break, so parquet-java's match-all rises to a
    ceiling tick."""
    g = lambda c, p: cell(data, c, p, name)["ms"]
    bars = [
        ("s_hw", g("hardwoodDefault[selective]", "unpinned"), False),
        ("s_hwp", g("hardwoodDefault[selective]", "pinned"), True),
        ("s_pj", g("parquetJava[selective]", "unpinned"), False),
        ("m_hw", g("hardwoodDefault[matchAll]", "unpinned"), False),
        ("m_hwp", g("hardwoodDefault[matchAll]", "pinned"), True),
        ("m_pj", g("parquetJava[matchAll]", "unpinned"), False),
    ]
    vals = sorted(v for _, v, _ in bars)
    step = nice_step(vals[-2])
    # `lower` (main-region top) is the next clean tick at/above the second-tallest
    # bar so that bar stays in-region; `upper` (ceiling) is the next clean tick
    # at/above the tallest — hugging it, not overshooting (which would crush the cap).
    lower = math.ceil(vals[-2] / step - 1e-9) * step
    upper = math.ceil(vals[-1] / step - 1e-9) * step
    if upper <= lower:
        upper = lower + step
    s_lo = (FY0 - Y_LOW) / lower
    s_hi = (Y_LOW - Y_HIGH) / (upper - lower)
    bar_x = {"s_hw": 129.5, "s_hwp": 199.5, "s_pj": 289.5, "m_hw": 464.5, "m_hwp": 534.5, "m_pj": 624.5}
    col = {"hw": "#1971c2", "hwp": "#4dabf7", "pj": "#f08c00"}
    subst = {"outlier": ""}
    for pre, v, pin in bars:
        x, c = bar_x[pre], col[pre.split("_")[1]]
        if v > lower:  # outlier: lower piece + cap above the centred break + break mark + value
            cap_bot = BREAK_Y - BREAK_HALF - BREAK_PAD  # top piece resumes above the mark
            top = Y_LOW - (v - lower) * s_hi
            top = min(top, cap_bot - MIN_CAP)  # floor the cap height so it never collapses to a sliver
            lo_top = BREAK_Y + BREAK_HALF + BREAK_PAD  # bottom piece stops below the mark
            subst["outlier"] = (
                '<rect x="{x:.1f}" y="{yl:.1f}" width="56" height="{hl:.1f}" rx="2" fill="{c}"/>'
                '<rect x="{x:.1f}" y="{yt:.1f}" width="56" height="{hu:.1f}" rx="2" fill="{c}"/>'
                '<text x="{xc:.1f}" y="{ly:.1f}" font-size="12.5" font-weight="700" fill="{c}" text-anchor="middle">{v}</text>'
            ).format(x=x, yl=lo_top, hl=FY0 - lo_top, yt=top, hu=cap_bot - top, xc=x + 28, ly=top - 8, c=c, v=fmt(v, pin)) + break_mark(x, c)
        else:
            h = round(v * s_lo)
            y = int(round(FY0 - h))
            subst[pre + "_y"], subst[pre + "_h"], subst[pre + "_ly"], subst[pre + "_v"] = y, h, y - 6, fmt(v, pin)
    subst["s_oob"] = "{:.1f}".format(bars[2][1] / bars[0][1])  # parquet-java / Hardwood, selective
    subst["m_oob"] = "{:.1f}".format(bars[5][1] / bars[3][1])  # parquet-java / Hardwood, match-all
    # Main-region gridlines + labels, one per clean step up to `lower`, plus the
    # lifted ceiling at Y_HIGH. The 0 baseline is the dark axis line in the template.
    grid, labels = [], ['<text x="62" y="444">0</text>']
    for k in range(1, int(round(lower / step)) + 1):
        val = k * step
        y = FY0 - (val / lower) * (FY0 - Y_LOW)
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
    grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(Y_HIGH))
    labels.append('<text x="62" y="{:.1f}">{}</text>'.format(Y_HIGH + 4, fmt_tick(upper)))
    subst["gridlines"] = "\n    ".join(grid)
    subst["ticklabels"] = "\n    ".join(labels)
    subst["brk"] = fmt_tick(lower)
    subst["tcap"] = fmt_tick(upper)
    subst["fds"] = dataset_label
    return subst


def filter_descriptor(meta):
    """Filtered subtitle: 'N M-row event file · size', from the run's meta."""
    return "{:.0f}M-row event file · {}".format(
        float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target",
                    help="directory holding bench-throughput/-meta-FilterBenchmark.tsv "
                         "(default target; point at a captured run, e.g. "
                         "results/2026-06-25-hardwood-1.0/run-2)")
    ap.add_argument("--results", default=None, help="override filter throughput TSV")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--machine", default="AWS m7i.2xlarge (8 vCPU / 4 physical cores)",
                    help="hardware label for the chart subtitle")
    args = ap.parse_args()

    results = args.results or os.path.join(args.results_dir, "bench-throughput-FilterBenchmark.tsv")
    if not os.path.exists(results):
        raise SystemExit("no filter results at {} — run run-filter.sh first".format(results))
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    data = load(results)
    meta = require_meta(results, ["rows", "bytes", "java"])
    c3 = chart3(data, results, filter_descriptor(meta))
    c3["java"] = meta["java"]; c3["machine"] = args.machine
    rendered = [render("filter/chart3_filtered.svg.tmpl", out / "filtered_chart.svg", c3)]
    render_pngs(rendered)


if __name__ == "__main__":
    main()
