#!/usr/bin/env python3
"""Generate the fixed-size-list fast-path speedup-vs-k chart from a benchmark run.

Reads the FixedSizeListScanBenchmark ms/op TSV (default
target/bench-throughput-FixedSizeListScanBenchmark.tsv), whose contender labels
carry the vector length as `columnFast[768]`. For each k it plots the speedup
(baseline ms / fast ms) of the fast path over the reconstruction baseline, one
line per reader (column, row). A dashed line at 1x marks "no speedup".

Fills charts/templates/fixedlist/fixedlist_speedup.svg.tmpl.

Usage:
    python charts/make-fixedlist-chart.py [--results-dir DIR]   # default target/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
import re
import sys
from pathlib import Path

from chartlib import fmt_tick, nice_max, nice_step, render, render_pngs

# Plot geometry: x axis 70..740 (log2 k), y axis 100..400 (speedup, y0 at 400).
X0, X1 = 70.0, 740.0
Y0, PLOT = 400.0, 300.0

CONTENDER_RE = re.compile(r"^(?P<base>[A-Za-z]+)\[(?P<k>\d+)\]$")


def load(results, pass_):
    """Parse the ms/op TSV into {base: {k: ms}} for the requested pass."""
    data = {}
    with open(results) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[0] != pass_:
                continue
            match = CONTENDER_RE.match(parts[2])
            if not match:
                continue
            try:
                ms = float(parts[3])
            except ValueError:
                continue
            data.setdefault(match["base"], {})[int(match["k"])] = ms
    return data


def speedups(data, fast, baseline):
    """[(k, baseline_ms / fast_ms)] over the k present for both, sorted by k."""
    fast_ms, base_ms = data.get(fast, {}), data.get(baseline, {})
    ks = sorted(set(fast_ms) & set(base_ms))
    return [(k, base_ms[k] / fast_ms[k]) for k in ks if fast_ms[k] > 0]


def xpix(k, xmin, xspan):
    return X0 + (math.log2(k) - xmin) / xspan * (X1 - X0)


def ypix(value, scale):
    return Y0 - value * scale


def polyline(points):
    return " ".join("{:.1f},{:.1f}".format(x, y) for x, y in points)


def dots(points, color):
    """Draw a marker at every point (no value labels — the y-axis and the subtitle
    carry the numbers)."""
    return "\n    ".join(
        '<circle cx="{:.1f}" cy="{:.1f}" r="3.5" fill="{}"/>'.format(x, y, color)
        for x, y in points)


def read_meta(results_path, keys):
    """Lenient meta read: the fixed-size-list benchmark writes ms/op only (no meta
    sidecar), so missing keys default to empty rather than failing."""
    meta_path = results_path.replace("bench-throughput-", "bench-meta-")
    meta = {}
    if os.path.exists(meta_path):
        with open(meta_path) as f:
            for line in f:
                if "\t" in line:
                    k, v = line.rstrip("\n").split("\t", 1)
                    meta[k] = v
    return {k: meta.get(k, "") for k in keys}


def build(data, meta, machine):
    col = speedups(data, "columnFast", "columnBaseline")
    row = speedups(data, "rowFast", "rowBaseline")
    if not col and not row:
        sys.exit("make-fixedlist-chart: no column/row speedup points in results — "
                 "did columnFast/columnBaseline (and row) run across the k sweep?")

    all_k = sorted({k for k, _ in col} | {k for k, _ in row})
    xmin, xmax = math.log2(all_k[0]), math.log2(all_k[-1])
    xspan = (xmax - xmin) or 1.0
    top = max([v for _, v in col + row] + [1.0])
    axis_max = nice_max(top * 1.08)
    scale = PLOT / axis_max
    step = nice_step(axis_max, target=6)

    grid, ticklabels = [], ['<text x="62" y="404">0</text>']
    n = int(round(axis_max / step))
    for i in range(1, n + 1):
        val = i * step
        y = Y0 - val * scale
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        ticklabels.append('<text x="62" y="{:.1f}">{}x</text>'.format(y + 4, fmt_tick(val)))

    # X tick labels, thinned so densely-spaced points on the log axis (e.g. the
    # scalar 9/12/15 samples crowding 8 and 16) don't overprint. When two labels
    # fall within MIN_LABEL_GAP px, keep the "rounder" one: endpoints and the
    # headline k's (1, 3, 768, max) first, then powers of two. Every point still
    # gets a dot; only its axis label may be dropped.
    MIN_LABEL_GAP = 16

    def _label_priority(k):
        if k in (1, 3, 768) or k == all_k[-1]:
            return 2
        return 1 if (k & (k - 1)) == 0 else 0

    kept = []  # (x, k, priority), left to right, each >= MIN_LABEL_GAP apart
    for k in all_k:
        x = xpix(k, xmin, xspan)
        prio = _label_priority(k)
        if kept and x - kept[-1][0] < MIN_LABEL_GAP:
            if prio > kept[-1][2]:
                kept[-1] = (x, k, prio)  # rounder neighbour wins the slot
        else:
            kept.append((x, k, prio))
    xlabels = ['<text x="{:.1f}" y="418">{}</text>'.format(x, k) for x, k, _ in kept]

    subst = {
        "gridlines": "\n    ".join(grid),
        "ticklabels": "\n    ".join(ticklabels),
        "xlabels": "\n    ".join(xlabels),
        "baseline_y": "{:.1f}".format(ypix(1.0, scale)),
    }

    # Shade the scalar-fallback band (n in 9..15, rows not byte-aligned): from the
    # geometric midpoint of (8,9) to that of (15,16), so it brackets the scalar
    # samples without touching the byte-aligned 8 and 16 points.
    if all_k[0] <= 8 and all_k[-1] >= 16:
        band_l = xpix(math.sqrt(8 * 9), xmin, xspan)
        band_r = xpix(math.sqrt(15 * 16), xmin, xspan)
    else:
        band_l = band_r = 0.0
    subst["notch_x"] = "{:.1f}".format(band_l)
    subst["notch_w"] = "{:.1f}".format(max(0.0, band_r - band_l))
    subst["notch_lx"] = "{:.1f}".format((band_l + band_r) / 2.0)

    for base, series, color in (("col", col, "#1971c2"), ("row", row, "#f08c00")):
        pts = [(xpix(k, xmin, xspan), ypix(v, scale)) for k, v in series]
        subst[base + "_line"] = polyline(pts)
        subst[base + "_dots"] = dots(pts, color)

    hardwood = meta.get("hardwood", "")
    codec = meta.get("compression")
    # Drop the JDK vendor parenthetical ("Java 25 (Eclipse Adoptium)" -> "Java 25")
    # so the line does not overflow the chart width and clip the trailing commit SHA.
    java = (meta.get("java", "") or "").split(" (")[0]
    values = meta.get("values")
    per_file = " · {:.0f}M values/file".format(int(values) / 1e6) if values else ""
    codec_s = " · " + codec.upper() if codec else ""
    # Line 2 ("what we see"): this chart is a sweep of the fast-path speedup across
    # vector widths — describe that, not any single k's numbers.
    subst["headline"] = ("Fast-path speedup (higher is better) swept over vector length "
                         "n = {}–{}{}{}".format(all_k[0], all_k[-1], per_file, codec_s))
    # Line 3 (context): identical in shape to the bars chart's third line.
    subst["ds"] = (machine + " · warm cache"
                   + (" · " + java if java else "")
                   + (" · Hardwood " + hardwood if hardwood else ""))
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target")
    ap.add_argument("--results", default=None,
                    help="override TSV (default <results-dir>/bench-throughput-FixedSizeListScanBenchmark.tsv)")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--pass", dest="pass_", default="unpinned",
                    help="which JMH pass to plot (default unpinned / all-cores)")
    ap.add_argument("--machine", default=None,
                    help="override the hardware label (default: the `machine` key from "
                         "the meta sidecar, recorded by the capture script)")
    args = ap.parse_args()

    results = args.results or os.path.join(
        args.results_dir, "bench-throughput-FixedSizeListScanBenchmark.tsv")
    if not os.path.exists(results):
        sys.exit("no results at {} — run run-fixedlist.sh first".format(results))
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    data = load(results, args.pass_)
    meta = read_meta(results, ["values", "java", "hardwood", "machine", "compression"])
    # Hardware label comes from the meta (recorded by the capture script), --machine
    # overrides it; the all-cores vs single-core mode is derived from the pass.
    machine = args.machine or meta.get("machine") or "unknown machine"
    mode = "single core" if args.pass_ == "pinned" else "all cores"
    subst = build(data, meta, machine + " · " + mode)
    svg = render("fixedlist/fixedlist_speedup.svg.tmpl", out / "fixedlist_speedup.svg", subst)
    render_pngs([svg])


if __name__ == "__main__":
    main()
