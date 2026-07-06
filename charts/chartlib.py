#!/usr/bin/env python3
"""Shared chart primitives for the per-benchmark chart generators
(make-flat-chart.py, make-filter-chart.py, make-fixedlist-chart.py).

Holds the geometry helpers, TSV loading, run-meta reading, and SVG/PNG rendering
that every chart needs; the chart-specific axis and bar layout lives in each
generator. Stdlib only; no third-party dependencies.
"""
import math
import os
import shutil
import string
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATES = HERE / "templates"

# Plot geometry — baseline at y=400, axis drawn over 300px (y=100..400).
Y0 = 400.0
PLOT = 300.0


def geom(value, scale):
    """Bar rect (y, height) and value-label y for a value at the given px scale."""
    h = round(value * scale)
    y = round(Y0 - h)
    return {"y": y, "h": h, "ly": y - 6}


def fmt(value, pinned=False):
    """Value label: 1 decimal under 100, whole numbers above. Pinned (1-core) bars
    carry the * marker."""
    s = "{:.0f}".format(value) if value >= 100 else "{:.1f}".format(value)
    return s + ("*" if pinned else "")


def fmt_tick(value):
    """Axis tick: drop the decimal when the value is whole."""
    return str(int(round(value))) if abs(value - round(value)) < 1e-9 else "{:.1f}".format(value)


def nice_max(value):
    """Smallest 1/1.5/2/2.5/... x 10^k number >= value, for a clean axis top."""
    if value <= 0:
        return 1.0
    exp = math.floor(math.log10(value))
    base = 10 ** exp
    for m in (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if m * base >= value - 1e-9:
            return m * base
    return 10 * base


def nice_step(value, target=4.5):
    """A clean tick step (1/2/2.5/5 x 10^k) giving roughly `target` intervals up to
    `value`, so ticks land on round numbers (e.g. 250s)."""
    if value <= 0:
        return 1.0
    raw = value / target
    base = 10 ** math.floor(math.log10(raw))
    for m in (1, 2, 2.5, 5):
        if m * base >= raw - 1e-9:
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


def require_meta(results_path, keys):
    """Read the sidecar `bench-meta-*.tsv` the benchmark writes next to its results
    TSV (run row count, on-disk size, JVM, and window). Fails early if the file or
    any required key is missing — chart subtitles must come from the run, never a
    guess."""
    meta_path = results_path.replace("bench-throughput-", "bench-meta-")
    if meta_path == results_path or not os.path.exists(meta_path):
        raise SystemExit(
            "chart: missing run-params sidecar '{}' for '{}'. Re-run the "
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
        raise SystemExit("chart: '{}' is missing required key(s): {}".format(
            meta_path, ", ".join(missing)))
    return meta


def size_label(byte_count):
    mb = byte_count / 1_000_000.0
    return "{:.1f} GB".format(mb / 1000.0) if mb >= 1000 else "{:.0f} MB".format(mb)


def render(template, out_path, subst):
    """Fill a template under templates/ (path relative to it, e.g.
    `flat/chart1_columnar.svg.tmpl`) and write the SVG."""
    text = string.Template((TEMPLATES / template).read_text())
    out_path.write_text(text.substitute(subst))
    print("wrote {}".format(out_path))
    return out_path


# SVG->PNG converters, tried in order; first one on PATH wins. All render at 2x the
# SVG's pixel size. Kept dependency-free: if none is installed the SVGs are still
# written and a one-line notice points at how to get PNGs.
def _png_argv(tool, svg, png):
    return {
        "rsvg-convert": [tool, "-z", "2", "-o", png, svg],
        "resvg": [tool, "--zoom", "2", svg, png],
        "inkscape": [tool, svg, "--export-type=png", "--export-dpi=192", "--export-filename=" + png],
        "cairosvg": [tool, svg, "-o", png, "-s", "2"],
    }[tool]


def render_pngs(svg_paths):
    """Best-effort: rasterize each SVG to a sibling PNG using whatever converter is
    on PATH. No-op (with a notice) when none is installed."""
    tools = ["rsvg-convert", "resvg", "inkscape", "cairosvg"]
    tool = next((t for t in tools if shutil.which(t)), None)
    if not tool:
        print("(no SVG->PNG converter on PATH; install one of {} for PNGs)".format(", ".join(tools)))
        return
    for svg in svg_paths:
        png = str(svg.with_suffix(".png"))
        try:
            subprocess.run(_png_argv(tool, str(svg), png), check=True, capture_output=True)
            print("wrote {}".format(png))
        except subprocess.CalledProcessError as e:
            print("  {} failed on {}: {}".format(tool, svg, e.stderr.decode(errors="replace").strip()[:200]))
