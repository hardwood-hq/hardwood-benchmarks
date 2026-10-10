#!/usr/bin/env python3
"""Chart a regression run on one page: every Hardwood contender, base against new.

Takes the output directory of `run-regression.sh` (its `progress.log` names the
versions in order; the first is the base, the last the new one), or a BASE and a NEW
snapshot as `compare-runs.py` takes them, and writes

  <out>/regression-overview.svg   (and a PNG beside it)

with two bars per contender both versions ran, grouped by benchmark. Times span
four orders of magnitude across benchmarks, so each contender is drawn relative to
its base median (base = 100%); the labels carry both medians in ms.

Each contender's verdict (faster, slower or within noise) comes from
`compare-runs.py`'s `compare_benchmark`, with the same noise band and defaults, so
the chart and `verdict.txt` agree. A contender only one version ran is listed as
such, without bars. Contenders matching --control are not charted; a control
that moved beyond its band is a warning, as are the comparability problems
`compare-runs.py` reports.

Usage:
    python charts/make-regression-chart.py target/regression
    python charts/make-regression-chart.py BASE NEW --out DIR

Stdlib only; no third-party dependencies.
"""
import argparse
import importlib.util
import os
import re
import statistics
import sys
from pathlib import Path

from chartlib import fmt_tick, nice_max, nice_step, render, render_pngs

_spec = importlib.util.spec_from_file_location(
    "compare_runs", Path(__file__).resolve().parent / "compare-runs.py")
compare_runs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compare_runs)

PASS_LABELS = {"unpinned": "all cores", "pinned": "1 core"}

# Version colours as make-version-chart.py draws two snapshots: base lightest.
BASE_COLOR, NEW_COLOR = "#9bcdf5", "#0b3d73"
SLOWER_COLOR, SLOWER_BACKGROUND = "#c92a2a", "#fff0f0"

# Geometry (px).
WIDTH = 980
LABEL_X = 290           # contender labels end here
PLOT_X0, PLOT_X1 = 300, 830
VERDICT_X = 948
LABEL_ROOM = 100        # a bar's value label needs this much to its right
LABEL_END = 860         # and may run up to here, short of the verdict column
TOP = 132               # first benchmark heading starts here
HEADING_H = 26
BAR_H, BAR_GAP, ROW_GAP, BENCHMARK_GAP = 11, 2, 9, 10
ROW_H = 2 * BAR_H + BAR_GAP


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def fmt_ms(value):
    """Three significant digits, so a 0.075 ms contender does not read as 0.1."""
    for limit, spec in ((100, "{:.0f}"), (10, "{:.1f}"), (1, "{:.2f}")):
        if value >= limit:
            return spec.format(value)
    return "{:.3f}".format(value)


def fmt_pct(value):
    return "{:.0f}%".format(value) if value >= 10 else "{:.1f}%".format(value)


def versions_from_progress(directory):
    """The run's versions in order, from the `round 1/N  VERSION  script ...` lines."""
    progress = os.path.join(directory, "progress.log")
    if not os.path.exists(progress):
        sys.exit("regression chart: no progress.log in {}; name a BASE and a NEW snapshot instead"
                 .format(directory))
    versions = []
    with open(progress) as f:
        for line in f:
            cols = line.split()
            if len(cols) >= 3 and cols[0] == "round" and cols[2] not in versions:
                versions.append(cols[2])
    if len(versions) < 2:
        sys.exit("regression chart: {} names fewer than two versions".format(progress))
    return versions


def snapshot_label(snap, path):
    builds = [m["hardwood"] for _, m in sorted(snap["meta"].items()) if m.get("hardwood")]
    return builds[0] if builds else os.path.basename(os.path.normpath(path))


def common(base, new, key, unrecorded):
    values = sorted({m[key] for s in (base, new) for m in s["meta"].values() if m.get(key)})
    return " / ".join(values) if values else unrecorded


def runs_label(base, new):
    counts = sorted({len(base["runs"]), len(new["runs"])})
    n = " / ".join(str(c) for c in counts)
    return "{} run{} per version".format(n, "" if counts == [1] else "s")


def collect(base, new, control, threshold, min_band):
    """Per benchmark both snapshots hold: its compared rows and one-sided contenders;
    plus every warning, as text."""
    groups, warnings = [], []
    for benchmark in sorted(set(base["samples"]) | set(new["samples"])):
        if not base["samples"].get(benchmark) or not new["samples"].get(benchmark):
            side = "new" if base["samples"].get(benchmark) else "base"
            warnings.append("{}: present only in {}, not compared".format(benchmark, side))
            continue
        rows, only_base, only_new, drift = compare_runs.compare_benchmark(
            base, new, benchmark, threshold, min_band, control=control)
        one_sided = [(key, "base", base) for key in only_base if not control.search(key[1])]
        one_sided += [(key, "new", new) for key in only_new if not control.search(key[1])]
        if rows or one_sided:
            groups.append((benchmark, rows, one_sided))
        for warning in compare_runs.warnings_for(base, new, benchmark):
            warnings.append("{}: {}".format(benchmark, warning))
        for r in drift:
            warnings.append("{}: control {} moved {:+.1f}% (band {:.1f}%); the machine or run "
                            "configuration drifted".format(benchmark, r["contender"], r["delta"], r["band"]))
    return groups, warnings


def contender_label(pass_, contender, passes):
    return contender if len(passes) == 1 else "{} · {}".format(contender, PASS_LABELS.get(pass_, pass_))


def label_backing(x, mid, font_size, chars, fill):
    """A rect behind a value label of about `chars` characters, in the background colour."""
    width = chars * font_size * 0.58 + 4
    return '<rect x="{:.1f}" y="{:.1f}" width="{:.1f}" height="{:.1f}" fill="{}"/>'.format(
        x - 2, mid - font_size / 2.0 - 1, width, font_size + 2, fill)


def compared_row(y, r, label, scale):
    """SVG for one compared contender: label, base and new bar, value labels, verdict."""
    slower = r["verdict"] == "slower"
    pct = r["new_ms"] / r["base_ms"] * 100.0
    mid = y + ROW_H / 2.0
    base_mid, new_top = y + BAR_H / 2.0, y + BAR_H + BAR_GAP
    new_mid = new_top + BAR_H / 2.0
    out = []
    if slower:
        out.append('<rect x="36" y="{:.1f}" width="{}" height="{}" rx="3" fill="{}"/>'
                   .format(y - 4, VERDICT_X + 6 - 36, ROW_H + 8, SLOWER_BACKGROUND))
    out.append('<text x="{}" y="{:.1f}" font-size="12" fill="#1a1a1a" text-anchor="end"{}>{}</text>'
               .format(LABEL_X, mid + 4, ' font-weight="700"' if slower else "", esc(label)))
    base_w = 100.0 * scale
    new_w = max(pct * scale, 1.0)
    out.append('<rect x="{}" y="{:.1f}" width="{:.1f}" height="{}" rx="2" fill="{}"/>'
               .format(PLOT_X0, y, base_w, BAR_H, BASE_COLOR))
    out.append('<rect x="{}" y="{:.1f}" width="{:.1f}" height="{}" rx="2" fill="{}"/>'
               .format(PLOT_X0, new_top, new_w, BAR_H, SLOWER_COLOR if slower else NEW_COLOR))
    # Each value label sits on a backing in the row's background colour, which masks the
    # gridlines and the 100% line where they cross it; the halo alone leaves them showing
    # between glyphs.
    background = SLOWER_BACKGROUND if slower else "#ffffff"
    halo = 'stroke="{}" stroke-width="3" paint-order="stroke"'.format(background)
    base_text = "{} ms".format(fmt_ms(r["base_ms"]))
    new_pct, new_ms = fmt_pct(pct), "{} ms".format(fmt_ms(r["new_ms"]))
    base_x, new_x = PLOT_X0 + base_w + 5, PLOT_X0 + new_w + 5
    out.append(label_backing(base_x, base_mid, 10.5, len(base_text), background))
    out.append(label_backing(new_x, new_mid, 11, len(new_pct) * 1.1 + 3 + len(new_ms), background))
    out.append('<text x="{:.1f}" y="{:.1f}" font-size="10.5" fill="#868e96" {}>{}</text>'
               .format(base_x, base_mid + 3.5, halo, base_text))
    out.append('<text x="{:.1f}" y="{:.1f}" font-size="11" fill="#495057" {}>'
               '<tspan font-weight="700" fill="{}">{}</tspan> · {}</text>'
               .format(new_x, new_mid + 4, halo, SLOWER_COLOR if slower else "#1a1a1a", new_pct, new_ms))
    word, color, weight = {
        "slower": ("slower", SLOWER_COLOR, "700"),
        "faster": ("faster", NEW_COLOR, "700"),
    }.get(r["verdict"], ("within noise", "#868e96", "400"))
    out.append('<text x="{}" y="{:.1f}" font-size="12" font-weight="{}" fill="{}" text-anchor="end">'
               '{} {:+.1f}%</text>'.format(VERDICT_X, base_mid + 4, weight, color, word, r["delta"]))
    out.append('<text x="{}" y="{:.1f}" font-size="10.5" fill="#adb5bd" text-anchor="end">band ±{:.1f}%{}</text>'
               .format(VERDICT_X, new_mid + 4, r["band"], "" if r["measured"] else " (threshold)"))
    return out


def one_sided_row(y, label, side, values):
    mid = y + ROW_H / 2.0
    text = "only in {} ({} ms), not compared".format(side, fmt_ms(statistics.median(values)))
    return [label_backing(PLOT_X0 + 4, mid, 11, len(text), "#ffffff"),
            '<text x="{}" y="{:.1f}" font-size="12" fill="#868e96" text-anchor="end">{}</text>'
            .format(LABEL_X, mid + 4, esc(label)),
            '<text x="{}" y="{:.1f}" font-size="11" font-style="italic" fill="#868e96" '
            'stroke="#ffffff" stroke-width="3" paint-order="stroke">{}</text>'
            .format(PLOT_X0 + 4, mid + 4, text)]


def chart(base, new, base_label, new_label, groups, warnings, min_band, out_dir):
    rows = [r for _, compared, _ in groups for r in compared]
    passes = {r["pass"] for r in rows} | {key[0] for _, _, one_sided in groups for key, _, _ in one_sided}
    # The axis leaves room for the longest bar's value label left of the verdict column.
    longest = max([r["new_ms"] / r["base_ms"] * 100.0 for r in rows] + [100.0])
    top = nice_max(longest * (PLOT_X1 - PLOT_X0) / (LABEL_END - LABEL_ROOM - PLOT_X0))
    scale = (PLOT_X1 - PLOT_X0) / top

    body, y = [], TOP
    for benchmark, compared, one_sided in groups:
        body.append('<text x="40" y="{:.1f}" font-size="13" font-weight="700" fill="#1a1a1a">{}</text>'
                    .format(y + 15, esc(benchmark)))
        y += HEADING_H
        for r in compared:
            body += compared_row(y, r, contender_label(r["pass"], r["contender"], passes), scale)
            y += ROW_H + ROW_GAP
        for key, side, snap in one_sided:
            body += one_sided_row(y, contender_label(key[0], key[1], passes), side,
                                  snap["samples"][benchmark][key])
            y += ROW_H + ROW_GAP
        y += BENCHMARK_GAP
    plot_bottom = y - BENCHMARK_GAP - ROW_GAP

    # Gridlines and ticks go under the bars, so they are drawn first.
    grid = []
    step = nice_step(top)
    k = 0
    while k * step <= top + 1e-9:
        x = PLOT_X0 + k * step * scale
        grid.append('<line x1="{:.1f}" y1="{}" x2="{:.1f}" y2="{:.1f}" stroke="#ececec" stroke-width="1"/>'
                    .format(x, TOP - 6, x, plot_bottom + 6))
        for tick_y in (TOP - 12, plot_bottom + 22):
            grid.append('<text x="{:.1f}" y="{:.1f}" font-size="11" fill="#adb5bd" text-anchor="middle">{}%</text>'
                        .format(x, tick_y, fmt_tick(k * step)))
        k += 1
    x100 = PLOT_X0 + 100.0 * scale
    grid.append('<line x1="{:.1f}" y1="{}" x2="{:.1f}" y2="{:.1f}" stroke="#868e96" stroke-width="1" '
                'stroke-dasharray="4 3"/>'.format(x100, TOP - 6, x100, plot_bottom + 6))
    grid.append('<line x1="{}" y1="{}" x2="{}" y2="{:.1f}" stroke="#bbb" stroke-width="1.5"/>'
                .format(PLOT_X0, TOP - 6, PLOT_X0, plot_bottom + 6))
    grid.append('<text x="{:.1f}" y="{:.1f}" font-size="12" fill="#495057" text-anchor="middle">'
                'time per operation relative to base (base = 100% · lower is better)</text>'
                .format((PLOT_X0 + PLOT_X1) / 2.0, plot_bottom + 40))

    legend, x = [], 40
    for label, color in ((base_label + " · base", BASE_COLOR), (new_label + " · new", NEW_COLOR),
                         ("new, slower beyond the noise band", SLOWER_COLOR)):
        legend.append('<rect x="{}" y="92" width="12" height="12" rx="2" fill="{}"/>'.format(x, color))
        legend.append('<text x="{}" y="102" font-size="11.5" fill="#495057">{}</text>'.format(x + 17, esc(label)))
        x += 17 + 7 * len(label) + 28

    foot_y = plot_bottom + 66
    notes = ["% = new median ÷ base median. The verdict is verdict.txt's: a change beyond the noise band,",
             "half of each version's spread across runs added together, at least {:g}%. Non-Hardwood contenders "
             "are a control and are not charted.".format(min_band)]
    foot = ['<text x="40" y="{:.1f}" font-size="11.5" fill="#868e96">{}</text>'.format(foot_y + 17 * i, esc(n))
            for i, n in enumerate(notes)]
    for i, warning in enumerate(warnings):
        foot.append('<text x="40" y="{:.1f}" font-size="11.5" fill="#c92a2a">warning: {}</text>'
                    .format(foot_y + 17 * (len(notes) + i), esc(warning)))
    height = int(foot_y + 17 * (len(notes) + len(warnings) - 1) + 24)

    tally = "{} compared: {} slower, {} faster, {} within noise".format(
        len(rows), sum(r["verdict"] == "slower" for r in rows),
        sum(r["verdict"] == "faster" for r in rows), sum(r["verdict"] == "~same" for r in rows))
    subst = {
        "width": WIDTH, "height": height,
        "title": esc("Regression check · {}".format(" / ".join(PASS_LABELS.get(p, p) for p in sorted(passes, reverse=True)))),
        "subtitle": esc("{} → {} · {}".format(base_label, new_label, tally)),
        "machine": esc("{} · {} · {}".format(common(base, new, "machine", "machine unrecorded"),
                                             common(base, new, "java", "JVM unrecorded"), runs_label(base, new))),
        "legend": "\n  ".join(legend),
        "body": "\n  ".join(grid + body),
        "footer": "\n  ".join(foot),
    }
    return render("regression/regression_overview.svg.tmpl", out_dir / "regression-overview.svg", subst)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("snapshots", nargs="+", metavar="DIR",
                    help="a run-regression.sh output directory, or a BASE and a NEW snapshot")
    ap.add_argument("--out", help="output directory (default: <run directory>/charts for a run-regression.sh "
                                  "directory, target/version-charts for two snapshots)")
    ap.add_argument("--control", default="^(parquetJava|avro|arrow)",
                    help="non-Hardwood contenders (regex, default ^(parquetJava|avro|arrow)): "
                         "not charted, checked for drift instead")
    ap.add_argument("--threshold", type=float, default=5.0,
                    help="noise band in percent when a side has no repeats (default 5, as compare-runs.py)")
    ap.add_argument("--min-band", type=float, default=3.0,
                    help="floor in percent for a band measured from repeats (default 3, as compare-runs.py)")
    args = ap.parse_args()

    if len(args.snapshots) == 1:
        directory = args.snapshots[0]
        versions = versions_from_progress(directory)
        paths = [os.path.join(directory, versions[0]), os.path.join(directory, versions[-1])]
        out = Path(args.out or os.path.join(directory, "charts"))
    elif len(args.snapshots) == 2:
        paths = args.snapshots
        out = Path(args.out or "target/version-charts")
    else:
        sys.exit("regression chart: name a run-regression.sh directory, or a BASE and a NEW snapshot")

    base, new = (compare_runs.load(p) for p in paths)
    groups, warnings = collect(base, new, re.compile(args.control), args.threshold, args.min_band)
    if not any(compared for _, compared, _ in groups):
        sys.exit("regression chart: no Hardwood contender ran in both {} and {}".format(*paths))
    for warning in warnings:
        print("warning: {}".format(warning), file=sys.stderr)
    out.mkdir(parents=True, exist_ok=True)
    render_pngs([chart(base, new, snapshot_label(base, paths[0]), snapshot_label(new, paths[1]),
                       groups, warnings, args.min_band, out)])
    return 0


if __name__ == "__main__":
    sys.exit(main())
