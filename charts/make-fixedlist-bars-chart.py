#!/usr/bin/env python3
"""Generate the fixed-size-list absolute-throughput bar chart for one vector length.

The lead visual for the post: grouped bars of read throughput (M float32
values/s, higher is better) at a single `k` (default 768, the embedding hero
size) — column reader baseline vs. fast, row reader baseline vs. fast — with the
flat-column decode floor drawn as a dashed reference line (the fastest these
bytes move; `columnFast` should land near it). The speedup-vs-k curve
(make-fixedlist-chart.py) is the companion that shows the win holds across `k`.

Reads the ms/op TSV (default
target/bench-throughput-FixedSizeListScanBenchmark.tsv) and the `values`
denominator from bench-meta-FixedSizeListScanBenchmark.tsv, and fills
charts/templates/fixedlist/fixedlist_bars.svg.tmpl.

Usage:
    python charts/make-fixedlist-bars-chart.py [--k 768] [--results-dir DIR]

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
import re
import sys
from pathlib import Path

from chartlib import PLOT, Y0, fmt, fmt_tick, geom, nice_max, nice_step, render, render_pngs

CONTENDER_RE = re.compile(r"^(?P<base>[A-Za-z]+)\[(?P<k>\d+)\]$")


def load_k(results, pass_, k):
    """{base: ms} for the given pass and vector length k."""
    out = {}
    with open(results) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[0] != pass_:
                continue
            match = CONTENDER_RE.match(parts[2])
            if not match or int(match["k"]) != k:
                continue
            try:
                out[match["base"]] = float(parts[3])
            except ValueError:
                pass
    return out


def read_meta(results_path, keys):
    meta_path = results_path.replace("bench-throughput-", "bench-meta-")
    meta = {}
    if os.path.exists(meta_path):
        with open(meta_path) as f:
            for line in f:
                if "\t" in line:
                    key, value = line.rstrip("\n").split("\t", 1)
                    meta[key] = value
    return {k: meta.get(k) for k in keys}


def axis(values):
    """Throughput y-axis (higher is better): px-per-unit scale + gridline/label SVG."""
    axis_max = nice_max(max(values) * 1.08)
    step = nice_step(axis_max, target=6)
    scale = PLOT / axis_max
    grid, labels = [], ['<text x="62" y="404">0</text>']
    for i in range(1, int(round(axis_max / step)) + 1):
        val = i * step
        y = Y0 - val * scale
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
    return scale, "\n    ".join(grid), "\n    ".join(labels)


def block(prefix, throughput, scale):
    g = geom(throughput, scale)
    return {prefix + "_y": g["y"], prefix + "_h": g["h"], prefix + "_ly": g["ly"],
            prefix + "_v": fmt(throughput)}


def build(rows, k, values, machine, java):
    needed = ["columnBaseline", "columnFast", "rowBaseline", "rowFast", "flatFloor"]
    missing = [n for n in needed if n not in rows]
    if missing:
        sys.exit("make-fixedlist-bars-chart: k={} is missing contender(s) {} in the "
                 "results — run the benchmark with that k.".format(k, ", ".join(missing)))

    def tput(ms):  # M float32 values per second
        return values / (ms / 1000.0) / 1e6

    cb, cf = tput(rows["columnBaseline"]), tput(rows["columnFast"])
    rb, rf = tput(rows["rowBaseline"]), tput(rows["rowFast"])
    floor = tput(rows["flatFloor"])

    scale, grid, ticklabels = axis([cb, cf, rb, rf, floor])
    subst = {"gridlines": grid, "ticklabels": ticklabels}
    subst.update(block("c_base", cb, scale))
    subst.update(block("c_fast", cf, scale))
    subst.update(block("r_base", rb, scale))
    subst.update(block("r_fast", rf, scale))
    subst["floor_y"] = "{:.1f}".format(Y0 - floor * scale)
    subst["floor_v"] = fmt(floor)
    subst["c_speedup"] = "{:.1f}".format(cf / cb)
    subst["r_speedup"] = "{:.1f}".format(rf / rb)

    subst["k"] = str(k)
    subst["ds"] = "{}-element float32 vectors · {:.0f}M values/file".format(k, values / 1e6)
    subst["subline"] = machine + " · warm cache" + (" · " + java if java else "")
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--k", type=int, default=768, help="vector length to chart (default 768)")
    ap.add_argument("--results-dir", default="target")
    ap.add_argument("--results", default=None,
                    help="override TSV (default <results-dir>/bench-throughput-FixedSizeListScanBenchmark.tsv)")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--pass", dest="pass_", default="unpinned", help="JMH pass to plot (default unpinned)")
    ap.add_argument("--machine", default="AWS m7i.2xlarge (8 vCPU / 4 physical cores)",
                    help="hardware label for the chart subtitle")
    args = ap.parse_args()

    results = args.results or os.path.join(
        args.results_dir, "bench-throughput-FixedSizeListScanBenchmark.tsv")
    if not os.path.exists(results):
        sys.exit("no results at {} — run run-fixedlist.sh first".format(results))
    rows = load_k(results, args.pass_, args.k)
    if not rows:
        sys.exit("no rows for k={} in {} — run the benchmark with that k in the sweep".format(args.k, results))
    meta = read_meta(results, ["values", "java"])
    if not meta.get("values"):
        sys.exit("bench-meta is missing 'values' — re-run the benchmark (it writes the "
                 "throughput denominator alongside the results TSV).")

    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)
    subst = build(rows, args.k, int(meta["values"]), args.machine, meta.get("java"))
    svg = render("fixedlist/fixedlist_bars.svg.tmpl", out / "fixedlist_bars.svg", subst)
    render_pngs([svg])


if __name__ == "__main__":
    main()
