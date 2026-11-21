#!/usr/bin/env python3
"""Compare a regression run with the latest nightly results of the same machine.

Usage: against-history.py OUT VERSION --results DIR [--metric M] [--band PCT]

OUT is run-regression.sh's output directory, VERSION the version it ran, and DIR a checkout of
hardwood-benchmark-results. Run from the benchmarks checkout that produced OUT.

Per contender the run measured, it finds the series recording would add the run to (the same
contender, epoch and machine epoch) and prints the series' latest settled value, the commit it
measured, this run's value and the delta, as a Markdown table. A delta beyond --band (default
5 %) in either direction is called worse or better; within it, within the band. The latest
nightly value is a single row, so the band is fixed rather than taken from either side's
spread: a call is a hint to look closer, not a verdict. A contender that did not settle, or
whose forks disagree, in this run gets that instead of a call. One without a series of the
same epoch on this machine says why: its definition differs from the nightly one, or the
machine has no history.

--metric picks the metric: wall (default), cpu, alloc or live.
"""
import argparse
import csv
import glob
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("record_results", os.path.join(HERE, "record-results.py"))
record_results = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(record_results)

METRICS = {"wall": ("wall_ms", "ms"), "cpu": ("cpu_ms", "ms"), "alloc": ("alloc_bytes", "B"),
           "live": ("live_bytes", "B")}


def number(value, unit):
    if value is None:
        return "–"
    if unit == "B":
        for scale, suffix in ((1 << 30, "GiB"), (1 << 20, "MiB"), (1 << 10, "KiB")):
            if abs(value) >= scale:
                return "{:.1f} {}".format(value / scale, suffix)
        return "{:.0f} B".format(value)
    if value < 1:
        return "{:.3f} ms".format(value)
    if value < 100:
        return "{:.2f} ms".format(value)
    return "{:,.0f} ms".format(value)


def latest(path, column):
    """The series' last settled row that has `column`, or None."""
    if not os.path.exists(path):
        return None
    with open(path, newline="") as f:
        rows = [r for r in csv.DictReader(f) if r.get(column)]
    return rows[-1] if rows else None


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out")
    parser.add_argument("version")
    parser.add_argument("--results", required=True)
    parser.add_argument("--metric", choices=sorted(METRICS), default="wall")
    parser.add_argument("--band", type=float, default=5.0)
    args = parser.parse_args()

    column, unit = METRICS[args.metric]
    summary = record_results.summarise(args.out, args.version)
    machine = summary["machine_epoch"]
    machine_dir = os.path.join(args.results, "series", machine)
    if not os.path.isdir(machine_dir):
        known = sorted(os.listdir(os.path.join(args.results, "series"))) if os.path.isdir(
            os.path.join(args.results, "series")) else []
        print("No nightly history on this machine (machine epoch {}; the results hold {}). The machine, JDK, "
              "heap flags or clock differ from the nightly runs'.".format(machine, ", ".join(known) or "none"))
        return 1

    print("{} against the latest nightly results of machine epoch {}, {} per operation; "
          "band ±{:g} %.".format(args.version, machine, args.metric, args.band))
    print()
    print("| Benchmark | Contender | Latest nightly | Commit | This run | Delta | |")
    print("|---|---|--:|---|--:|--:|---|")
    worse = 0
    for c in summary["contenders"]:
        cls, contender = c["benchmark"], c["contender"]
        name = cls[:-len("Benchmark")] if cls.endswith("Benchmark") else cls
        value = c[column]
        row = latest(os.path.join(machine_dir, cls, "{}@{}.csv".format(contender, c["epoch"])), column)
        if row is None:
            others = glob.glob(os.path.join(machine_dir, cls, "{}@*.csv".format(glob.escape(contender))))
            note = "definition differs from the nightly one" if others else "no nightly history"
            print("| {} | {} | – | – | {} | – | {} |".format(name, contender, number(value, unit), note))
            continue
        before = float(row[column])
        if value is None or before == 0:
            delta, call = "–", "not measured"
        else:
            percent = (value / before - 1) * 100
            delta = "{:+.1f} %".format(percent)
            if c["verdict"]:
                call = c["verdict"]
            elif percent > args.band:
                call, worse = "worse", worse + 1
            elif percent < -args.band:
                call = "better"
            else:
                call = "within the band"
        print("| {} | {} | {} | {} {} | {} | {} | {} |".format(
            name, contender, number(before, unit), row["commit"][:9], row["time"][:10], number(value, unit), delta,
            call))
    print()
    print("{} worse beyond ±{:g} %.".format(worse, args.band) if worse else "Nothing worse beyond ±{:g} %.".format(args.band))
    return 0


if __name__ == "__main__":
    sys.exit(main())
