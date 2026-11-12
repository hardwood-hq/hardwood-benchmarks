#!/usr/bin/env python3
"""Generate the published flat and filtered charts with two Hardwood versions side by side.

Takes two snapshots (a run directory or a median snapshot, each holding
bench-throughput-/bench-meta-FlatScanBenchmark.tsv and -FilterBenchmark.tsv), the
older first, and writes the three published charts with five bars per group:
Hardwood all cores (older, newer), Hardwood 1 core (older, newer), and the
baseline (parquet-java or AvroParquetReader). Both versions share the colours
of the published charts; each Hardwood bar is labelled with its version
underneath, and a key above the legend maps the labels to the versions. The
baseline bar and the hardware/JVM line come from the newer snapshot, and the
ratios read older -> newer.

  <out>/flat_chart1_columnar.svg   columnar reader vs parquet-java column API
  <out>/flat_chart2_record.svg     row reader vs AvroParquetReader, indexed and named
  <out>/filtered_chart.svg         push-down range filter, selective and match-all

Usage:
    python charts/make-two-version-charts.py OLD NEW --old-label 1.0.0.Final \\
        --new-label b7ffc871 --old-short 1.0 --new-short main [--baseline-label 'parquet-java 1.18.2'] [--out DIR]

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
from pathlib import Path

from chartlib import cell, fmt, fmt_tick, load, nice_step, render_pngs, require_meta, size_label

Y0, Y_TOP = 400.0, 100.0           # plot baseline and top (inside the legend offset)
X_L, X_R = 70, 740
BAR, PAIR_GAP, MODE_GAP = 38, 4, 16
GROUP_W = 5 * BAR + 2 * PAIR_GAP + 2 * MODE_GAP
COL = {"all": "#1971c2", "one": "#4dabf7", "base": "#f08c00"}
TXT = {"all": "#1971c2", "one": "#228be6", "base": "#f08c00"}
# Broken axis (filtered chart): main region 0..lower up to Y_LOW, compressed
# lower..upper region up to Y_HIGH, with the break mark centred between them.
Y_LOW, Y_HIGH = 220.0, 106.0
BREAK_Y = (Y_LOW + Y_HIGH) / 2.0
BREAK_HALF, BREAK_PAD, MIN_CAP = 10.0, 4.0, 30.0
FOOT_PIN = "* 1 core = taskset -c 0 (one hardware thread). Values are JMH means, the median across repeats."


class Axis:
    """Value -> y. Linear up to the top tick, or broken above `lower` when one bar
    dwarfs the rest (more than 2.5x the second-tallest)."""

    def __init__(self, values):
        vals = sorted(values)
        self.broken = len(vals) > 1 and vals[-1] > 2.5 * vals[-2]
        if self.broken:
            self.step = nice_step(vals[-2])
            self.lower = math.ceil(vals[-2] / self.step - 1e-9) * self.step
            self.upper = math.ceil(vals[-1] / self.step - 1e-9) * self.step
            if self.upper <= self.lower:
                self.upper = self.lower + self.step
        else:
            self.step = nice_step(vals[-1], 6)
            self.lower = math.ceil(vals[-1] / self.step - 1e-9) * self.step
            self.upper = None

    def y(self, v):
        if not self.broken:
            return Y0 - v / self.lower * (Y0 - Y_TOP)
        if v <= self.lower:
            return Y0 - v / self.lower * (Y0 - Y_LOW)
        return Y_LOW - (v - self.lower) / (self.upper - self.lower) * (Y_LOW - Y_HIGH)

    def grid(self):
        top_y = Y_LOW if self.broken else Y_TOP
        grid, labels = [], ['<text x="62" y="{:.0f}">0</text>'.format(Y0 + 4)]
        for k in range(1, int(round(self.lower / self.step)) + 1):
            val = k * self.step
            y = Y0 - val / self.lower * (Y0 - top_y)
            grid.append('<line x1="{}" y1="{:.1f}" x2="{}" y2="{:.1f}"/>'.format(X_L, y, X_R, y))
            labels.append('<text x="62" y="{:.1f}">{}</text>'.format(y + 4, fmt_tick(val)))
        if self.broken:
            grid.append('<line x1="{}" y1="{:.1f}" x2="{}" y2="{:.1f}"/>'.format(X_L, Y_HIGH, X_R, Y_HIGH))
            labels.append('<text x="62" y="{:.1f}">{}</text>'.format(Y_HIGH + 4, fmt_tick(self.upper)))
            pts = " ".join("{:.1f},{:.1f}".format(43 + (14 if i % 2 else 0),
                                                 BREAK_Y - BREAK_HALF + 2 * BREAK_HALF * i / 6.0) for i in range(7))
            labels.append('<polyline points="{}" fill="none" stroke="#adb5bd" stroke-width="1.0"/>'.format(pts))
        return grid, labels


def zigzag(x, color, bw):
    cx = x + bw / 2.0
    pts = " ".join("{:.1f},{:.1f}".format(cx - 7 + (14 if i % 2 else 0),
                                         BREAK_Y - BREAK_HALF + 2 * BREAK_HALF * i / 6.0) for i in range(7))
    return '<polyline points="{}" fill="none" stroke="{}" stroke-width="1.0"/>'.format(pts, color)


def bar(x, value, kind, pinned, axis, bw):
    """One bar with its value label; a value above a broken axis's `lower` is drawn
    as two pieces around the break mark."""
    op = ""
    label_col = TXT[kind]
    out = []
    if axis.broken and value > axis.lower:
        cap_bot = BREAK_Y - BREAK_HALF - BREAK_PAD
        top = min(axis.y(value), cap_bot - MIN_CAP)
        lo_top = BREAK_Y + BREAK_HALF + BREAK_PAD
        out.append('<rect x="{:.1f}" y="{:.1f}" width="{}" height="{:.1f}" rx="2" fill="{}"{}/>'.format(
            x, lo_top, bw, Y0 - lo_top, COL[kind], op))
        out.append('<rect x="{:.1f}" y="{:.1f}" width="{}" height="{:.1f}" rx="2" fill="{}"{}/>'.format(
            x, top, bw, cap_bot - top, COL[kind], op))
        out.append(zigzag(x, COL[kind], bw))
    else:
        top = axis.y(value)
        out.append('<rect x="{:.1f}" y="{:.1f}" width="{}" height="{:.1f}" rx="2" fill="{}"{}/>'.format(
            x, top, bw, Y0 - top, COL[kind], op))
    out.append('<text x="{:.1f}" y="{:.1f}" font-size="11" font-weight="700" fill="{}" '
               'text-anchor="middle"{}>{}</text>'.format(
                   x + bw / 2.0, top - 5, label_col, "", fmt(value, pinned)))
    return out


def group(cx, bars, axis, labels, base_label, scale=1.0):
    """Five bars centred on cx: all-cores old/new, 1-core old/new, baseline.
    `bars` = (all_old, all_new, one_old, one_new, base) values. `scale` widens bars
    and gaps alike, so a chart with a single group fills the plot width."""
    bw, pair, mode = BAR * scale, PAIR_GAP * scale, MODE_GAP * scale
    x = cx - GROUP_W * scale / 2.0
    xs = [x, x + bw + pair]
    xs += [xs[1] + bw + mode, xs[1] + bw + mode + bw + pair]
    xs.append(xs[3] + bw + mode)
    spec = [("all", False), ("all", False), ("one", True), ("one", True), ("base", False)]
    out = []
    for xi, v, (kind, pinned) in zip(xs, bars, spec):
        out += bar(xi, v, kind, pinned, axis, bw)
    under = [labels[0], labels[1], labels[0], labels[1], base_label]
    for xi, text in zip(xs, under):
        if text:
            out.append('<text x="{:.1f}" y="{:.0f}" font-size="9.5" fill="#868e96" '
                       'text-anchor="middle">{}</text>'.format(xi + bw / 2.0, Y0 + 13, text))
    return out


def ratio(old, new):
    return "{:.1f}× → {:.1f}×".format(old, new)


def svg(title, subtitle, hw_line, legend_base, versions, body, axis, footnotes, height, y_title):
    grid, ticks = axis.grid()
    legend = [
        '<rect x="40" y="82" width="12" height="12" rx="2" fill="{}"/>'.format(COL["all"]),
        '<text x="57" y="92" font-size="11.5" fill="#495057">Hardwood (all cores)</text>',
        '<rect x="190" y="82" width="12" height="12" rx="2" fill="{}"/>'.format(COL["one"]),
        '<text x="207" y="92" font-size="11.5" fill="#495057">Hardwood (1 core)*</text>',
        '<rect x="328" y="82" width="12" height="12" rx="2" fill="{}"/>'.format(COL["base"]),
        '<text x="345" y="92" font-size="11.5" fill="#495057">{}</text>'.format(legend_base),
        '<text x="740" y="67" font-size="11" fill="#868e96" text-anchor="end" xml:space="preserve">{}</text>'.format(versions),
    ]
    foot = ['<text x="40" y="{}" font-size="11.5" fill="{}">{}</text>'.format(y, c, t) for y, c, t in footnotes]
    return "\n".join([
        '<svg xmlns="http://www.w3.org/2000/svg" width="780" height="{0}" viewBox="0 0 780 {0}" '
        'font-family="-apple-system, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif">'.format(height),
        '<rect width="780" height="{}" fill="#ffffff"/>'.format(height),
        '<text x="40" y="30" font-size="20" font-weight="700" fill="#1a1a1a">{}</text>'.format(title),
        '<text x="40" y="50" font-size="12.5" fill="#495057">{}</text>'.format(subtitle),
        '<text x="40" y="67" font-size="11" fill="#868e96">{}</text>'.format(hw_line),
        *legend,
        '<g transform="translate(0, 16)">',
        '<text x="22" y="250" font-size="12.5" fill="#495057" transform="rotate(-90 22 250)" '
        'text-anchor="middle">{}</text>'.format(y_title),
        '<g stroke="#ececec" stroke-width="1">', *grid, '</g>',
        '<g font-size="11" fill="#adb5bd" text-anchor="end">', *ticks, '</g>',
        '<line x1="70" y1="400" x2="740" y2="400" stroke="#bbb" stroke-width="1.5"/>',
        *body, *foot,
        '</g>', '</svg>', ""])


def snapshot(d, bench):
    path = os.path.join(d, "bench-throughput-{}.tsv".format(bench))
    if not os.path.exists(path):
        raise SystemExit("no {} results at {}".format(bench, path))
    return load(path), path


def window_label(window):
    """A month range, collapsed to the single month when both ends are equal."""
    start, sep, end = window.partition("–")
    return start if sep and start == end else window


def hw_line(meta):
    return "{}, warm cache · {}".format(meta.get("machine", "unknown machine"), meta["java"])


def flat_charts(old_dir, new_dir, versions, short, base_label, out):
    old, old_path = snapshot(old_dir, "FlatScanBenchmark")
    new, new_path = snapshot(new_dir, "FlatScanBenchmark")
    meta = require_meta(new_path, ["rows", "bytes", "java", "window"])
    ds = "{} · {:.1f}M rows · {}".format(window_label(meta["window"]), float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))

    def rows(data, path, contender, pass_):
        return cell(data, contender, pass_, path)["rows"]

    def five(hw, base):
        return (rows(old, old_path, hw, "unpinned"), rows(new, new_path, hw, "unpinned"),
                rows(old, old_path, hw, "pinned"), rows(new, new_path, hw, "pinned"),
                rows(new, new_path, base, "unpinned"))

    written = []
    # Columnar
    c = five("hardwoodColumnar", "parquetJavaColumnar")
    axis = Axis(c)
    body = group(405, c, axis, short, base_label, scale=1.8)
    body.append('<text x="405" y="446" font-size="13.5" font-weight="700" fill="#1a1a1a" text-anchor="middle">'
                'out-of-box {}  (Hardwood ÷ parquet-java)  ·  engine-for-engine {}</text>'.format(
                    ratio(c[0] / c[4], c[1] / c[4]), ratio(c[2] / c[4], c[3] / c[4])))
    path = out / "flat_chart1_columnar.svg"
    path.write_text(svg("Flat full scan (columnar reader)",
                        "Hardwood column reader ↔ parquet-java column API · NYC taxi " + ds,
                        hw_line(meta), "parquet-java (single-threaded)", versions, body, axis,
                        [(490, "#228be6", FOOT_PIN)], 516, "throughput (M rows/s · higher is better)"))
    written.append(path)
    # Record: indexed + named
    i = five("hardwoodRowReaderIndexed", "avroParquetReaderIndexed")
    n = five("hardwoodRowReaderNamed", "avroParquetReaderNamed")
    axis = Axis(i + n)
    body = group(237.5, i, axis, short, base_label) + group(572.5, n, axis, short, base_label)
    body += [
        '<line x1="405" y1="92" x2="405" y2="400" stroke="#f0f0f0" stroke-width="1"/>',
        '<text x="237.5" y="430" font-size="13.5" font-weight="700" fill="#1a1a1a" text-anchor="middle">'
        'INDEXED  ·  out-of-box {}</text>'.format(ratio(i[0] / i[4], i[1] / i[4])),
        '<text x="237.5" y="446" font-size="11.5" fill="#868e96" text-anchor="middle">positional — get(10) — the fast path</text>',
        '<text x="572.5" y="430" font-size="13.5" font-weight="700" fill="#1a1a1a" text-anchor="middle">'
        'NAMED  ·  out-of-box {}</text>'.format(ratio(n[0] / n[4], n[1] / n[4])),
        '<text x="572.5" y="446" font-size="11.5" fill="#868e96" text-anchor="middle">'
        'ergonomic — get("fare_amount") — name lookup per field</text>',
    ]
    path = out / "flat_chart2_record.svg"
    path.write_text(svg("Flat full scan (row reader)",
                        "Hardwood row reader ↔ AvroParquetReader · NYC taxi " + ds,
                        hw_line(meta), "AvroParquetReader (single-threaded)", versions, body, axis,
                        [(474, "#868e96", "Indexed (positional) access is the fast path; named (by-name) "
                                          "trades a per-field name lookup for ergonomics."),
                         (490, "#228be6", FOOT_PIN)], 516, "throughput (M rows/s · higher is better)"))
    written.append(path)
    return written


def filter_chart(old_dir, new_dir, versions, short, base_label, out):
    old, old_path = snapshot(old_dir, "FilterBenchmark")
    new, new_path = snapshot(new_dir, "FilterBenchmark")
    meta = require_meta(new_path, ["rows", "bytes", "java"])
    fds = "{:.0f}M-row event file · {}".format(float(meta["rows"]) / 1e6, size_label(float(meta["bytes"])))

    def five(sel):
        hw = "hardwoodDefault[{}]".format(sel)
        ms = lambda data, path, c, p: cell(data, c, p, path)["ms"]
        return (ms(old, old_path, hw, "unpinned"), ms(new, new_path, hw, "unpinned"),
                ms(old, old_path, hw, "pinned"), ms(new, new_path, hw, "pinned"),
                ms(new, new_path, "parquetJava[{}]".format(sel), "unpinned"))

    s, m = five("selective"), five("matchAll")
    axis = Axis(s + m)
    body = group(237.5, s, axis, short, base_label) + group(572.5, m, axis, short, base_label)
    body += [
        '<line x1="405" y1="100" x2="405" y2="400" stroke="#f0f0f0" stroke-width="1"/>',
        '<text x="237.5" y="430" font-size="13.5" font-weight="700" fill="#1a1a1a" text-anchor="middle">'
        'SELECTIVE  ·  out-of-box {}</text>'.format(ratio(s[4] / s[0], s[4] / s[1])),
        '<text x="237.5" y="446" font-size="11.5" fill="#868e96" text-anchor="middle">~5% of rows pass — the push-down win</text>',
        '<text x="572.5" y="430" font-size="13.5" font-weight="700" fill="#1a1a1a" text-anchor="middle">'
        'MATCH-ALL  ·  out-of-box {}</text>'.format(ratio(m[4] / m[0], m[4] / m[1])),
        '<text x="572.5" y="446" font-size="11.5" fill="#868e96" text-anchor="middle">every row passes</text>',
    ]
    foot = [(474, "#868e96", "out-of-box × = parquet-java ÷ Hardwood."),
            (490, "#868e96", "Match-all, {}: row-group statistics prove every row matches, so the filter column is "
                             "not read.".format(short[1])),
            (506, "#228be6", FOOT_PIN)]
    if axis.broken:
        foot[0] = (474, "#868e96", "The y-axis is broken above {} ms (rising to the {} ceiling). "
                                   "out-of-box × = parquet-java ÷ Hardwood.".format(fmt_tick(axis.lower), fmt_tick(axis.upper)))
    path = out / "filtered_chart.svg"
    path.write_text(svg("Filtered scan (predicate push-down)",
                        "Hardwood filtered column reader ↔ parquet-java · push-down range filter · " + fds,
                        hw_line(meta), "parquet-java (single-threaded)", versions, body, axis, foot, 532, "time per scan (ms · lower is better)"))
    return [path]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("old", help="snapshot of the older Hardwood version")
    ap.add_argument("new", help="snapshot of the newer Hardwood version (also supplies the baselines)")
    ap.add_argument("--old-label", required=True, help="legend label of the older version, e.g. 1.0.0.Final")
    ap.add_argument("--new-label", required=True, help="legend label of the newer version, e.g. 'main b7ffc871'")
    ap.add_argument("--old-short", default=None, help="label under the older version's bars (default --old-label)")
    ap.add_argument("--new-short", default=None, help="label under the newer version's bars (default --new-label)")
    ap.add_argument("--baseline-label", default="", help="label under the baseline bars, e.g. 'pq 1.18.2'")
    ap.add_argument("--out", default=None, help="output directory (default <new>/charts/two-version, git-ignored)")
    args = ap.parse_args()
    out = Path(args.out) if args.out else Path(args.new) / "charts" / "two-version"
    out.mkdir(parents=True, exist_ok=True)
    short = (args.old_short or args.old_label, args.new_short or args.new_label)
    versions = "   ·   ".join(lbl if sh == lbl else "{} = {}".format(sh, lbl)
                              for sh, lbl in zip(short, (args.old_label, args.new_label)))
    written = flat_charts(args.old, args.new, versions, short, args.baseline_label, out)
    written += filter_chart(args.old, args.new, versions, short, args.baseline_label, out)
    for p in written:
        print("wrote {}".format(p))
    render_pngs(written)


if __name__ == "__main__":
    main()
