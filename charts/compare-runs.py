#!/usr/bin/env python3
"""Difference two benchmark snapshots and report what moved.

Given a BASE and a NEW snapshot — each either a run directory (holding
`bench-throughput-<Benchmark>.tsv` and its `bench-meta` sidecar) or a parent
directory of `run-*` repeats — this prints, per benchmark and pass, every
contender's base time, new time, and the change between them.

A single run is not evidence of a change: run-to-run variance on a shared host is
~5-10%. So a delta is called only when it clears a noise band:

  - Both sides repeats (n >= 2): the band is half of each side's own observed
    spread, (max - min) / median, added together, and at least --min-band
    (default 3%), since a few repeats understate the spread.
  - Either side a single run: the band is --threshold (default 5%), and the
    report says so, because nothing in the snapshots measures the noise.

Times are `ms_per_op`, so a negative delta is faster. Contenders present on only
one side are listed separately rather than silently dropped, as are benchmarks.

Usage:
    # two publication directories, each holding run-1/ run-2/ run-3/
    python charts/compare-runs.py results/2026-06-25-hardwood-1.0 results/2026-09-10-hardwood-1.1

    # two single runs, with an explicit noise threshold
    python charts/compare-runs.py --threshold 8 old-run target

    # machine-readable, and non-zero exit if a Hardwood contender regressed
    python charts/compare-runs.py --format tsv --include hardwood \
        --fail-on-regression base new

Stdlib only; no third-party dependencies.
"""

import argparse
import glob
import os
import re
import statistics
import sys


# --- loading ---------------------------------------------------------------

def snapshot_dirs(path):
    """The run directories a snapshot argument names.

    A directory holding bench-throughput-*.tsv is itself one run; otherwise its
    run-* children are the repeats. Returns (dirs, is_repeats).
    """
    if not os.path.isdir(path):
        sys.exit("compare-runs: not a directory: " + path)
    if glob.glob(os.path.join(path, "bench-throughput-*.tsv")):
        return [path], False
    runs = sorted(d for d in glob.glob(os.path.join(path, "run-*")) if os.path.isdir(d))
    runs = [d for d in runs if glob.glob(os.path.join(d, "bench-throughput-*.tsv"))]
    if not runs:
        sys.exit("compare-runs: no bench-throughput-*.tsv in {} or its run-* subdirectories"
                 .format(path))
    return runs, True


def load(path):
    """Load a snapshot into {benchmark: {(pass, contender): [ms, ...]}} plus meta.

    Meta is taken from the first run; the sidecars are identical across repeats.
    """
    runs, _ = snapshot_dirs(path)
    samples = {}
    order = {}
    meta = {}
    for run in runs:
        for tsv in sorted(glob.glob(os.path.join(run, "bench-throughput-*.tsv"))):
            with open(tsv) as f:
                for line in f:
                    cols = line.rstrip("\n").split("\t")
                    if len(cols) < 4 or cols[0] == "pass":
                        continue
                    pass_, benchmark, contender, ms = cols[0], cols[1], cols[2], cols[3]
                    try:
                        value = float(ms)
                    except ValueError:
                        continue
                    key = (pass_, contender)
                    by_bench = samples.setdefault(benchmark, {})
                    if key not in by_bench:
                        by_bench[key] = []
                        order.setdefault(benchmark, []).append(key)
                    by_bench[key].append(value)

            name = os.path.basename(tsv).replace("bench-throughput-", "").replace(".tsv", "")
            sidecar = os.path.join(run, "bench-meta-{}.tsv".format(name))
            if name not in meta and os.path.exists(sidecar):
                fields = {}
                with open(sidecar) as f:
                    for line in f:
                        cols = line.rstrip("\n").split("\t")
                        if len(cols) >= 2:
                            fields[cols[0]] = cols[1]
                meta[name] = fields
    return {"runs": runs, "samples": samples, "order": order, "meta": meta}


# --- statistics ------------------------------------------------------------

def spread_pct(values):
    """Observed run-to-run spread as a percentage of the median, or None."""
    if len(values) < 2:
        return None
    median = statistics.median(values)
    if median == 0:
        return None
    return (max(values) - min(values)) / median * 100.0


def compare(base_values, new_values, threshold, min_band=0.0):
    """(delta%, band%, verdict, band_measured) for one contender. A measured band is
    never narrower than min_band: a spread over a handful of repeats underestimates
    the noise, and a 0.1% band calls every flicker a change."""
    base_median = statistics.median(base_values)
    new_median = statistics.median(new_values)
    delta = (new_median - base_median) / base_median * 100.0 if base_median else 0.0

    base_spread = spread_pct(base_values)
    new_spread = spread_pct(new_values)
    if base_spread is not None and new_spread is not None:
        band = max(base_spread / 2.0 + new_spread / 2.0, min_band)
        measured = True
    else:
        band = threshold
        measured = False

    if abs(delta) <= band:
        verdict = "~same"
    elif delta < 0:
        verdict = "faster"
    else:
        verdict = "slower"
    return base_median, new_median, delta, band, verdict, measured


# --- reporting -------------------------------------------------------------

def meta_line(label, snap, benchmark):
    fields = snap["meta"].get(benchmark, {})
    parts = [
        fields.get("hardwood", "hardwood version unrecorded"),
        fields.get("java", "?"),
        fields.get("machine", "?"),
        "{} run{}".format(len(snap["runs"]), "" if len(snap["runs"]) == 1 else "s"),
    ]
    return "  {:<5} {}".format(label, "   ".join(parts))


def warnings_for(base, new, benchmark):
    """Comparability problems worth saying out loud before any number is read."""
    out = []
    b = base["meta"].get(benchmark, {})
    n = new["meta"].get(benchmark, {})
    for key, why in (("machine", "different hardware"), ("java", "different JVM")):
        if b.get(key) and n.get(key) and b[key] != n[key]:
            out.append("{}: {} vs {} — {}, the delta is not attributable to Hardwood"
                       .format(key, b[key], n[key], why))
    if b.get("hardwood") and b.get("hardwood") == n.get("hardwood"):
        out.append("both snapshots record hardwood {} — this compares a build with itself"
                   .format(b["hardwood"]))
    for key in ("rows", "bytes", "values", "compression", "window", "preset"):
        if b.get(key) and n.get(key) and b[key] != n[key]:
            out.append("{}: {} vs {} — the two runs read different data"
                       .format(key, b[key], n[key]))
    return out


def report_text(rows, base, new, benchmark, only_base, only_new, out, changes_only=False, drift=()):
    print("\n{}".format(benchmark), file=out)
    print(meta_line("base", base, benchmark), file=out)
    print(meta_line("new", new, benchmark), file=out)

    for warning in warnings_for(base, new, benchmark):
        print("  WARNING  {}".format(warning), file=out)
    for r in drift:
        print("  WARNING  control {} [{}] moved {:+.1f}% (band {:.1f}%): the machine or run "
              "configuration drifted, so this benchmark's verdicts are not attributable to Hardwood"
              .format(r["contender"], r["pass"], r["delta"], r["band"]), file=out)

    if changes_only:
        moved = [r for r in rows if r["verdict"] != "~same"]
        print("  {} compared: {} slower, {} faster, {} within noise".format(
            len(rows), sum(r["verdict"] == "slower" for r in rows),
            sum(r["verdict"] == "faster" for r in rows),
            sum(r["verdict"] == "~same" for r in rows)), file=out)
        rows = moved
        if not rows:
            rows = None

    if rows is None:
        pass
    elif not rows:
        print("  (no contender measured on both sides)", file=out)
    else:
        width = max(len(r["contender"]) for r in rows)
        pass_width = max(len(r["pass"]) for r in rows)
        print("", file=out)
        print("  {:<{pw}}  {:<{w}}  {:>10}  {:>10}  {:>8}  {:>7}  {}".format(
            "pass", "contender", "base ms", "new ms", "delta", "band", "verdict",
            pw=pass_width, w=width), file=out)
        for r in rows:
            print("  {:<{pw}}  {:<{w}}  {:>10.3f}  {:>10.3f}  {:>+7.1f}%  {:>6.1f}%  {}".format(
                r["pass"], r["contender"], r["base_ms"], r["new_ms"], r["delta"],
                r["band"], r["verdict"], pw=pass_width, w=width), file=out)

        if not all(r["measured"] for r in rows):
            print("  band is the --threshold default, not a measured spread: "
                  "at least one side is a single run", file=out)

    for pass_, contender in only_base:
        print("  only in base:  {} [{}]".format(contender, pass_), file=out)
    for pass_, contender in only_new:
        print("  only in new:   {} [{}]".format(contender, pass_), file=out)


def report_tsv(rows, benchmark, out):
    for r in rows:
        print("\t".join([
            benchmark, r["pass"], r["contender"],
            "{:.3f}".format(r["base_ms"]), "{:.3f}".format(r["new_ms"]),
            "{:.2f}".format(r["delta"]), "{:.2f}".format(r["band"]),
            r["verdict"], "measured" if r["measured"] else "threshold",
        ]), file=out)


# --- main ------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Difference two benchmark snapshots and report what moved.")
    parser.add_argument("base", help="baseline snapshot (a run dir, or a dir of run-* repeats)")
    parser.add_argument("new", help="snapshot to compare against the baseline")
    parser.add_argument("--threshold", type=float, default=5.0,
                        help="noise band in percent when a side has no repeats (default 5)")
    parser.add_argument("--include", metavar="REGEX",
                        help="restrict to contenders matching REGEX")
    parser.add_argument("--format", choices=("text", "tsv"), default="text",
                        help="text report (default) or machine-readable TSV")
    parser.add_argument("--fail-on-regression", action="store_true",
                        help="exit 1 if any reported contender is slower")
    parser.add_argument("--min-band", type=float, default=3.0,
                        help="floor in percent for a band measured from repeats (default 3)")
    parser.add_argument("--changes-only", action="store_true",
                        help="list only contenders that moved beyond the noise band, with a tally per benchmark")
    parser.add_argument("--control", metavar="REGEX",
                        help="contenders whose version is pinned (e.g. ^(parquetJava|avro|arrow)): a move beyond "
                             "the band is reported as drift, not as a verdict, and never counts as a regression")
    args = parser.parse_args()

    base = load(args.base)
    new = load(args.new)
    pattern = re.compile(args.include) if args.include else None
    control = re.compile(args.control) if args.control else None

    if args.format == "tsv":
        print("benchmark\tpass\tcontender\tbase_ms\tnew_ms\tdelta_pct\tband_pct\tverdict\tband_source")

    benchmarks = sorted(set(base["samples"]) | set(new["samples"]))
    regressed = 0
    compared = 0

    for benchmark in benchmarks:
        base_bench = base["samples"].get(benchmark, {})
        new_bench = new["samples"].get(benchmark, {})
        if not base_bench or not new_bench:
            side = "new" if base_bench else "base"
            print("\n{}: present only in {} — not compared".format(benchmark, side),
                  file=sys.stderr)
            continue

        rows, only_base, only_new, drift = [], [], [], []
        for key in base["order"].get(benchmark, []):
            pass_, contender = key
            if pattern and not pattern.search(contender):
                continue
            if key not in new_bench:
                only_base.append(key)
                continue
            base_ms, new_ms, delta, band, verdict, measured = compare(
                base_bench[key], new_bench[key], args.threshold, args.min_band)
            row = {"pass": pass_, "contender": contender, "base_ms": base_ms,
                   "new_ms": new_ms, "delta": delta, "band": band,
                   "verdict": verdict, "measured": measured}
            if control and control.search(contender):
                if verdict != "~same":
                    drift.append(row)
                continue
            rows.append(row)
            compared += 1
            if verdict == "slower":
                regressed += 1
        for key in new["order"].get(benchmark, []):
            if pattern and not pattern.search(key[1]):
                continue
            if key not in base_bench:
                only_new.append(key)

        if args.format == "tsv":
            report_tsv(rows, benchmark, sys.stdout)
        else:
            report_text(rows, base, new, benchmark, only_base, only_new, sys.stdout,
                        args.changes_only, drift)

    if args.format == "text":
        print("\n{} contender{} compared, {} slower".format(
            compared, "" if compared == 1 else "s", regressed))

    if args.fail_on_regression and regressed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
