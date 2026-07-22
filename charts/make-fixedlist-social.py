#!/usr/bin/env python3
"""Social-card (1200x630) variants of the fixed-size-list charts, for LinkedIn/X.

Decluttered relative to the in-article charts: bigger fonts, one context line, no
per-machine/JVM line or multi-line footnotes, the Hardwood logo stamped top-right,
and a github footer. Reads the SAME published median snapshots the article charts
use — it does not touch them.

Outputs `social_bars.{svg,png}` and `social_sweep.{svg,png}` (1200x630).

Usage:
    python charts/make-fixedlist-social.py \
        --results-dir results/2026-07-22-fixed-size-list --out target/charts/social

Stdlib only (plus chartlib for SVG->PNG rasterization).
"""
import argparse
import math
import os
import re
from pathlib import Path

from chartlib import render_pngs

W, H = 1200, 630
HERE = Path(__file__).resolve().parent


def load(path):
    d = {}
    for ln in open(path):
        x = ln.rstrip("\n").split("\t")
        if len(x) >= 4 and x[0] == "unpinned":
            d[x[2]] = float(x[3])
    return d


def txt(x, y, s, size, fill="#1a1a1a", weight="400", anchor="start"):
    return ('<text x="{}" y="{}" font-size="{}" font-weight="{}" fill="{}" '
            'text-anchor="{}">{}</text>').format(x, y, size, weight, fill, anchor, s)


def logo(x, y, side, logo_path):
    """Embed the Hardwood mark as a nested <svg>, scaled into a side x side box."""
    src = Path(logo_path).read_text()
    view = re.search(r'viewBox="([^"]+)"', src).group(1)
    inner = src[src.index(">") + 1: src.rindex("</svg>")]
    return ('<svg x="{}" y="{}" width="{}" height="{}" viewBox="{}">{}</svg>'
            .format(x, y, side, side, view, inner))


def frame(logo_path, logo_x=1112, logo_y=24, logo_side=56):
    return ['<svg xmlns="http://www.w3.org/2000/svg" width="{}" height="{}" viewBox="0 0 {} {}" '
            'font-family="-apple-system, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif">'.format(W, H, W, H),
            '<rect width="{}" height="{}" fill="#ffffff"/>'.format(W, H),
            logo(logo_x, logo_y, logo_side, logo_path)]


def bars(results_dir, logo_path):
    d = load(os.path.join(results_dir, "headline/median/bench-throughput-FixedSizeListScanBenchmark.tsv"))
    VALUES = 128e6
    tput = lambda c: VALUES / (d[c] / 1000.0) / 1e6
    floor = tput("flatFloor[768]")
    s = frame(logo_path)
    s.append(txt(60, 60, "Fixed-length list reads in Parquet &#8212; up to 3.7&#215; faster", 35, weight="700"))
    s.append(txt(60, 95, "Hardwood&#8217;s fixed-size-list fast path vs. regular decode &#183; 128M float32 values &#183; ZSTD", 19, "#495057"))
    lg = 128
    s.append('<rect x="60" y="{}" width="14" height="14" rx="2" fill="#a5d8ff"/>'
             '<rect x="76" y="{}" width="14" height="14" rx="2" fill="#1971c2"/>'.format(lg - 11, lg - 11))
    s.append(txt(98, lg, "column reader (baseline &#183; fast)", 17, "#495057"))
    s.append('<rect x="360" y="{}" width="14" height="14" rx="2" fill="#fab005"/>'
             '<rect x="376" y="{}" width="14" height="14" rx="2" fill="#f08c00"/>'.format(lg - 11, lg - 11))
    s.append(txt(398, lg, "row reader (baseline &#183; fast)", 17, "#495057"))
    s.append('<line x1="640" y1="{}" x2="676" y2="{}" stroke="#2f9e44" stroke-width="3" stroke-dasharray="6 4"/>'.format(lg - 5, lg - 5))
    s.append(txt(684, lg, "flat-column read (no list structure)", 17, "#2f9e44"))
    Y0, TOP, AXMAX = 505, 185, 1000.0
    scale = (Y0 - TOP) / AXMAX
    for v in (250, 500, 750, 1000):
        y = Y0 - v * scale
        s.append('<line x1="120" y1="{0:.0f}" x2="1160" y2="{0:.0f}" stroke="#ececec" stroke-width="1"/>'.format(y))
        s.append(txt(112, y + 5, str(v), 14, "#adb5bd", anchor="end"))
    s.append(txt(112, Y0 + 5, "0", 14, "#adb5bd", anchor="end"))
    fy = Y0 - floor * scale
    s.append('<line x1="120" y1="{0:.0f}" x2="1160" y2="{0:.0f}" stroke="#2f9e44" stroke-width="2.5" stroke-dasharray="7 4"/>'.format(fy))
    s.append(txt(124, fy - 8, "flat-column read {:.0f}".format(floor), 15, "#2f9e44", weight="700"))
    COL = {("column", "Baseline"): "#a5d8ff", ("column", "Fast"): "#1971c2",
           ("row", "Baseline"): "#fab005", ("row", "Fast"): "#f08c00"}
    BAR_W, PAIR, WGAP, GGAP = 60, 12, 52, 150
    pair_w = 2 * BAR_W + PAIR
    group_w = 2 * pair_w + WGAP
    total = 2 * group_w + GGAP
    left = 120 + (1040 - total) / 2
    for reader in ("column", "row"):
        gstart = left
        for wi, k in enumerate((3, 768)):
            pl = left + wi * (pair_w + WGAP)
            for bi, kind in enumerate(("Baseline", "Fast")):
                bx = pl + bi * (BAR_W + PAIR)
                t = tput("{}{}[{}]".format(reader, kind, k))
                bh = t * scale
                by = Y0 - bh
                s.append('<rect x="{:.0f}" y="{:.0f}" width="{}" height="{:.0f}" rx="2" fill="{}"/>'.format(bx, by, BAR_W, bh, COL[(reader, kind)]))
                lab = COL[(reader, "Fast")] if kind == "Fast" else "#495057"
                s.append(txt(bx + BAR_W / 2, by - 8, "{:.0f}".format(t), 16, lab, "700", "middle"))
            cx = pl + pair_w / 2
            spd = tput("{}Fast[{}]".format(reader, k)) / tput("{}Baseline[{}]".format(reader, k))
            s.append(txt(cx, Y0 + 20, "n={}".format(k), 15, "#868e96", anchor="middle"))
            s.append(txt(cx, Y0 + 43, "{:.1f}&#215;".format(spd), 19, COL[(reader, "Fast")], "700", "middle"))
        s.append(txt(gstart + group_w / 2, Y0 + 66, "{} reader".format(reader.capitalize()), 17, "#1a1a1a", "700", "middle"))
        left += group_w + GGAP
    s.append(txt(60, 610, "All bars decode identical float32 values &#183; &#215; = fast vs. baseline &#183; github.com/hardwood-hq/hardwood", 14, "#868e96"))
    s.append("</svg>")
    return "\n".join(s)


def sweep(results_dir, logo_path):
    d = load(os.path.join(results_dir, "sweep/median-128M/bench-throughput-FixedSizeListScanBenchmark.tsv"))
    ks = [1, 2, 3, 4, 8, 9, 12, 15, 16, 32, 64, 128, 256, 512, 768, 1536]
    sp = lambda w, k: d["{}Baseline[{}]".format(w, k)] / d["{}Fast[{}]".format(w, k)]
    s = frame(logo_path, 44, 33, 104)
    s.append(txt(180, 60, "Fixed-length list reads in Parquet &#8212; up to 3.9&#215; faster", 31, weight="700"))
    s.append(txt(180, 94, "Hardwood fast path vs. regular decode, by vector length &#183; 128M float32 values &#183; ZSTD", 18, "#495057"))
    lg = 126
    s.append('<rect x="180" y="{}" width="14" height="14" rx="3" fill="#1971c2"/>'.format(lg - 11))
    s.append(txt(202, lg, "column reader", 17, "#495057"))
    s.append('<rect x="340" y="{}" width="14" height="14" rx="3" fill="#f08c00"/>'.format(lg - 11))
    s.append(txt(362, lg, "row reader", 17, "#495057"))
    s.append('<line x1="480" y1="{}" x2="516" y2="{}" stroke="#adb5bd" stroke-width="2" stroke-dasharray="5 4"/>'.format(lg - 5, lg - 5))
    s.append(txt(524, lg, "1&#215; (no speed-up)", 17, "#868e96"))
    X0, X1, Y0, TOP, AXMAX = 120, 1160, 505, 152, 4.3
    scale = (Y0 - TOP) / AXMAX
    xmin, xmax = math.log2(ks[0]), math.log2(ks[-1])
    xp = lambda k: X0 + (math.log2(k) - xmin) / (xmax - xmin) * (X1 - X0)
    yp = lambda v: Y0 - v * scale
    for v in (1, 2, 3, 4):
        y = yp(v)
        s.append('<line x1="{}" y1="{:.0f}" x2="{}" y2="{:.0f}" stroke="#ececec" stroke-width="1"/>'.format(X0, y, X1, y))
        s.append(txt(X0 - 8, y + 5, "{}&#215;".format(v), 15, "#adb5bd", anchor="end"))
    s.append('<line x1="{}" y1="{:.0f}" x2="{}" y2="{:.0f}" stroke="#bbb" stroke-width="1.5"/>'.format(X0, Y0, X1, Y0))
    s.append('<line x1="{}" y1="{:.0f}" x2="{}" y2="{:.0f}" stroke="#adb5bd" stroke-width="1.5" stroke-dasharray="5 4"/>'.format(X0, yp(1), X1, yp(1)))
    for w, color in (("column", "#1971c2"), ("row", "#f08c00")):
        pts = " ".join("{:.1f},{:.1f}".format(xp(k), yp(sp(w, k))) for k in ks)
        s.append('<polyline points="{}" fill="none" stroke="{}" stroke-width="3"/>'.format(pts, color))
        for k in ks:
            s.append('<circle cx="{:.1f}" cy="{:.1f}" r="4" fill="{}"/>'.format(xp(k), yp(sp(w, k)), color))
    for k in (1, 2, 3, 4, 8, 16, 32, 64, 128, 256, 512, 1536):
        s.append(txt(xp(k), Y0 + 24, str(k), 14, "#868e96", anchor="middle"))
    s.append(txt((X0 + X1) / 2, Y0 + 50, "vector length n (elements per row, log scale)", 16, "#495057", anchor="middle"))
    pk = max(ks, key=lambda k: sp("row", k))
    s.append(txt(xp(pk), yp(sp("row", pk)) - 14, "up to {:.1f}&#215;".format(sp("row", pk)), 17, "#f08c00", "700", "middle"))
    s.append(txt(60, 610, "Speed-up = regular-decode time &#247; fast-path time, same file (gate-checked) &#183; github.com/hardwood-hq/hardwood", 14, "#868e96"))
    s.append("</svg>")
    return "\n".join(s)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="results/2026-07-22-fixed-size-list")
    ap.add_argument("--out", default="target/charts/social")
    ap.add_argument("--logo", default=str(HERE / "assets" / "hardwood.svg"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    svgs = []
    for name, fn in (("social_bars", bars), ("social_sweep", sweep)):
        p = out / (name + ".svg")
        p.write_text(fn(args.results_dir, args.logo))
        print("wrote", p)
        svgs.append(p)
    render_pngs(svgs)


if __name__ == "__main__":
    main()
