#!/usr/bin/env python3
"""Chart Hardwood contenders across two or more versions.

Given snapshots in order — each a run directory (holding
`bench-throughput-<Benchmark>.tsv` and its `bench-meta` sidecar) or a directory
of `run-*` repeats, as `compare-runs.py` takes them — this writes one chart per
benchmark and pass, with every Hardwood contender's time in each snapshot side
by side: two snapshots for a before/after, more for a progression such as
1.0 -> 1.1 -> 1.2. It serves every benchmark, regression-only or mixed-use.

  <out>/<Benchmark>_allcores.svg   the all-cores pass
  <out>/<Benchmark>_1core.svg      the taskset-pinned single-core pass

Each snapshot is labelled with the Hardwood build its meta sidecar records
(or --label). A snapshot of repeats is drawn at its median, with its min-max
spread as a whisker. A contender a snapshot lacks, because that version's
benchmark source did not compile or the contender failed on it, is drawn as not
run; so is every contender of a benchmark the snapshot lacks altogether.
The ratio beside each contender is its time in the earliest snapshot that ran it
over its time in the last: above 1 is faster.

Contenders matching --control (default: parquet-java's, Avro's and Arrow's)
are not charted. They are pinned in the pom and do not change with the
Hardwood version, so they serve as a control: when one moves beyond its noise
band between two consecutive snapshots, the machine or the run configuration
drifted, and the chart warns. It also warns, as `compare-runs.py` does, about snapshots taken on
different hardware or JVMs, over different data, or of the same build.

Usage:
    python charts/make-version-chart.py BASE NEW [MORE ...] [--out DIR]
    python charts/make-version-chart.py 1.0 1.1 1.2 --label 1.0 --label 1.1 --label 1.2

Stdlib only; no third-party dependencies.
"""
import argparse
import importlib.util
import os
import re
import statistics
import sys
from pathlib import Path

from chartlib import fmt, fmt_tick, nice_max, nice_step, render, render_pngs

_spec = importlib.util.spec_from_file_location(
    "compare_runs", Path(__file__).resolve().parent / "compare-runs.py")
compare_runs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compare_runs)

PASSES = [("unpinned", "allcores", "all cores"), ("pinned", "1core", "1 core")]
# Keys that describe the machine, the JVM or the build rather than the data read.
NON_DATASET_KEYS = {"hardwood", "java", "machine", "simd"}
# Prefixes of keys that measure what a read fetched (run-s3.sh), which a version is expected to
# change, rather than describe the data read.
MEASURED_KEY_PREFIXES = ("requests.", "bytes.", "indexRequests.", "indexBytes.")


def is_dataset_key(key):
    return key not in NON_DATASET_KEYS and not key.startswith(MEASURED_KEY_PREFIXES)

# Sequential ramp, oldest snapshot lightest.
RAMP_LIGHT = (0x9b, 0xcd, 0xf5)
RAMP_DARK = (0x0b, 0x3d, 0x73)

# Geometry (px).
WIDTH = 980
LABEL_X = 290           # contender labels end here
PLOT_X0, PLOT_X1 = 300, 860
RATIO_X = 948
TOP = 128               # first group starts here
BAR_H, BAR_GAP, GROUP_GAP = 13, 3, 16


def ramp(n):
    if n == 1:
        return ["#{:02x}{:02x}{:02x}".format(*RAMP_DARK)]
    out = []
    for i in range(n):
        t = i / float(n - 1)
        rgb = [round(a + (b - a) * t) for a, b in zip(RAMP_LIGHT, RAMP_DARK)]
        out.append("#{:02x}{:02x}{:02x}".format(*rgb))
    return out


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def snapshot_label(snap, path, benchmark):
    return snap["meta"].get(benchmark, {}).get("hardwood") or os.path.basename(os.path.normpath(path))


def warnings_for(snaps, labels, benchmark, control, threshold):
    """Comparability warnings across the snapshots of one benchmark, as (pass, text);
    pass is None for a warning that holds for every pass."""
    out = []
    metas = [s["meta"][benchmark] for s in snaps if s["meta"].get(benchmark)]
    for key in ("machine", "java", "simd"):
        values = {m[key] for m in metas if m.get(key)}
        if len(values) > 1:
            out.append((None, "{} differs across snapshots ({}); deltas are not attributable to Hardwood"
                        .format(key, " / ".join(sorted(values)))))
    keys = sorted(k for k in {k for m in metas for k in m} if is_dataset_key(k))
    for key in keys:
        values = {m.get(key) for m in metas}
        if len(values) > 1:
            out.append((None, "{} differs across snapshots ({}); they read different data"
                        .format(key, " / ".join(str(v) for v in values))))
    builds = [s["meta"].get(benchmark, {}).get("hardwood") for s in snaps]
    for i in range(1, len(builds)):
        if builds[i] and builds[i] == builds[i - 1]:
            out.append((None, "{} and {} record the same build ({})".format(labels[i - 1], labels[i], builds[i])))
    # Pinned contenders as a control: consecutive snapshots only.
    for i in range(1, len(snaps)):
        before = snaps[i - 1]["samples"].get(benchmark, {})
        after = snaps[i]["samples"].get(benchmark, {})
        for key in snaps[i - 1]["order"].get(benchmark, []):
            pass_, contender = key
            if not control.search(contender) or key not in after:
                continue
            _, _, delta, band, verdict, _ = compare_runs.compare(before[key], after[key], threshold, 3.0)
            if verdict != "~same":
                out.append((pass_, "control {} moved {:+.1f}% (band {:.1f}%) between {} and {}; "
                            "the machine or run configuration drifted"
                            .format(contender, delta, band, labels[i - 1], labels[i])))
    return out


def group(y, contender, cells, colors, scale):
    """SVG for one contender: its label, one bar per snapshot, and the earliest/last ratio."""
    out = ['<text x="{}" y="{:.1f}" font-size="12" fill="#1a1a1a" text-anchor="end">{}</text>'
           .format(LABEL_X, y + (len(cells) * (BAR_H + BAR_GAP)) / 2.0 + 2, esc(contender))]
    for i, (values, color) in enumerate(zip(cells, colors)):
        top = y + i * (BAR_H + BAR_GAP)
        mid = top + BAR_H / 2.0
        if values is None:
            out.append('<text x="{}" y="{:.1f}" font-size="11" font-style="italic" fill="#868e96">'
                       'not run</text>'.format(PLOT_X0 + 4, mid + 4))
            continue
        median = statistics.median(values)
        w = max(median * scale, 1.0)
        out.append('<rect x="{}" y="{:.1f}" width="{:.1f}" height="{}" rx="2" fill="{}"/>'
                   .format(PLOT_X0, top, w, BAR_H, color))
        label_x = PLOT_X0 + w + 5
        if len(values) > 1:
            lo, hi = PLOT_X0 + min(values) * scale, PLOT_X0 + max(values) * scale
            out.append('<g stroke="#343a40" stroke-width="1.2"><line x1="{:.1f}" y1="{:.1f}" x2="{:.1f}" y2="{:.1f}"/>'
                       '<line x1="{:.1f}" y1="{:.1f}" x2="{:.1f}" y2="{:.1f}"/>'
                       '<line x1="{:.1f}" y1="{:.1f}" x2="{:.1f}" y2="{:.1f}"/></g>'
                       .format(lo, mid, hi, mid, lo, mid - 3, lo, mid + 3, hi, mid - 3, hi, mid + 3))
            label_x = max(label_x, hi + 5)
        out.append('<text x="{:.1f}" y="{:.1f}" font-size="11" fill="#495057" stroke="#ffffff" stroke-width="3" '
                   'paint-order="stroke">{}</text>'.format(label_x, mid + 4, fmt(median)))
    ran = [values for values in cells if values is not None]
    if len(ran) > 1 and cells[-1] is not None:
        ratio = statistics.median(ran[0]) / statistics.median(cells[-1])
        out.append('<text x="{}" y="{:.1f}" font-size="12.5" font-weight="700" fill="#1a1a1a" text-anchor="end">'
                   '{:.2f}×</text>'.format(RATIO_X, y + (len(cells) * (BAR_H + BAR_GAP)) / 2.0 + 2, ratio))
    return out


def chart(benchmark, pass_, slug, pass_label, snaps, labels, control, warnings, out_dir):
    contenders = []
    for snap in snaps:
        for p, contender in snap["order"].get(benchmark, []):
            if p == pass_ and not control.search(contender) and contender not in contenders:
                contenders.append(contender)
    if not contenders:
        return None
    rows = [[snap["samples"].get(benchmark, {}).get((pass_, c)) for snap in snaps] for c in contenders]
    top = nice_max(max(max(v) for row in rows for v in row if v) * 1.12)
    scale = (PLOT_X1 - PLOT_X0) / top
    colors = ramp(len(snaps))

    group_h = len(snaps) * (BAR_H + BAR_GAP) - BAR_GAP
    plot_bottom = TOP + len(contenders) * (group_h + GROUP_GAP) - GROUP_GAP
    body = []
    step = nice_step(top)
    k = 0
    while k * step <= top + 1e-9:
        x = PLOT_X0 + k * step * scale
        body.append('<line x1="{:.1f}" y1="{}" x2="{:.1f}" y2="{:.1f}" stroke="#ececec" stroke-width="1"/>'
                    .format(x, TOP - 6, x, plot_bottom + 6))
        body.append('<text x="{:.1f}" y="{:.1f}" font-size="11" fill="#adb5bd" text-anchor="middle">{}</text>'
                    .format(x, plot_bottom + 22, fmt_tick(k * step)))
        k += 1
    body.append('<line x1="{}" y1="{}" x2="{}" y2="{:.1f}" stroke="#bbb" stroke-width="1.5"/>'
                .format(PLOT_X0, TOP - 6, PLOT_X0, plot_bottom + 6))
    body.append('<text x="{:.1f}" y="{:.1f}" font-size="12" fill="#495057" text-anchor="middle">'
                'time per operation (ms · lower is better)</text>'
                .format((PLOT_X0 + PLOT_X1) / 2.0, plot_bottom + 40))
    body.append('<text x="{}" y="{}" font-size="11" fill="#868e96" text-anchor="end">{} ÷ {}</text>'
                .format(RATIO_X, TOP - 12, "earliest", "last"))
    y = TOP
    for contender, cells in zip(contenders, rows):
        body += group(y, contender, cells, colors, scale)
        y += group_h + GROUP_GAP

    legend, x = [], 40
    for label, color in zip(labels, colors):
        legend.append('<rect x="{}" y="92" width="12" height="12" rx="2" fill="{}"/>'.format(x, color))
        legend.append('<text x="{}" y="102" font-size="11.5" fill="#495057">{}</text>'.format(x + 17, esc(label)))
        x += 17 + 7 * len(label) + 28

    foot_y = plot_bottom + 66
    foot = ['<text x="40" y="{:.1f}" font-size="11.5" fill="#868e96">× = earliest ÷ last snapshot '
            'that ran it (above 1 is faster). Bars are medians; whiskers span repeats. Non-Hardwood contenders are a '
            'control and are not charted.</text>'.format(foot_y)]
    for i, warning in enumerate(warnings):
        foot.append('<text x="40" y="{:.1f}" font-size="11.5" fill="#c92a2a">warning: {}</text>'
                    .format(foot_y + 17 * (i + 1), esc(warning)))
    height = int(foot_y + 17 * len(warnings) + 24)

    meta = next((s["meta"][benchmark] for s in reversed(snaps) if s["meta"].get(benchmark)), {})
    dataset = " · ".join("{} {}".format(k, v) for k, v in sorted(meta.items()) if is_dataset_key(k))
    subst = {
        "width": WIDTH, "height": height,
        "title": esc("{} · {}".format(benchmark, pass_label)),
        "subtitle": esc(dataset or "dataset unrecorded"),
        "machine": esc("{} · {}".format(meta.get("machine", "machine unrecorded"),
                                        meta.get("java", "JVM unrecorded"))),
        "legend": "\n  ".join(legend),
        "body": "\n  ".join(body),
        "footer": "\n  ".join(foot),
    }
    return render("version/version_chart.svg.tmpl", out_dir / "{}_{}.svg".format(benchmark, slug), subst)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("snapshots", nargs="+", help="snapshots in order, oldest first")
    ap.add_argument("--label", action="append", default=None,
                    help="label for a snapshot, once per snapshot in order (default: its Hardwood build)")
    ap.add_argument("--benchmark", help="chart only this benchmark (default: every one the snapshots hold)")
    ap.add_argument("--control", default="^(parquetJava|avro|arrow)",
                    help="non-Hardwood contenders (regex, default ^(parquetJava|avro|arrow)): "
                         "not charted, checked for drift instead")
    ap.add_argument("--threshold", type=float, default=5.0,
                    help="noise band in percent for the control check when a side has no repeats (default 5)")
    ap.add_argument("--out", default="target/version-charts", help="output directory (default target/version-charts)")
    args = ap.parse_args()

    if len(args.snapshots) < 2:
        sys.exit("version chart: needs at least two snapshots")
    if args.label and len(args.label) != len(args.snapshots):
        sys.exit("version chart: {} --label for {} snapshots".format(len(args.label), len(args.snapshots)))
    snaps = [compare_runs.load(p) for p in args.snapshots]
    control = re.compile(args.control)

    # A benchmark a snapshot lacks altogether (its source did not compile against
    # that version) is charted with every contender not run for that snapshot.
    present = set()
    for snap in snaps:
        present |= set(snap["samples"])
    benchmarks = [args.benchmark] if args.benchmark else sorted(present)
    if not benchmarks:
        sys.exit("version chart: the snapshots hold no benchmark")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    rendered = []
    for benchmark in benchmarks:
        if benchmark not in present:
            sys.exit("version chart: no snapshot holds {}".format(benchmark))
        labels = args.label or [snapshot_label(s, p, benchmark) for s, p in zip(snaps, args.snapshots)]
        warnings = warnings_for(snaps, labels, benchmark, control, args.threshold)
        for pass_, warning in warnings:
            print("warning: {}{}: {}".format(benchmark, " [{}]".format(pass_) if pass_ else "", warning),
                  file=sys.stderr)
        for pass_, slug, pass_label in PASSES:
            own = [text for p, text in warnings if p in (None, pass_)]
            path = chart(benchmark, pass_, slug, pass_label, snaps, labels, control, own, out)
            if path:
                rendered.append(path)
    if not rendered:
        sys.exit("version chart: every contender matches --control {!r}".format(args.control))
    render_pngs(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
