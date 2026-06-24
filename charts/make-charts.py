#!/usr/bin/env python3
"""Generate the announcement SVGs (and PNGs, when a converter is available) from a
benchmark run.

Flat charts (columnar + record) come from the flat throughput TSV
(target/bench-throughput-FlatScanBenchmark.tsv); the filtered chart comes from
the filter ms/op TSV (target/bench-throughput-FilterBenchmark.tsv) when present.
Each fills the SVG templates in charts/templates/ with bar geometry, value
labels, ratios, and the dataset descriptor. Everything else in the templates
(axes, legend, titles, footnotes) is static context — edit it there, not here.

The flat charts plot throughput (higher is better); the filtered chart plots ms/op
(lower is better); both axes are sized to the data. The Arrow Dataset contender is
an internal reference and is never plotted.

Row count, on-disk size, JVM, and the flat date window come from each run's
bench-meta-<Benchmark>.tsv sidecar (written next to the results TSV); generation
fails early if it is missing.

Usage:
    python charts/make-charts.py [--results-dir DIR]   # default: target/ -> target/charts/

Stdlib only; no third-party dependencies.
"""
import argparse
import math
import os
import shutil
import string
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATES = HERE / "templates"

# Plot geometry — baseline at y=400, axis drawn over 300px (y=100..400). Both the
# flat charts and the filtered chart scale the 300px to a data-driven maximum (a
# clean tick at or above the tallest bar), so no bar ever overruns the top tick.
Y0 = 400.0
PLOT = 300.0


def geom(value, scale):
    """Bar rect (y, height) and value-label y for a value at the given px scale."""
    h = round(value * scale)
    y = round(Y0 - h)
    return {"y": y, "h": h, "ly": y - 6}


def fmt(value, pinned=False):
    """Value label: 1 decimal under 100, whole numbers above (filtered match-all
    ms values get large). Pinned (1-core) bars carry the * marker."""
    s = "{:.0f}".format(value) if value >= 100 else "{:.1f}".format(value)
    return s + ("*" if pinned else "")


def fmt_tick(value):
    """Axis tick: drop the decimal when the value is whole."""
    return str(int(round(value))) if abs(value - round(value)) < 1e-9 else "{:.1f}".format(value)


def nice_max(value):
    """Smallest 1/2/2.5/5 x 10^k number >= value, for a clean axis top."""
    if value <= 0:
        return 1.0
    exp = math.floor(math.log10(value))
    base = 10 ** exp
    for m in (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if m * base >= value - 1e-9:
            return m * base
    return 10 * base


def num(s):
    return None if s in ("", "-") else float(s)


def load(results):
    """Parse a results TSV into {contender: {pass: {ms, rows, mb}}}. Tolerates
    tab- or space-delimited rows; keeps any row that carries a per-op time."""
    data = {}
    with open(results) as f:
        f.readline()  # header
        for line in f:
            parts = line.split()
            if len(parts) < 6:
                continue
            pass_, _bench, contender, ms, rows, mb = parts[:6]
            if num(ms) is None:
                continue
            data.setdefault(contender, {})[pass_] = {"ms": num(ms), "rows": num(rows), "mb": num(mb)}
    return data


def cell(data, contender, pass_, results_name):
    try:
        return data[contender][pass_]
    except KeyError:
        sys.exit(
            "missing '{}' [{}] in {} — did the {} pass run? (the 1-core bars need "
            "the taskset-pinned pass; don't use --no-pin for a publish run)".format(
                contender, pass_, results_name,
                "pinned (single-core)" if pass_ == "pinned" else "all-cores"))


# ---- flat (throughput, higher is better, fixed 0..20 axis) ------------------

def flat_axis(values, target=6):
    """Data-driven y-axis for a flat chart: a clean tick step (nice_step) with
    `axis_max` at the next step at or above the tallest bar, so bars never overrun
    the top tick. Returns the px-per-unit scale and the gridline/label SVG for the
    template ($gridlines / $ticklabels)."""
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


def require_meta(results_path, keys):
    """Read the sidecar `bench-meta-*.tsv` the benchmark writes next to its results
    TSV (run row count, on-disk size, JVM, and window). Fails early if the file or
    any required key is missing — chart subtitles must come from the run, never a
    guess."""
    meta_path = results_path.replace("bench-throughput-", "bench-meta-")
    if meta_path == results_path or not os.path.exists(meta_path):
        raise SystemExit(
            "make-charts: missing run-params sidecar '{}' for '{}'. Re-run the "
            "benchmark — it writes bench-meta-<Benchmark>.tsv next to the results "
            "TSV.".format(meta_path, results_path))
    meta = {}
    with open(meta_path) as f:
        for line in f:
            if "\t" in line:
                k, v = line.rstrip("\n").split("\t", 1)
                meta[k] = v
    missing = [k for k in keys if k not in meta]
    if missing:
        raise SystemExit("make-charts: '{}' is missing required key(s): {}".format(
            meta_path, ", ".join(missing)))
    return meta


def _size_label(byte_count):
    mb = byte_count / 1_000_000.0
    return "{:.1f} GB".format(mb / 1000.0) if mb >= 1000 else "{:.0f} MB".format(mb)


def flat_descriptor(meta):
    """Flat subtitle: window · rows · size, from the run's meta."""
    return "{} · {:.1f}M rows · {}".format(
        meta["window"], float(meta["rows"]) / 1e6, _size_label(float(meta["bytes"])))


def filter_descriptor(meta):
    """Filtered subtitle: 'N M-row event file · size', from the run's meta."""
    return "{:.0f}M-row event file · {}".format(
        float(meta["rows"]) / 1e6, _size_label(float(meta["bytes"])))


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


# ---- filtered (ms/op, lower is better, data-sized axis) ---------------------

# Filtered chart broken y-axis: a main region (0..`lower`) on the s_lo scale that
# fills most of the height, then a compressed `lower`..`upper` region up to a ceiling
# tick, so the one giant match-all bar rises to a tick instead of being flat-cut.
# Both bounds land on a clean tick step (nice_step): `lower` is the next tick at or
# above the second-tallest bar (the tallest *in-region* bar), and `upper` is the next
# tick at or above the tallest bar — so the ceiling hugs the outlier rather than
# overshooting it (an overshoot crushes the cap to a sliver). The main-region ticks
# step by that same clean increment (e.g. 250s), however many fit.
#
# Y geometry is fixed (the break mark and ceiling line live at constant y in the
# template); only the tick *values* and the main-region gridlines/labels are
# data-driven, emitted into $gridlines / $ticklabels. Y_LOW = the `lower` line (top
# of the main region), Y_HIGH = the `upper` ceiling; the break mark sits at BREAK_Y,
# midway between them, so it reads as a scale break centred in the compressed zone.
# MIN_CAP floors the cap height so a barely-outlying bar still shows a readable cap
# above the break instead of a sliver.
FY0 = 440.0                        # filtered-chart baseline (value 0)
Y_LOW, Y_HIGH = 220.0, 106.0       # the `lower` line and the `upper` ceiling line
BREAK_Y = (Y_LOW + Y_HIGH) / 2.0   # break-mark centre, midway between lower and upper
BREAK_HALF = 10.0                  # the mark (and the bar's gap) is 2*BREAK_HALF tall
BREAK_PAD = 4.0                    # whitespace between the mark and each bar piece
MIN_CAP = 30.0                     # minimum height (px) of the broken bar's top piece


def nice_step(value, target=4.5):
    """A clean tick step (1/2/2.5/5 x 10^k) giving roughly `target` intervals up to
    `value`, so the main-region ticks land on round numbers (e.g. 250s)."""
    if value <= 0:
        return 1.0
    raw = value / target
    base = 10 ** math.floor(math.log10(raw))
    for m in (1, 2, 2.5, 5):
        if m * base >= raw - 1e-9:
            return m * base
    return 10 * base


def break_mark(x, color):
    """The y-axis break — exact same shape, width (14px), and top-to-bottom
    orientation — mirrored onto the bar at x..x+56, centred on BREAK_Y so the bar's
    gap lines up with the axis break."""
    cx = x + 28.0
    pts = " ".join("{:.1f},{:.1f}".format(cx - 7 + (14 if i % 2 else 0), (BREAK_Y - BREAK_HALF) + 2 * BREAK_HALF * i / 6.0) for i in range(7))
    return '<polyline points="{}" fill="none" stroke="{}" stroke-width="1.0"/>'.format(pts, color)


def chart3(data, name, dataset_label):
    """Filtered: a shared ms axis broken into a 0..<lower> main region and a
    compressed <lower>..<upper> region above a break, so parquet-java's match-all
    rises to a ceiling tick. Selective scans stay tiny next to the full scans —
    that contrast is the push-down win."""
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


def render(template, out_path, subst):
    text = string.Template((TEMPLATES / template).read_text())
    out_path.write_text(text.substitute(subst))
    print("wrote {}".format(out_path))
    return out_path


# SVG→PNG converters, tried in order; first one on PATH wins. All render at 2x the
# SVG's pixel size for a crisp raster. Kept dependency-free: if none is installed
# the SVGs are still written and a one-line notice points at how to get PNGs.
def _png_argv(tool, svg, png):
    return {
        "rsvg-convert": [tool, "-z", "2", "-o", png, svg],
        "resvg": [tool, "--zoom", "2", svg, png],
        "inkscape": [tool, svg, "--export-type=png", "--export-dpi=192", "--export-filename=" + png],
        "cairosvg": [tool, svg, "-o", png, "-s", "2"],
    }[tool]


def render_pngs(svg_paths):
    """Best-effort: rasterize each SVG to a sibling PNG using whatever converter is
    on PATH. No-op (with a notice) when none is installed — PNGs are a convenience,
    not a hard requirement."""
    tools = ["rsvg-convert", "resvg", "inkscape", "cairosvg"]
    tool = next((t for t in tools if shutil.which(t)), None)
    if not tool:
        print("(no SVG→PNG converter on PATH; install one of {} for PNGs)".format(", ".join(tools)))
        return
    for svg in svg_paths:
        png = str(svg.with_suffix(".png"))
        try:
            subprocess.run(_png_argv(tool, str(svg), png), check=True, capture_output=True)
            print("wrote {}".format(png))
        except subprocess.CalledProcessError as e:
            print("  {} failed on {}: {}".format(tool, svg, e.stderr.decode(errors="replace").strip()[:200]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results-dir", default="target",
                    help="directory holding bench-throughput-*.tsv and bench-meta-*.tsv "
                         "(default target; point at a captured run, e.g. results/run-2)")
    ap.add_argument("--results", default=None,
                    help="override flat throughput TSV (default <results-dir>/bench-throughput-FlatScanBenchmark.tsv)")
    ap.add_argument("--filter-results", default=None,
                    help="override filter throughput TSV (default <results-dir>/bench-throughput-FilterBenchmark.tsv)")
    ap.add_argument("--out", default=None, help="output directory (default <results-dir>/charts)")
    ap.add_argument("--machine", default="AWS m7i.2xlarge (8 vCPU / 4 physical cores)",
                    help="hardware label for the chart subtitle (not a JVM property, so set it here)")
    args = ap.parse_args()
    results = args.results or os.path.join(args.results_dir, "bench-throughput-FlatScanBenchmark.tsv")
    filter_results = args.filter_results or os.path.join(args.results_dir, "bench-throughput-FilterBenchmark.tsv")
    out = Path(args.out) if args.out else Path(args.results_dir) / "charts"
    out.mkdir(parents=True, exist_ok=True)

    rendered = []
    if os.path.exists(results):
        data = load(results)
        meta = require_meta(results, ["rows", "bytes", "java", "window"])
        ds = flat_descriptor(meta)
        c1 = chart1(data, results); c1["ds"] = ds; c1["java"] = meta["java"]; c1["machine"] = args.machine
        c2 = chart2(data, results); c2["ds"] = ds; c2["java"] = meta["java"]; c2["machine"] = args.machine
        rendered.append(render("chart1_columnar.svg.tmpl", out / "flat_chart1_columnar.svg", c1))
        rendered.append(render("chart2_record.svg.tmpl", out / "flat_chart2_record.svg", c2))
    else:
        print("(no flat results at {}; skipping flat charts)".format(results))

    if os.path.exists(filter_results):
        fdata = load(filter_results)
        fmeta = require_meta(filter_results, ["rows", "bytes", "java"])
        c3 = chart3(fdata, filter_results, filter_descriptor(fmeta))
        c3["java"] = fmeta["java"]; c3["machine"] = args.machine
        rendered.append(render("chart3_filtered.svg.tmpl", out / "filtered_chart.svg", c3))
    else:
        print("(no filter results at {}; skipping filtered chart)".format(filter_results))

    if rendered:
        render_pngs(rendered)


if __name__ == "__main__":
    main()
