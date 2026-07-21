#!/usr/bin/env python3
"""Generate the fixed-size-list headline throughput chart — two vector widths side by side.

The lead visual for the post: grouped bars of read throughput (M float32 values/s,
higher is better) at two vector lengths (default n=3 "coordinates" and n=768
"embeddings"). For each reader (column, row) the baseline and fast bars are shown at
each width, with a plain flat-column read of the same values as a dashed reference
line. The point the chart makes: the win GROWS with vector width — modest for short
vectors, large for embeddings, and largest for the row reader (which additionally
stops materializing a list object per record).

Reads the ms/op TSV (default
target/bench-throughput-FixedSizeListScanBenchmark.tsv) and the `values` denominator
from bench-meta-FixedSizeListScanBenchmark.tsv, and fills
charts/templates/fixedlist/fixedlist_bars.svg.tmpl.

Usage:
    python charts/make-fixedlist-bars-chart.py [--k1 3 --k2 768] [--results TSV]

Stdlib only; no third-party dependencies.
"""
import argparse
import os
import re
import sys
from pathlib import Path

from chartlib import PLOT, Y0, fmt, fmt_tick, geom, nice_max, nice_step, render, render_pngs

CONTENDER_RE = re.compile(r"^(?P<base>[A-Za-z]+)\[(?P<k>\d+)\]$")

# Bar layout (px). Two reader groups; within each, an n=k1 pair and an n=k2 pair.
BAR_W = 34
# The three gaps form a clean 1:4:16 progression (each 4x the previous) for a legible
# nesting hierarchy: bars within a width-pair < width-pairs within a group < the two groups.
PAIR_GAP = 6        # gap between the baseline and fast bar within one width
WIDTH_GAP = 24      # gap between the two width-pairs within a reader group  (4x PAIR_GAP)
GROUP_GAP = 96      # gap between the column-reader group and the row-reader group (4x WIDTH_GAP)
PLOT_L, PLOT_R = 70, 740  # gridline extent; the two groups are centred within this span

COLORS = {
    ("column", "Baseline"): "#a5d8ff", ("column", "Fast"): "#1971c2",
    ("row", "Baseline"): "#fab005", ("row", "Fast"): "#f08c00",
}


def load(results, pass_):
    """{(base, k): ms} for the given JMH pass."""
    out = {}
    with open(results) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[0] != pass_:
                continue
            m = CONTENDER_RE.match(parts[2])
            if not m:
                continue
            try:
                out[(m["base"], int(m["k"]))] = float(parts[3])
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
    # Small headroom for the top value label. Kept tight (1.03) so a tallest bar just
    # under a round number (e.g. ~945) doesn't tip nice_max past it (1000 -> 1500).
    axis_max = nice_max(max(values) * 1.03)
    step = nice_step(axis_max, target=6)
    scale = PLOT / axis_max
    grid, labels = [], ['<text x="62" y="404">0</text>']
    for i in range(1, int(round(axis_max / step)) + 1):
        val = i * step
        y = Y0 - val * scale
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
    return scale, "\n    ".join(grid), "\n    ".join(labels)


def emit_bars(tput, ks, scale):
    """SVG for the eight bars in two reader groups, plus per-width and per-reader labels."""
    pair_w = 2 * BAR_W + PAIR_GAP
    group_w = 2 * pair_w + WIDTH_GAP
    total = 2 * group_w + GROUP_GAP
    left = PLOT_L + ((PLOT_R - PLOT_L) - total) / 2  # centre both groups in the plot span
    svg = []
    for reader in ("column", "row"):
        for wi, k in enumerate(ks):
            pair_left = left + wi * (pair_w + WIDTH_GAP)
            for bi, kind in enumerate(("Baseline", "Fast")):
                bx = pair_left + bi * (BAR_W + PAIR_GAP)
                t = tput(reader + kind, k)
                g = geom(t, scale)
                color = COLORS[(reader, kind)]
                lbl = color if kind == "Fast" else "#495057"
                svg.append('<rect x="{:.0f}" y="{}" width="{}" height="{}" rx="2" fill="{}"/>'
                           .format(bx, g["y"], BAR_W, g["h"], color))
                svg.append('<text x="{:.0f}" y="{}" font-size="11" font-weight="700" fill="{}" '
                           'text-anchor="middle">{}</text>'.format(bx + BAR_W / 2, g["ly"], lbl, fmt(t)))
            cx = pair_left + pair_w / 2
            speed = tput(reader + "Fast", k) / tput(reader + "Baseline", k)
            svg.append('<text x="{:.0f}" y="418" font-size="11.5" fill="#868e96" '
                       'text-anchor="middle">n={}</text>'.format(cx, k))
            svg.append('<text x="{:.0f}" y="434" font-size="12" font-weight="700" fill="{}" '
                       'text-anchor="middle">{:.1f}×</text>'.format(cx, COLORS[(reader, "Fast")], speed))
        gcx = left + group_w / 2
        svg.append('<text x="{:.0f}" y="458" font-size="13" font-weight="700" fill="#1a1a1a" '
                   'text-anchor="middle">{} reader</text>'.format(gcx, reader.capitalize()))
        left += group_w + GROUP_GAP
    return "\n  ".join(svg)


def build(data, ks, values, machine, java, hardwood, compression=None):
    needed = [("{}{}".format(r, kind), k)
              for r in ("column", "row") for kind in ("Baseline", "Fast") for k in ks]
    needed += [("flatFloor", k) for k in ks]
    missing = [c for c in needed if c not in data]
    if missing:
        sys.exit("make-fixedlist-bars-chart: missing contender(s) {} — run the benchmark "
                 "with k={}.".format(", ".join("{}[{}]".format(b, k) for b, k in missing),
                                     ",".join(str(k) for k in ks)))

    def tput(base, k):  # M float32 values per second
        return values / (data[(base, k)] / 1000.0) / 1e6

    floor = sum(tput("flatFloor", k) for k in ks) / len(ks)
    all_t = [tput(r + kind, k) for r in ("column", "row")
             for kind in ("Baseline", "Fast") for k in ks] + [floor]
    scale, grid, ticklabels = axis(all_t)

    subst = {"gridlines": grid, "ticklabels": ticklabels,
             "bars": emit_bars(tput, ks, scale),
             "floor_y": "{:.1f}".format(Y0 - floor * scale), "floor_v": fmt(floor)}
    subst["title"] = "Reading fixed-size-list vectors: the win grows with width"
    subst["subtitle"] = ("Read throughput (higher is better) at n={} (coordinates) and "
                         "n={} (embeddings) · {:.0f}M values/file".format(ks[0], ks[1], values / 1e6))
    codec = (" · " + compression.upper()) if compression else ""
    subst["subline"] = (machine + " · warm cache" + codec + (" · " + java if java else "")
                        + (" · Hardwood " + hardwood if hardwood else ""))
    return subst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--k1", type=int, default=3, help="short vector length (default 3)")
    ap.add_argument("--k2", type=int, default=768, help="long vector length (default 768)")
    ap.add_argument("--results-dir", default="target")
    ap.add_argument("--results", default=None,
                    help="override TSV (default <results-dir>/bench-throughput-FixedSizeListScanBenchmark.tsv)")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--pass", dest="pass_", default="unpinned", help="JMH pass to plot (default unpinned)")
    ap.add_argument("--machine", default=None, help="override the hardware label (default: meta `machine`)")
    args = ap.parse_args()

    results = args.results or os.path.join(
        args.results_dir, "bench-throughput-FixedSizeListScanBenchmark.tsv")
    if not os.path.exists(results):
        sys.exit("no results at {} — run run-fixedlist.sh first".format(results))
    data = load(results, args.pass_)
    ks = (args.k1, args.k2)
    meta = read_meta(results, ["values", "java", "hardwood", "machine", "compression"])
    if not meta.get("values"):
        sys.exit("bench-meta is missing 'values' — re-run the benchmark (it writes the "
                 "throughput denominator alongside the results TSV).")

    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)
    machine = args.machine or meta.get("machine") or "unknown machine"
    mode = "single core" if args.pass_ == "pinned" else "all cores"
    subst = build(data, ks, int(meta["values"]), machine + " · " + mode,
                  meta.get("java"), meta.get("hardwood"), meta.get("compression"))
    svg = render("fixedlist/fixedlist_bars.svg.tmpl", out / "fixedlist_bars.svg", subst)
    render_pngs([svg])


if __name__ == "__main__":
    main()
