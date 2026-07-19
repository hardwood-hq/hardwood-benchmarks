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


# Broken y-axis (same device as the filtered chart): a main region 0..`lower` that
# fills most of the height, then a compressed `lower`..`upper` region up to a ceiling
# tick, so the full-scan parquet-java bars rise to a tick instead of flattening every
# other bar. Y geometry is fixed; only the tick values are data-driven.
Y_LOW, Y_HIGH = 200.0, 104.0       # the `lower` line and the `upper` ceiling line
BREAK_Y = (Y_LOW + Y_HIGH) / 2.0   # break-mark centre, midway between them
BREAK_HALF = 10.0                  # the mark (and each broken bar's gap) is 2*BREAK_HALF tall
BREAK_PAD = 4.0                    # whitespace between the mark and each bar piece
MIN_CAP = 26.0                     # minimum height (px) of a broken bar's top piece
MIN_STUB = 2.0                     # a near-zero bar still shows this much, not nothing
BAR_W = 40                         # bar width, matching the template's group layout
BREAK_RATIO = 2.5                  # a gap this large in the sorted values triggers the break
MAX_OUTLIERS = 3                   # never compress away more than this many bars


def split_point(values):
    """Index of the last outlier in `values` sorted descending, or None for a plain
    axis. Breaks at the first big ratio jump among the top few bars, so the device
    engages only when a handful of bars genuinely dwarf the rest — and disengages by
    itself on a run where they don't."""
    desc = sorted(values, reverse=True)
    for k in range(MAX_OUTLIERS):
        if k + 1 < len(desc) and desc[k + 1] > 0 and desc[k] / desc[k + 1] >= BREAK_RATIO:
            return k
    return None


def broken_axis(values, target=5):
    """Tick geometry for the broken axis. Returns (lower, upper, s_lo, s_hi, grid,
    labels); `s_hi` is None when no break is warranted and the axis is plain."""
    split = split_point(values)
    desc = sorted(values, reverse=True)
    if split is None:                                   # plain linear axis
        step = nice_step(desc[0], target)
        upper = math.ceil(desc[0] / step - 1e-9) * step
        s_lo = PLOT / upper
        grid, labels = [], ['<text x="62" y="404">0</text>']
        for k in range(1, int(round(upper / step)) + 1):
            val = k * step
            y = Y0 - val * s_lo
            grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
            labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
        return upper, upper, s_lo, None, "\n    ".join(grid), "\n    ".join(labels)

    ref = desc[split + 1]                               # tallest bar that stays in-region
    step = nice_step(ref, target)
    lower = math.ceil(ref / step - 1e-9) * step
    upper = math.ceil(desc[0] / step - 1e-9) * step
    if upper <= lower:
        upper = lower + step
    s_lo = (Y0 - Y_LOW) / lower                         # px per ms below the break
    s_hi = (Y_LOW - Y_HIGH) / (upper - lower)           # px per ms above it (compressed)
    grid, labels = [], ['<text x="62" y="404">0</text>']
    for k in range(1, int(round(lower / step)) + 1):
        val = k * step
        y = Y0 - val * s_lo
        grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(y))
        labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
    grid.append('<line x1="70" y1="{0:.1f}" x2="740" y2="{0:.1f}"/>'.format(Y_HIGH))
    labels.append('<text x="62" y="{:.1f}">{}</text>'.format(Y_HIGH + 4, fmt_tick(upper)))
    return lower, upper, s_lo, s_hi, "\n    ".join(grid), "\n    ".join(labels)


def break_mark(cx, color, width=1.0):
    """Zig-zag break mark centred on (cx, BREAK_Y) — drawn on the axis label column
    and mirrored onto each broken bar so the bar's gap reads as the same break."""
    pts = " ".join("{:.1f},{:.1f}".format(cx - 7 + (14 if i % 2 else 0),
                                          (BREAK_Y - BREAK_HALF) + 2 * BREAK_HALF * i / 6.0)
                   for i in range(7))
    return '<polyline points="{}" fill="none" stroke="{}" stroke-width="{}"/>'.format(pts, color, width)


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


# Bar layout: x offset, fill, value-label colour, and whether the 1-core hatch goes
# on top. Mirrors the two group centres (present 237.5, absent 572.5).
BARS = [
    ("p_hwb",   93.5, "#1971c2", "#1971c2", False),
    ("p_hwb1", 141.5, "#1971c2", "#1971c2", True),
    ("p_hwn",  189.5, "#a5d8ff", "#1971c2", False),
    ("p_hwn1", 237.5, "#a5d8ff", "#1971c2", True),
    ("p_pjb",  293.5, "#f08c00", "#e8590c", False),
    ("p_pjn",  341.5, "#ffd8a8", "#e8590c", False),
    ("a_hwb",  428.5, "#1971c2", "#1971c2", False),
    ("a_hwb1", 476.5, "#1971c2", "#1971c2", True),
    ("a_hwn",  524.5, "#a5d8ff", "#1971c2", False),
    ("a_hwn1", 572.5, "#a5d8ff", "#1971c2", True),
    ("a_pjb",  628.5, "#f08c00", "#e8590c", False),
    ("a_pjn",  676.5, "#ffd8a8", "#e8590c", False),
]


def bar_svg(x, fill, label_fill, value, lower, s_lo, s_hi, hatch):
    """One bar. Below the break it is a single rect (floored to MIN_STUB so an
    all-pruned probe still shows). Above it, the bar is drawn in two pieces with a
    zig-zag in the gap, so the compressed top reads as broken rather than as a bar
    that happens to be short."""
    hatch_rect = ('<rect x="{x:.1f}" y="{y:.1f}" width="{w}" height="{h:.1f}" rx="2" fill="url(#onecore)"/>'
                  if hatch else "")
    if s_hi is not None and value > lower:              # broken: bottom piece + cap + mark
        cap_bot = BREAK_Y - BREAK_HALF - BREAK_PAD
        top = min(Y_LOW - (value - lower) * s_hi, cap_bot - MIN_CAP)
        lo_top = BREAK_Y + BREAK_HALF + BREAK_PAD
        pieces = [(lo_top, Y0 - lo_top), (top, cap_bot - top)]
        out = ""
        for y, h in pieces:
            out += '<rect x="{x:.1f}" y="{y:.1f}" width="{w}" height="{h:.1f}" rx="2" fill="{c}"/>'.format(
                x=x, y=y, w=BAR_W, h=h, c=fill)
            if hatch:
                out += hatch_rect.format(x=x, y=y, w=BAR_W, h=h)
        out += break_mark(x + BAR_W / 2.0, fill)
        out += ('<text x="{cx:.1f}" y="{ly:.1f}" font-size="11" font-weight="700" '
                'fill="{lc}" text-anchor="middle">{v}</text>').format(
            cx=x + BAR_W / 2.0, ly=top - 6, lc=label_fill, v=fmt_ms(value))
        return out

    h = value * s_lo
    if value > 0 and h < MIN_STUB:
        h = MIN_STUB
    y = Y0 - h
    out = '<rect x="{x:.1f}" y="{y:.1f}" width="{w}" height="{h:.1f}" rx="2" fill="{c}"/>'.format(
        x=x, y=y, w=BAR_W, h=h, c=fill)
    if hatch:
        out += hatch_rect.format(x=x, y=y, w=BAR_W, h=h)
    out += ('<text x="{cx:.1f}" y="{ly:.1f}" font-size="11" font-weight="700" '
            'fill="{lc}" text-anchor="middle">{v}</text>').format(
        cx=x + BAR_W / 2.0, ly=y - 6, lc=label_fill, v=fmt_ms(value))
    return out


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
    lower, upper, s_lo, s_hi, grid, labels = broken_axis(list(vals.values()))
    subst = {"gridlines": grid, "ticklabels": labels}
    subst["bars"] = "\n  ".join(
        bar_svg(x, fill, lc, vals[pre], lower, s_lo, s_hi, hatch)
        for pre, x, fill, lc, hatch in BARS)
    # The axis break itself, on the tick-label column — omitted on a plain axis.
    subst["axisbreak"] = break_mark(50.0, "#adb5bd") if s_hi is not None else ""
    subst["brknote"] = ("The y-axis is broken above {} ms so the compressed full-scan bars "
                        "do not flatten the rest. ".format(fmt_tick(lower)) if s_hi is not None else "")
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
