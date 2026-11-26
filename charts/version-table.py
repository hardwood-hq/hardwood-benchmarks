#!/usr/bin/env python3
"""One Markdown table of a regression run's contenders across versions.

Usage: version-table.py OUT [--script NAME] VERSION [VERSION ...]

OUT is run-regression.sh's output directory, holding <version>/run-<n>/ snapshots and
files.txt (which script wrote which results file). Per contender: each version's median
ms/op over its forks (over its rounds where no forks were recorded), then the last version
against each earlier one, and the last version's spread (half the range of those values,
relative to their median). --script
restricts the table to the benchmarks that script ran.

A cell is marked "(not settled)" or "(bimodal)" as settling.py finds the version's runs of the
contender: measured before it reached steady state, or with forks that disagree.
"""
import argparse
import csv
import os
import statistics

import settling


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out")
    parser.add_argument("--script")
    parser.add_argument("versions", nargs="+")
    args = parser.parse_args()

    files = set()
    with open(os.path.join(args.out, "files.txt")) as index:
        for line in index:
            script, name = line.split()
            if name.startswith("bench-throughput-") and (args.script is None or script == args.script):
                files.add(name)

    times = {}
    for version in args.versions:
        vdir = os.path.join(args.out, version)
        runs = sorted(d for d in os.listdir(vdir) if d.startswith("run-")) if os.path.isdir(vdir) else []
        for run in runs:
            for name in files:
                path = os.path.join(vdir, run, name)
                if not os.path.exists(path):
                    continue
                with open(path) as f:
                    for row in csv.DictReader(f, delimiter="\t"):
                        key = (row["benchmark"], row["contender"])
                        times.setdefault(key, {}).setdefault(version, []).append(float(row["ms_per_op"]))

    marks = flagged(args.out, args.versions)
    # Each fork's mean where the runs recorded forks, so that a single run has a spread.
    for version in args.versions:
        vdir = os.path.join(args.out, version)
        if not os.path.isdir(vdir):
            continue
        runs = [os.path.join(vdir, d) for d in sorted(os.listdir(vdir)) if d.startswith("run-")]
        for key, means in settling.fork_means(runs).items():
            if key in times and len(means) >= 2:
                times[key][version] = means

    last = args.versions[-1]
    earlier = args.versions[:-1]
    header = ["Benchmark", "Contender"] + [f"{v} ms" for v in args.versions] \
        + [f"vs {v}" for v in earlier] + [f"{last} spread"]
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * 2 + "---:|" * (len(header) - 2))
    for (benchmark, contender) in sorted(times):
        runs = times[(benchmark, contender)]
        median = {v: statistics.median(runs[v]) for v in runs}
        cells = [benchmark.removesuffix("Benchmark"), contender]
        cells += [(fmt(median[v]) + (" ({})".format(marks[(v, benchmark, contender)])
                                     if (v, benchmark, contender) in marks else ""))
                  if v in median else "not run" for v in args.versions]
        for v in earlier:
            cells.append(f"{(median[last] / median[v] - 1) * 100:+.1f} %" if last in median and v in median else "—")
        spread = runs.get(last, [])
        cells.append(f"±{(max(spread) - min(spread)) / 2 / median[last] * 100:.1f} %" if len(spread) > 1 else "—")
        print("| " + " | ".join(cells) + " |")


def flagged(out, versions):
    """{(version, benchmark, contender): verdict} for contenders settling.py flags."""
    found = {}
    for version in versions:
        vdir = os.path.join(out, version)
        if not os.path.isdir(vdir):
            continue
        runs = [os.path.join(vdir, d) for d in sorted(os.listdir(vdir)) if d.startswith("run-")]
        for (benchmark, contender), verdict in settling.verdicts(runs).items():
            found[(version, benchmark, contender)] = verdict
    return found


def fmt(ms):
    if ms < 1:
        return f"{ms:.3f}"
    if ms < 10:
        return f"{ms:.2f}"
    if ms < 100:
        return f"{ms:.1f}"
    return f"{ms:,.0f}"


if __name__ == "__main__":
    main()
