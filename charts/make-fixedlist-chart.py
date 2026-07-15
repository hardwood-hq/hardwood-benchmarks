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


# Consecutive value labels closer than LABEL_MIN_DX (px) would overlap horizontally
# on the log-k axis (e.g. k=8 vs k=9); alternate them above/below the point. Two
# readers' labels at the same k within LABEL_MIN_DY (px) overlap vertically (e.g.
# k=1, both near 1x); the lower-valued one drops below the line.
LABEL_MIN_DX = 22.0
LABEL_MIN_DY = 15.0


def label_sides(series, xmin, xspan):
    """Per-point label side within one reader's line (+1 above the point, -1 below):
    alternate below when a point sits too close in x to the previous one."""
    sides, prev_x, side = [], None, 1
    for k, _ in series:
        x = xpix(k, xmin, xspan)
        side = -side if prev_x is not None and (x - prev_x) < LABEL_MIN_DX else 1
        sides.append(side)
        prev_x = x
    return sides


def dots(points, values, sides, ks, color, label_ks):
    """Draw a marker at every point, but a value label only where `k` is in
    `label_ks` — the flat plateau carries markers without repeating near-identical
    numbers (e.g. several 2.8x) at slightly different heights."""
    out = []
    for (x, y), v, side, k in zip(points, values, sides, ks):
        out.append('<circle cx="{:.1f}" cy="{:.1f}" r="3.5" fill="{}"/>'.format(x, y, color))
        if k in label_ks:
            dy = -8 if side > 0 else 16
            out.append('<text x="{:.1f}" y="{:.1f}" font-size="10.5" font-weight="700" '
                       'fill="{}" text-anchor="middle">{:.1f}</text>'.format(x, y + dy, color, v))
    return "\n    ".join(out)


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

    xlabels = []
    for k in all_k:
        xlabels.append('<text x="{:.1f}" y="418">{}</text>'.format(xpix(k, xmin, xspan), k))

    subst = {
        "gridlines": "\n    ".join(grid),
        "ticklabels": "\n    ".join(ticklabels),
        "xlabels": "\n    ".join(xlabels),
        "baseline_y": "{:.1f}".format(ypix(1.0, scale)),
    }

    col_sides = label_sides(col, xmin, xspan)
    row_sides = label_sides(row, xmin, xspan)
    # Cross-reader: where both lines carry a point at the same k and their labels
    # would overlap vertically (close speedups — in practice the leftmost k, both
    # near 1x), drop the lower-valued label below the line and keep the higher above.
    col_idx = {k: i for i, (k, _) in enumerate(col)}
    row_idx = {k: i for i, (k, _) in enumerate(row)}
    col_val, row_val = dict(col), dict(row)
    for k in set(col_idx) & set(row_idx):
        if abs(col_val[k] - row_val[k]) * scale < LABEL_MIN_DY:
            col_low = col_val[k] <= row_val[k]
            col_sides[col_idx[k]] = -1 if col_low else 1
            row_sides[row_idx[k]] = 1 if col_low else -1

    # Label only the callout k's (headline embeddings/points + the k=1 edge) plus
    # each reader's peak; the plateau in between reads as one flat line, so labelling
    # every point would just repeat ~2.8x at slightly different heights.
    row_peak_k = max(row, key=lambda kv: kv[1])[0] if row else None
    label_ks = {
        "col": {1, 3, 768},
        "row": {1, 3, 768, row_peak_k},
    }
    for base, series, color, sides in (("col", col, "#1971c2", col_sides),
                                       ("row", row, "#f08c00", row_sides)):
        pts = [(xpix(k, xmin, xspan), ypix(v, scale)) for k, v in series]
        vals = [v for _, v in series]
        ks = [k for k, _ in series]
        subst[base + "_line"] = polyline(pts)
        subst[base + "_dots"] = dots(pts, vals, sides, ks, color, label_ks[base])

    def headline(series, k):
        for kk, v in series:
            if kk == k:
                return "{:.1f}x".format(v)
        return "-"

    hardwood = meta.get("hardwood", "")
    subst["ds"] = "float32 vectors · {} · {}{}".format(
        meta.get("java", "") or "warm cache", machine,
        " · Hardwood " + hardwood if hardwood else "")
    subst["headline"] = "k=3 (points): column {} · k=768 (embeddings): column {}".format(
        headline(col, 3), headline(col, 768))
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
    meta = read_meta(results, ["java", "hardwood", "machine"])
    # Hardware label comes from the meta (recorded by the capture script), --machine
    # overrides it; the all-cores vs single-core mode is derived from the pass.
    machine = args.machine or meta.get("machine") or "unknown machine"
    mode = "single core" if args.pass_ == "pinned" else "all cores"
    subst = build(data, meta, machine + " · " + mode)
    svg = render("fixedlist/fixedlist_speedup.svg.tmpl", out / "fixedlist_speedup.svg", subst)
    render_pngs([svg])


if __name__ == "__main__":
    main()
