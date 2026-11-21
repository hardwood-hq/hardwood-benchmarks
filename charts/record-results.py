#!/usr/bin/env python3
"""Record one Hardwood version's regression run into a results repository for Apache Otava.

Usage: record-results.py OUT VERSION --results DIR --hardwood-repo DIR [--summary FILE]

OUT is run-regression.sh's output directory, VERSION the version it ran, DIR a checkout of
hardwood-benchmark-results, and --hardwood-repo a Hardwood clone, where the commit a version
was built from gives each row its time. Run from the benchmarks checkout that produced OUT.

Per contender one row is added to series/<machine epoch>/<Benchmark>/<contender>@<epoch>.csv,
or to its .unsettled.csv twin when settling.py calls it not settled or bimodal: the commit's
time, commit, version, when the run began, its verdict, and the metrics RegressionProfiler
recorded (the median of the forks' mean time and its spread, each fork's mean, and the
median CPU, JIT, GC and allocation per operation and the largest live-set peak over the
measurement iterations of every fork). A series is kept in the order of its rows' time, the
measured commit's, since Otava reads rows in file order: an older version recorded later
lands before the newer ones. The run's raw results go to runs/<start>-<version>/.
--summary writes what was recorded as JSON: the commit, the version, the machine epoch, and
per contender its benchmark, script, series file and whether its series is new.
otava.yaml is written again from every series file, one Otava test per file.

A contender's epoch hashes what it measures: its script's preset and the iteration counts
every preset shares, its benchmark method's source, its parameters and the size of its
fixture, to three significant figures. A machine epoch hashes the manifest's description of the machine and JVM. A new
hash of either starts a new series, so that no series mixes two definitions or machines.
"""
import argparse
import csv
import datetime
import glob
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import settling  # noqa: E402

SCHEMA = 1
COLUMNS = ["time", "commit", "version", "run", "schema", "verdict", "wall_ms", "wall_spread_pct",
           "wall_forks_ms", "cpu_ms", "jit_ms", "gc_ms", "alloc_bytes", "live_bytes"]
OTAVA_METRICS = {"wall_ms": -1, "cpu_ms": -1, "alloc_bytes": -1, "live_bytes": -1}
MACHINE_KEYS = ["cpu", "cores", "microcode", "kernel", "java", "jvm_flags", "governor", "max_freq_khz",
                "no_turbo"]
FIXTURE_KEYS = ["rows", "bytes", "window"]


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()[:12]


def shell_value(script, name):
    """The single-quoted value of a `NAME='...'` line of a run script, "" without one."""
    with open(script) as f:
        m = re.search(r"^{}='([^']*)'".format(name), f.read(), re.M)
    return m.group(1) if m else ""


def common_preset():
    with open("bench-common.sh") as f:
        m = re.search(r"local common=\(([^)]*)\)", f.read())
    return m.group(1)


def method_source(cls, method):
    """The source of benchmark method `method` of class `cls`, whitespace collapsed."""
    for path in glob.glob("src/main/java/**/{}.java".format(cls), recursive=True):
        with open(path) as f:
            text = f.read()
        m = re.search(r"public\s+[\w<>\[\], .]+\s+{}\s*\(".format(re.escape(method)), text)
        if not m:
            continue
        depth, i = 0, text.index("{", m.end())
        for j in range(i, len(text)):
            depth += {"{": 1, "}": -1}.get(text[j], 0)
            if depth == 0:
                return " ".join(text[m.start():j + 1].split())
    raise SystemExit("record-results: no method {} in {}".format(method, cls))


def fixture_value(key, value):
    """A fixture's size to three significant figures: the version that writes a fixture leaves
    its name in the file's metadata, so its exact size differs by a few hundred bytes with the
    version that first ran the benchmark, while a change to the data moves it further."""
    if key != "bytes":
        return value
    return "{:.3g}".format(float(value))


def scripts_by_file(out):
    """{results file name: [scripts that wrote it]} from run-regression.sh's files.txt."""
    found = {}
    with open(os.path.join(out, "files.txt")) as f:
        for line in f:
            script, name = line.split()
            found.setdefault(name, [])
            if script not in found[name]:
                found[name].append(script)
    return found


def script_of(cls, method, scripts):
    """Which of `scripts` timed `method`: the one whose regression contenders it matches."""
    for script in scripts:
        include = shell_value("run-{}.sh".format(script), "BENCH_REGRESSION_INCLUDE") or "hardwood"
        if re.search(include, cls + "." + method):
            return script
    raise SystemExit("record-results: no script of {} times {}.{}".format(scripts, cls, method))


def meta(run, cls):
    fields = {}
    path = os.path.join(run, "bench-meta-{}.tsv".format(cls))
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if len(cols) >= 2:
                    fields[cols[0]] = cols[1]
    return fields


def metric_rows(run):
    """{(class, contender): [measurement records]} of one run's RegressionProfiler files."""
    found = {}
    for path in glob.glob(os.path.join(run, "bench-metrics-*.jsonl")):
        with open(path) as f:
            for line in f:
                if line.strip():
                    r = json.loads(line)
                    if r["phase"] == "measurement":
                        cls = r["benchmark"].rsplit(".", 2)[-2]
                        found.setdefault((cls, settling.contender_name(r["benchmark"], r["params"])), []).append(r)
    return found


def median_of(records, field):
    values = [r[field] for r in records if r[field] is not None]
    return statistics.median(values) if values else None


def number(value, digits=6):
    return "" if value is None else "{:.{}g}".format(value, digits)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out")
    parser.add_argument("version")
    parser.add_argument("--results", required=True)
    parser.add_argument("--hardwood-repo", required=True)
    parser.add_argument("--summary")
    args = parser.parse_args()

    with open(os.path.join(args.out, "manifest.json")) as f:
        manifest = json.load(f)
    machine = {k: manifest.get(k) for k in MACHINE_KEYS}
    machine_epoch = "m" + digest(machine)
    machines = os.path.join(args.results, "machines")
    os.makedirs(machines, exist_ok=True)
    with open(os.path.join(machines, machine_epoch + ".json"), "w") as f:
        json.dump(machine, f, indent=2, sort_keys=True)

    vdir = os.path.join(args.out, args.version)
    runs = sorted(d for d in glob.glob(os.path.join(vdir, "run-*")) if os.path.isdir(d))
    if not runs:
        raise SystemExit("record-results: no runs of {} in {}".format(args.version, args.out))
    verdicts = settling.verdicts(runs)
    files = scripts_by_file(args.out)
    records, metas = {}, {}
    for run in runs:
        for key, rows in metric_rows(run).items():
            records.setdefault(key, []).extend(rows)
            metas.setdefault(key[0], meta(run, key[0]))

    built = metas[next(iter(metas))].get("hardwood", "")
    m = re.search(r"\(([0-9a-f]{7,40})\)", built)
    if not m:
        raise SystemExit("record-results: the runs record no Hardwood commit ({!r})".format(built))
    commit = subprocess.run(["git", "-C", args.hardwood_repo, "rev-parse", m.group(1)],
                            capture_output=True, text=True, check=True).stdout.strip()
    time = subprocess.run(["git", "-C", args.hardwood_repo, "show", "-s", "--format=%cI", commit],
                          capture_output=True, text=True, check=True).stdout.strip()

    common = common_preset()
    recorded = []
    for (cls, contender), rows in sorted(records.items()):
        method = contender.split("[", 1)[0]
        params = sorted({json.dumps(r["params"], sort_keys=True) for r in rows})
        script = script_of(cls, method, files.get("bench-metrics-{}.jsonl".format(cls), []))
        fixture = {k: fixture_value(k, metas[cls][k]) for k in FIXTURE_KEYS if k in metas[cls]}
        definition = {"script": script, "preset": shell_value("run-{}.sh".format(script), "BENCH_REGRESSION_PRESET"),
                      "common": common, "method": method_source(cls, method), "params": params,
                      "fixture": fixture}
        epoch = "e" + digest(definition)
        epochs = os.path.join(args.results, "epochs", cls, contender)
        os.makedirs(epochs, exist_ok=True)
        with open(os.path.join(epochs, epoch + ".json"), "w") as f:
            json.dump(definition, f, indent=2, sort_keys=True)

        by_fork = {}
        for r in rows:
            by_fork.setdefault(r["fork"], []).append(r["score"])
        fork_means = [statistics.mean(v) for v in by_fork.values()]
        wall = statistics.median(fork_means)
        spread = (max(fork_means) - min(fork_means)) / 2 / wall * 100 if len(fork_means) > 1 else None
        live = [r["liveBytes"] for r in rows if r["liveBytes"] is not None]
        verdict = verdicts.get((cls, contender), "")
        row = {"time": time, "commit": commit, "version": args.version, "run": manifest["started"],
               "schema": SCHEMA, "verdict": verdict, "wall_ms": number(wall), "wall_spread_pct": number(spread, 3),
               "wall_forks_ms": ";".join(number(v) for v in fork_means),
               "cpu_ms": number(median_of(rows, "cpuMs")), "jit_ms": number(median_of(rows, "jitMs")),
               "gc_ms": number(median_of(rows, "gcMs")), "alloc_bytes": number(median_of(rows, "allocBytes")),
               "live_bytes": number(max(live) if live else None)}
        series = os.path.join(args.results, "series", machine_epoch, cls)
        os.makedirs(series, exist_ok=True)
        base = os.path.join(series, "{}@{}".format(contender, epoch))
        new = not os.path.exists(base + ".csv") and not os.path.exists(base + ".unsettled.csv")
        add_row(base + (".unsettled.csv" if verdict else ".csv"), row)
        recorded.append({"benchmark": cls, "contender": contender, "script": script, "epoch": epoch,
                         "file": os.path.relpath(base + ".csv", args.results), "verdict": verdict, "new": new})

    raw = os.path.join(args.results, "runs", "{}-{}".format(manifest["started"].replace(":", ""), args.version))
    os.makedirs(raw, exist_ok=True)
    shutil.copy(os.path.join(args.out, "manifest.json"), raw)
    for name in ("tables.md", "not-run.txt", "progress.log"):
        if os.path.exists(os.path.join(args.out, name)):
            shutil.copy(os.path.join(args.out, name), raw)
    for run in runs:
        dest = os.path.join(raw, os.path.basename(run))
        os.makedirs(dest, exist_ok=True)
        for path in glob.glob(os.path.join(run, "bench-*")):
            shutil.copy(path, dest)

    write_otava(args.results)
    if args.summary:
        with open(args.summary, "w") as f:
            json.dump({"commit": commit, "version": args.version, "time": time, "machine": machine_epoch,
                       "run": manifest["started"], "contenders": recorded}, f, indent=2)
    print("recorded {} contenders of {} ({}) at {}, machine {}".format(len(recorded), args.version, commit[:9], time,
                                                                      machine_epoch))


def add_row(path, row):
    """Adds `row` to the series file `path`, keeping its rows in the order of their time and
    then of their run; a row of the same run and commit is replaced."""
    rows = []
    if os.path.exists(path):
        with open(path, newline="") as f:
            rows = [r for r in csv.DictReader(f) if (r["run"], r["commit"]) != (row["run"], row["commit"])]
    rows.append({k: str(v) for k, v in row.items()})
    rows.sort(key=lambda r: (instant(r["time"]), r["run"]))
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, restval="")
        writer.writeheader()
        writer.writerows(rows)


def instant(iso):
    """An ISO 8601 time with offset as UTC seconds, so that offsets sort alike."""
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()


def write_otava(results):
    """otava.yaml: one test per series file, its time column, the commit and version as
    attributes, and the metrics Otava looks for change points in, all better when lower."""
    lines = ["# Written by hardwood-benchmarks' charts/record-results.py; one test per series file.",
             "tests:"]
    for path in sorted(glob.glob(os.path.join(results, "series", "*", "*", "*.csv"))):
        if path.endswith(".unsettled.csv"):
            continue
        machine, cls, name = os.path.relpath(path, os.path.join(results, "series")).split(os.sep)
        test = "{}.{}@{}".format(cls, name[:-len(".csv")], machine)
        lines += ["  {}:".format(json.dumps(test)),
                  "    type: csv",
                  "    file: {}".format(json.dumps(os.path.relpath(path, results))),
                  "    time_column: time",
                  "    attributes: [commit, version]",
                  "    metrics:"]
        lines += ["      {}:".format(m) + "\n        direction: {}".format(d) for m, d in OTAVA_METRICS.items()]
    with open(os.path.join(results, "otava.yaml"), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
