#!/usr/bin/env python3
"""Check a results publication before anything from it is published.

Reads the publication's raw JMH logs and nothing derived from them, and checks:

  logs      every measured iteration is in the log and averages to JMH's printed
            result, each log's pass (-Dperf.pass) matches its file name, and every
            log of one snapshot names the same Hardwood version and commit
  tsvs      each run's bench-throughput-*.tsv holds exactly the means of its logs
  medians   each median/ holds the per-value median of its runs, recomputed here
  repeats   every snapshot has at least --min-runs runs
  pinned    every Hardwood contender timed on all cores is also timed under
            taskset -c 0 (the charts' 1-core bars); parquet-java is not, its 1-core
            comparison uses its all-cores run
  gates     every gate log in <publication>/gates passed; a failure is accepted only
            when it matches a --known-gate-failure, which must name a contender that
            the publication does not chart
  upstream  the parquet-java version the pom pins (which also fixes Avro, through
            parquet-avro) is the latest release on Maven Central (UNVERIFIED when
            Central cannot be reached)
  claims    every line of <publication>/claims.tsv evaluates, from the recomputed
            medians, to the value the published text states

A snapshot is a directory holding run-N/ directories (and a median/). A run's logs
are its *.log files; a log's pass is "pinned" when its name contains "-pin".

claims.tsv has three tab-separated columns, `claim`, `expression`, `expect`:
  - `expression` is arithmetic (with max and min) over ms(SNAPSHOT, PASS, CONTENDER), the median ms/op
    of a contender, where SNAPSHOT is the snapshot's path inside the publication,
    e.g. ms("pq1.18.2/main", "unpinned", "hardwoodColumnar");
  - `expect` is the value as stated: `6.3` (the value rounds to it at the stated
    precision), `>3` or `>=3`, or `1.7..1.8` (rounds into the range).

Usage:
    python3 charts/check-publication.py results/<publication> [--min-runs 3]
        [--known-gate-failure TEXT] [--offline]

Exits 1 when any check fails. Stdlib only; no third-party dependencies.
"""
import argparse
import glob
import os
import re
import statistics
import sys
import urllib.request

ITERATION = re.compile(r"^Iteration\s+\d+: ([\d.]+) ms/op", re.M)
RESULT = re.compile(r"Result \"[^\"]+\":\s*\n\s*([\d.]+) ")
BENCHMARK = re.compile(r"# Benchmark: \S+\.(\w+)")
PARAMS = re.compile(r"# Parameters: \(selectivity = (\w+)\)")
MEASUREMENT = re.compile(r"# Measurement: (\d+) iterations")
PASS = re.compile(r"-Dperf\.pass=(\w+)")
HARDWOOD = re.compile(r"Run params: .*?Hardwood (\S+) \((\w+)\)")
CENTRAL = "https://repo1.maven.org/maven2/{}/maven-metadata.xml"
# Avro comes in through parquet-avro, so the parquet-java version pins it too.
UPSTREAM = {"parquet.version": "org/apache/parquet/parquet-hadoop"}


class Report:
    def __init__(self):
        self.failures = 0

    def line(self, status, check, text):
        if status == "FAIL":
            self.failures += 1
        print("{:10} {:9} {}".format(status, check, text))


def parse_log(path):
    """Yields (contender, pass, mean, problem) for each JMH result block of a log."""
    text = open(path, encoding="utf-8", errors="replace").read()
    file_pass = "pinned" if "-pin" in os.path.basename(path) else "unpinned"
    for block in text.split("# JMH version:")[1:]:
        bench = BENCHMARK.search(block)
        result = RESULT.search(block)
        if not bench or not result:
            continue
        params = PARAMS.search(block)
        contender = bench.group(1) + ("[{}]".format(params.group(1)) if params else "")
        iterations = [float(x) for x in ITERATION.findall(block)]
        expected = int(MEASUREMENT.search(block).group(1))
        passes = PASS.findall(block)
        mean = sum(iterations) / len(iterations) if iterations else float("nan")
        problem = None
        if len(iterations) != expected:
            problem = "{} measured iterations, the header says {}".format(len(iterations), expected)
        elif abs(mean - float(result.group(1))) > 0.0015:
            problem = "iterations average {:.3f}, JMH printed {}".format(mean, result.group(1))
        elif not passes or passes[-1] != file_pass:
            problem = "pass {} in a file named for {}".format(passes[-1] if passes else "none", file_pass)
        yield contender, file_pass, mean, problem


def read_tsv(path):
    values = {}
    with open(path) as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 4 and cols[0] != "pass":
                values[(cols[0], cols[2])] = float(cols[3])
    return values


def check_snapshot(pub, snap, args, report, medians):
    name = os.path.relpath(snap, pub)
    runs = sorted(glob.glob(os.path.join(snap, "run-*")))
    status = "ok" if len(runs) >= args.min_runs else "FAIL"
    report.line(status, "repeats", "{}: {} runs (minimum {})".format(name, len(runs), args.min_runs))
    per_run = []
    builds = set()
    for run in runs:
        means = {}
        for log in sorted(glob.glob(os.path.join(run, "*.log"))):
            text = open(log, encoding="utf-8", errors="replace").read()
            builds.update(m.groups() for m in HARDWOOD.finditer(text) if "base" not in os.path.basename(log))
            for contender, pass_, mean, problem in parse_log(log):
                if problem:
                    report.line("FAIL", "logs", "{}: {}: {}".format(os.path.relpath(log, pub), contender, problem))
                means[(pass_, contender)] = mean
        tsv = {}
        for path in glob.glob(os.path.join(run, "bench-throughput-*.tsv")):
            tsv.update(read_tsv(path))
        if set(tsv) != set(means):
            report.line("FAIL", "tsvs", "{}: TSV and logs hold different contenders: {}".format(
                os.path.relpath(run, pub), sorted(set(tsv) ^ set(means))))
        for key in sorted(set(tsv) & set(means)):
            if abs(tsv[key] - means[key]) > 0.0015:
                report.line("FAIL", "tsvs", "{}: {} {}: TSV {:.3f}, logs {:.3f}".format(
                    os.path.relpath(run, pub), key[0], key[1], tsv[key], means[key]))
        per_run.append(means)
    if len(builds) > 1:
        report.line("FAIL", "logs", "{}: logs name more than one Hardwood build: {}".format(name, sorted(builds)))
    keys = set().union(*per_run) if per_run else set()
    for key in sorted(keys):
        values = [r[key] for r in per_run if key in r]
        if len(values) != len(per_run):
            report.line("FAIL", "repeats", "{}: {} {} is in {} of {} runs".format(name, key[0], key[1], len(values), len(per_run)))
        medians[(name, key[0], key[1])] = statistics.median(values)
    median_dir = os.path.join(snap, "median")
    if os.path.isdir(median_dir):
        stored = {}
        for path in glob.glob(os.path.join(median_dir, "bench-throughput-*.tsv")):
            stored.update(read_tsv(path))
        bad = [k for k in keys if k not in stored or abs(stored[k] - medians[(name,) + k]) > 0.0015]
        report.line("FAIL" if bad else "ok", "medians", "{}: median/ {}".format(
            name, "differs at {}".format(sorted(bad)) if bad else "matches the runs"))
    unpinned = {c for p, c in keys if p == "unpinned" and c.startswith("hardwood")}
    pinned = {c for p, c in keys if p == "pinned"}
    missing = sorted(unpinned - pinned)
    if pinned:
        report.line("FAIL" if missing else "ok", "pinned", "{}: {}".format(
            name, "no taskset -c 0 run for {}".format(", ".join(missing)) if missing
            else "every Hardwood contender has a taskset -c 0 run"))
    if builds:
        report.line("ok", "logs", "{}: Hardwood {}".format(name, ", ".join("{} ({})".format(*b) for b in sorted(builds))))


def check_gates(pub, args, report):
    logs = sorted(glob.glob(os.path.join(pub, "gates", "*.log")))
    if not logs:
        report.line("FAIL", "gates", "no gate logs in gates/")
    for log in logs:
        text = open(log, encoding="utf-8", errors="replace").read()
        fails = [l.strip() for l in text.splitlines() if re.match(r"\s*FAIL\b", l) or l.startswith("Exception")]
        unknown = [l for l in fails if not any(k in l for k in args.known_gate_failure)]
        rel = os.path.relpath(log, pub)
        if unknown:
            report.line("FAIL", "gates", "{}: {}".format(rel, unknown[0][:160]))
        elif fails:
            report.line("accepted", "gates", "{}: {} known failure line(s)".format(rel, len(fails)))
        elif "Gate passed" in text:
            report.line("ok", "gates", "{}: passed".format(rel))
        else:
            report.line("FAIL", "gates", "{}: no 'Gate passed' line".format(rel))


def latest_release(path):
    with urllib.request.urlopen(CENTRAL.format(path), timeout=10) as r:
        return re.search(r"<release>([^<]+)</release>", r.read().decode()).group(1)


def check_upstream(args, report):
    pom = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pom.xml")).read()
    for prop, path in UPSTREAM.items():
        found = re.search(r"<{0}>([^<]+)</{0}>".format(re.escape(prop)), pom)
        if not found:
            report.line("FAIL", "upstream", "pom.xml sets no <{}>".format(prop))
            continue
        pinned = found.group(1)
        if args.offline:
            report.line("UNVERIFIED", "upstream", "{} {} (--offline)".format(prop, pinned))
            continue
        try:
            latest = latest_release(path)
        except Exception as e:
            report.line("UNVERIFIED", "upstream", "{} {}: Maven Central unreachable ({})".format(prop, pinned, e))
            continue
        report.line("ok" if latest == pinned else "FAIL", "upstream",
                    "{} pinned {}, latest release {}".format(prop, pinned, latest))


def matches(value, expect):
    expect = expect.strip()
    if expect.startswith(">="):
        return value >= float(expect[2:])
    if expect.startswith(">"):
        return value > float(expect[1:])
    if ".." in expect:
        lo, hi = expect.split("..")
        digits = len(lo.split(".")[1]) if "." in lo else 0
        return float(lo) <= round(value, digits) <= float(hi)
    digits = len(expect.split(".")[1]) if "." in expect else 0
    return round(value, digits) == float(expect)


def check_claims(pub, report, medians):
    path = os.path.join(pub, "claims.tsv")
    if not os.path.exists(path):
        report.line("FAIL", "claims", "no claims.tsv: nothing ties the published text to the data")
        return

    def ms(snapshot, pass_, contender):
        key = (snapshot, pass_, contender)
        if key not in medians:
            raise KeyError("no {} {} in {}".format(pass_, contender, snapshot))
        return medians[key]

    with open(path) as f:
        for n, line in enumerate(f, 1):
            cols = line.rstrip("\n").split("\t")
            if n == 1 or not line.strip() or line.startswith("#"):
                continue
            claim, expression, expect = cols[0], cols[1], cols[2]
            try:
                value = eval(expression, {"__builtins__": {}}, {"ms": ms, "max": max, "min": min})
            except Exception as e:
                report.line("FAIL", "claims", "{}: {}".format(claim, e))
                continue
            report.line("ok" if matches(value, expect) else "FAIL", "claims",
                        "{}: {:.3f} (stated {})".format(claim, value, expect))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("publication", help="results/<publication> directory")
    ap.add_argument("--min-runs", type=int, default=3, help="repeats each snapshot needs (default 3)")
    ap.add_argument("--known-gate-failure", action="append", default=[],
                    help="text of a gate failure to accept, e.g. the name of an uncharted contender")
    ap.add_argument("--offline", action="store_true", help="skip the Maven Central check")
    args = ap.parse_args()
    pub = os.path.normpath(args.publication)
    report = Report()
    medians = {}
    snapshots = sorted({os.path.dirname(r) for r in glob.glob(os.path.join(pub, "**", "run-*"), recursive=True)})
    if not snapshots:
        sys.exit("check-publication: no run-N directories under " + pub)
    for snap in snapshots:
        check_snapshot(pub, snap, args, report, medians)
    check_gates(pub, args, report)
    check_upstream(args, report)
    check_claims(pub, report, medians)
    print("\n{} check(s) failed".format(report.failures) if report.failures else "\nall checks passed")
    sys.exit(1 if report.failures else 0)


if __name__ == "__main__":
    main()
