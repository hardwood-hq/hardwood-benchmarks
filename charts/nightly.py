#!/usr/bin/env python3
"""The analysis steps of nightly.sh, on a checkout of hardwood-benchmark-results.

  nightly.py analyze RESULTS --date DATE [--summary FILE --plan FILE]
      With --summary, a night's analysis: adds the night the summary records to nights.csv,
      the commits the nightly job measured as its own, runs Otava over every settled series of
      the summary's machine epoch, and writes the side-by-side runs the new regressions get to
      --plan: per script, the commit before the change and the commit after it. Without, the
      analysis after a batch of backfills: every machine epoch, no night recorded, nothing
      planned. Either adds the change points it finds to changes.json as found on DATE.
  nightly.py releases CLONE
      Prints the versions of the releases the history page marks from FIRST_RELEASE on, oldest
      first: what a series whose epoch starts is backfilled with.
  nightly.py plan FILE N FIELD
      Prints field FIELD (script, before or after) of the plan's run N, or with N "count"
      the number of runs.
  nightly.py side-by-side RESULTS --plan FILE --item N --out DIR --before-version V --after-version V
      Adds to the change points of the plan's run N what DIR, a run-regression.sh run of the
      commit before (version V) and the commit after, measured: each flagged metric's change
      between the two commits' medians and every fork's value. It decides nothing.
  nightly.py report RESULTS --date DATE [--summary FILE --out DIR] [--status TEXT]
      Writes reports/DATE.md and reports/latest.md: what ran, against the median of each
      series' last nights, and the change points found, or why the night recorded nothing.
  nightly.py page RESULTS [--hardwood CLONE]
      Writes history.json, which index.html, the history page, reads; with a Hardwood clone, it
      lists the clone's release tags, so the page marks a release whether or not it was measured.
  nightly.py issues RESULTS --repo OWNER/NAME --token-file FILE
      Opens an issue for each regression and unstable contender not alerted yet, and records its
      URL; a failure leaves the rest for the next night.
  nightly.py mute RESULTS
      Marks each regression and unstable contender not alerted yet as found with alerts off.
  nightly.py health --date DATE --repo OWNER/NAME --token-file FILE (--failing REASON | --ok)
      Keeps one issue open while nights fail: --failing opens it, or comments on it when it is
      open; --ok closes it.

A change point is one series' change at one commit, as Otava finds it: it alone decides what
changed. changes.json keeps every one, so that a change point Otava finds again, or finds a
row or two away as rows arrive, is not handled twice. A regression whose commit after a night
measured as its own is a regression, however many rows Otava needed to see it; one at a
commit only a backfill measured is kept as history.
"""
import argparse
import csv
import datetime
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import settling  # noqa: E402

METRICS = {"wall_ms": "score", "cpu_ms": "cpuMs", "alloc_bytes": "allocBytes", "live_bytes": "liveBytes"}
UNITS = {"wall_ms": "ms", "cpu_ms": "ms", "alloc_bytes": "B", "live_bytes": "B"}
# The caller share from which the report marks a contender as bound by its caller's thread.
CALLER_BOUND = 0.8
# The smallest change Otava reports per metric, as a fraction of the smaller of the means on
# either side of it: CPU time
# includes the JIT's, which moves more night to night than wall time; allocation hardly moves.
MAGNITUDES = {"wall_ms": 0.03, "cpu_ms": 0.05, "alloc_bytes": 0.01}
# A contender whose rows have not settled for this many nights in a row is unstable: its rows
# stay out of Otava, so it gets an issue of its own instead of falling silent.
UNSTABLE_NIGHTS = 3
SHIFT_ROWS = 2
LAST_NIGHTS = 5


def load(path, default):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return default


def save(path, value):
    with open(path, "w") as f:
        json.dump(value, f, indent=2, sort_keys=True)
        f.write("\n")


def rows_of(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def instant(iso):
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))


def tests_of(results):
    """{Otava test name: series file} from otava.yaml, in its order."""
    tests, name = {}, None
    with open(os.path.join(results, "otava.yaml")) as f:
        for line in f:
            if line.startswith("  ") and not line.startswith("   ") and line.rstrip().endswith(":"):
                name = json.loads(line.strip()[:-1])
            elif line.strip().startswith("file:") and name:
                tests[name] = json.loads(line.split(":", 1)[1].strip())
    return tests


def series_meta(results, path):
    """The benchmark, contender, epoch and machine epoch of a series file."""
    machine, cls, name = os.path.relpath(path, "series").split(os.sep)
    contender, epoch = name[:-len(".csv")].rsplit("@", 1)
    definition = load(os.path.join(results, "epochs", cls, contender, epoch + ".json"), {})
    return {"benchmark": cls, "contender": contender, "epoch": epoch, "machine": machine,
            "script": definition.get("script", "")}


def series_files(results):
    """Every series as (settled file, unsettled file), relative to the results, either None
    where the series has none of that kind."""
    out = {}
    for path in glob.glob(os.path.join(results, "series", "*", "*", "*.csv")):
        rel = os.path.relpath(path, results)
        unsettled = rel.endswith(".unsettled.csv")
        key = rel[:-len(".unsettled.csv")] + ".csv" if unsettled else rel
        out.setdefault(key, [None, None])[1 if unsettled else 0] = rel
    return sorted((s, u) for s, u in out.values())


def unsettled_streak(results, settled, unsettled, night_runs):
    """The rows of the latest nights in a row whose row of this series did not settle, oldest
    first, and the night's settled row before them, or None."""
    rows = [(r, True) for r in rows_of(os.path.join(results, settled))] if settled else []
    rows += [(r, False) for r in rows_of(os.path.join(results, unsettled))] if unsettled else []
    streak = []
    for r, ok in sorted((x for x in rows if x[0]["run"] in night_runs), key=lambda x: x[0]["run"], reverse=True):
        if ok:
            return streak[::-1], r
        streak.append(r)
    return streak[::-1], None


def is_unstable(streak, latest_run):
    """Unstable: not settled for UNSTABLE_NIGHTS nights in a row, up to the latest night. A
    superseded epoch's series ends before it, and is left out."""
    return len(streak) >= UNSTABLE_NIGHTS and streak[-1]["run"] == latest_run


def unstable(args, machine, latest_run, night_runs, changes, found):
    """Adds to changes.json each contender under `machine` (a series/ prefix) that is unstable
    as of the night `latest_run`, once per run of unsettled nights."""
    for settled, unsettled in series_files(args.results):
        if not unsettled or not unsettled.startswith(machine):
            continue
        file = settled or unsettled[:-len(".unsettled.csv")] + ".csv"
        streak, before = unsettled_streak(args.results, settled, unsettled, night_runs)
        if not is_unstable(streak, latest_run):
            continue
        first = streak[0]
        # Keyed on the first night's run, not its commit: one commit can be measured night after night.
        if any(c["file"] == file and c["kind"] == "unstable" and c.get("since_run") == first["run"] for c in changes):
            continue
        meta = series_meta(args.results, file)
        test = "{}.{}@{}@{}".format(meta["benchmark"], meta["contender"], meta["epoch"], meta["machine"])
        changes.append(dict(meta, file=file, test=test, kind="unstable", status="unstable", commit=first["commit"],
                            version=first["version"], before_commit=before["commit"] if before else "",
                            before_version=before["version"] if before else "", found=found, metrics={},
                            verdicts=[r["verdict"] for r in streak], since_run=first["run"],
                            id="{}@unstable@{}".format(test, first["run"][:10])))


def otava(results, tests):
    """Otava's change points of `tests`, {test: [change point]}, one per commit with the changes
    of every metric there. Otava takes one minimum magnitude per run, so it runs per metric."""
    found = {}
    names = list(tests)
    for metric, magnitude in MAGNITUDES.items():
        for i in range(0, len(names), 50):
            done = subprocess.run([os.environ.get("OTAVA", "otava"), "analyze", "--config-file", "otava.yaml",
                                   "--output", "json", "--metrics", metric, "--magnitude", str(magnitude)]
                                  + names[i:i + 50], cwd=results, capture_output=True, text=True)
            if done.returncode != 0:
                raise SystemExit("nightly: otava failed:\n" + done.stderr[-2000:])
            # One JSON object per test, a line each.
            for line in done.stdout.splitlines():
                if not line.strip().startswith("{"):
                    continue
                for test, points in json.loads(line).items():
                    at = found.setdefault(test, {})
                    for point in points:
                        commit = point["attributes"]["commit"]
                        if commit in at:
                            at[commit]["changes"] += point["changes"]
                        else:
                            at[commit] = point
    return {test: list(at.values()) for test, at in found.items()}


def analyze(args):
    if bool(args.summary) != bool(args.plan):
        raise SystemExit("nightly: analyze takes --summary and --plan together, or neither")
    if args.summary and not os.path.exists(args.summary):
        raise SystemExit("nightly: no summary " + args.summary)
    summary = load(args.summary, None) if args.summary else None
    nights_path = os.path.join(args.results, "nights.csv")
    nights = rows_of(nights_path)
    if summary and not any(n["run"] == summary["run"] for n in nights):
        nights.append({"date": summary["run"][:10], "commit": summary["commit"], "version": summary["version"],
                       "run": summary["run"]})
        with open(nights_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["date", "commit", "version", "run"])
            writer.writeheader()
            writer.writerows(nights)
    machine = "series/{}/".format(summary["machine"]) if summary else "series/"
    tests = {t: f for t, f in tests_of(args.results).items() if f.startswith(machine)}
    changes = load(os.path.join(args.results, "changes.json"), [])
    found = args.date
    night_commits = {n["commit"] for n in nights}
    plan = {}
    for test, points in otava(args.results, tests).items():
        rows = rows_of(os.path.join(args.results, tests[test]))
        commits = [r["commit"] for r in rows]
        for point in points:
            after = point["attributes"]["commit"]
            if after not in commits:
                continue
            index = commits.index(after)
            if index == 0:
                continue
            # Otava rounds its forward_change_percent to a whole number; the means are exact.
            metrics = {c["metric"]: {"percent": round((float(c["mean_after"]) / float(c["mean_before"]) - 1) * 100, 2),
                                     "before": float(c["mean_before"]), "after": float(c["mean_after"]),
                                     "pvalue": float(c["pvalue"])} for c in point["changes"]}
            kind = kind_of(metrics)
            known = handled(changes, tests[test], commits, index, kind)
            if known is not None:
                continue
            meta = series_meta(args.results, tests[test])
            change = dict(meta, file=tests[test], test=test, kind=kind, commit=after, version=rows[index]["version"],
                          before_commit=rows[index - 1]["commit"], before_version=rows[index - 1]["version"],
                          found=found, metrics=metrics)
            if kind == "improvement":
                change["status"] = "improvement"
            elif after in night_commits:
                change["status"] = "regression"
            else:
                change["status"] = "history"
            change["id"] = "{}@{}@{}".format(test, kind, after[:9])
            changes.append(change)
    # Unstable is judged up to the latest night, which a batch of backfills does not move.
    if nights:
        unstable(args, machine, nights[-1]["run"], {n["run"] for n in nights}, changes, found)
    for change in changes if summary else []:
        # Any regression not alerted yet without one: also those an analysis after backfills found.
        if change["status"] == "regression" and "issue" not in change and "side_by_side" not in change:
            key = "{} {} {}".format(change["script"], change["before_commit"], change["commit"])
            plan.setdefault(key, {"script": change["script"], "before": change["before_commit"],
                                  "after": change["commit"], "changes": []})["changes"].append(change["id"])
    save(os.path.join(args.results, "changes.json"), changes)
    if args.plan:
        save(args.plan, list(plan.values()))
    print("{} new regressions to run side by side, in {} runs".format(
        sum(len(p["changes"]) for p in plan.values()), len(plan)))


def kind_of(metrics):
    """Wall time decides where it moved: a CPU or allocation increase that buys a faster
    operation is an improvement, still listed with the metrics that rose. Without a wall-time
    change, any metric that rose makes a regression."""
    if "wall_ms" in metrics:
        return "regression" if metrics["wall_ms"]["percent"] > 0 else "improvement"
    return "regression" if any(m["percent"] > 0 for m in metrics.values()) else "improvement"


def handled(changes, file, commits, index, kind):
    """The change point of changes.json that this one is: the same series and kind, at a commit
    from SHIFT_ROWS rows before the known one's commit before to SHIFT_ROWS rows after its commit
    after. Rows a backfill inserts land inside that range, so a change point Otava finds again
    between them is still the same one."""
    for change in changes:
        if change["file"] != file or change["kind"] != kind or change["commit"] not in commits:
            continue
        after = commits.index(change["commit"])
        before = commits.index(change["before_commit"]) if change.get("before_commit") in commits else after - 1
        if before + 1 - SHIFT_ROWS <= index <= after + SHIFT_ROWS:
            return change
    return None


def fork_values(run_dirs, benchmark, contender, field):
    """Each fork's value of `field` over its measurement iterations: the mean, or for the
    heap-after-collection peak the largest."""
    by_fork = {}
    for run in run_dirs:
        for path in glob.glob(os.path.join(run, "bench-metrics-{}.jsonl".format(benchmark))):
            with open(path) as f:
                for line in f:
                    if not line.strip():
                        continue
                    r = json.loads(line)
                    if r["phase"] != "measurement" or r[field] is None:
                        continue
                    if settling.contender_name(r["benchmark"], r["params"]) != contender:
                        continue
                    by_fork.setdefault((run, r["fork"]), []).append(r[field])
    pick = max if field == "liveBytes" else statistics.mean
    return [pick(v) for v in by_fork.values()]


def side_by_side(args):
    changes = load(os.path.join(args.results, "changes.json"), [])
    by_id = {c["id"]: c for c in changes}
    for item in load(args.plan, [])[args.item:args.item + 1]:
        before = sorted(glob.glob(os.path.join(args.out, args.before_version, "run-*")))
        after = sorted(glob.glob(os.path.join(args.out, args.after_version, "run-*")))
        for cid in item["changes"]:
            change = by_id[cid]
            measured = {}
            for metric, found in change["metrics"].items():
                if found["percent"] <= 0:
                    continue
                b = fork_values(before, change["benchmark"], change["contender"], METRICS[metric])
                a = fork_values(after, change["benchmark"], change["contender"], METRICS[metric])
                if not a or not b:
                    continue
                measured[metric] = {"percent": round((statistics.median(a) / statistics.median(b) - 1) * 100, 2),
                                    "before_forks": [round(v, 4) for v in b], "after_forks": [round(v, 4) for v in a]}
            change["side_by_side"] = {"date": datetime.date.today().isoformat(), "metrics": measured}
            print("{}: {}".format(cid, side_by_side_text(change) or "not measured side by side"))
    save(os.path.join(args.results, "changes.json"), changes)


def plan_field(args):
    plan = load(args.file, [])
    print(len(plan) if args.n == "count" else plan[int(args.n)][args.field])


def number(value, unit):
    if value is None:
        return ""
    if unit == "B":
        for scale, suffix in ((1 << 30, "GiB"), (1 << 20, "MiB"), (1 << 10, "KiB")):
            if abs(value) >= scale:
                return "{:.1f} {}".format(value / scale, suffix)
        return "{:.0f} B".format(value)
    return "{:,.3g} ms".format(value) if value < 100 else "{:,.0f} ms".format(value)


def against_last(rows, row, metric):
    """`row`'s metric against the median of the series' last LAST_NIGHTS settled rows before it."""
    earlier = [float(r[metric]) for r in rows if r is not row and r[metric] and instant(r["time"]) <= instant(row["time"])
               and r["run"] != row["run"]][-LAST_NIGHTS:]
    if not earlier or not row[metric]:
        return ""
    return "{:+.1f} %".format((float(row[metric]) / statistics.median(earlier) - 1) * 100)


def caller_share(row):
    """The caller's CPU time against wall time, in bold from CALLER_BOUND; empty in rows from
    before the caller's time was recorded."""
    if not row.get("caller_ms") or not row["wall_ms"]:
        return ""
    share = float(row["caller_ms"]) / float(row["wall_ms"])
    return "**{:.2f}**".format(share) if share >= CALLER_BOUND else "{:.2f}".format(share)


def report(args):
    lines = ["# Nightly {}".format(args.date), ""]
    if args.status:
        lines += [args.status, ""]
    if args.summary and os.path.exists(args.summary):
        summary = load(args.summary, None)
        lines += ["| | |", "|---|---|",
                  "| Version | {} |".format(summary["version"]),
                  "| Commit | [{}](https://github.com/hardwood-hq/hardwood/commit/{}) |".format(summary["commit"][:9],
                                                                                         summary["commit"]),
                  "| Run | {} |".format(summary["run"]),
                  "| Machine | [{0}](../machines/{0}.json) |".format(summary["machine"])]
        if args.out and os.path.exists(os.path.join(args.out, "progress.log")):
            with open(os.path.join(args.out, "progress.log")) as f:
                total = [l.split()[1] for l in f if l.startswith("total ")]
            if total:
                lines.append("| Duration | {} min |".format(round(int(total[0].rstrip("s")) / 60)))
        lines += ["", "Each metric against the median of the series' last {} nights. The caller share is the CPU "
                      "time of the thread calling the benchmark against wall time; in bold from {}, where that "
                      "thread hardly waits on Hardwood and wall time misses decode changes that CPU time "
                      "still shows.".format(LAST_NIGHTS, CALLER_BOUND), "",
                  "| Benchmark | Contender | Wall | | CPU | | Allocated | | Heap after GC | | Caller share | |",
                  "|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|"]
        for c in summary["contenders"]:
            base = os.path.join(args.results, c["file"][:-len(".csv")])
            path = base + (".unsettled.csv" if c["verdict"] else ".csv")
            rows = rows_of(path)
            row = next((r for r in rows if r["run"] == summary["run"] and r["commit"] == summary["commit"]), None)
            if row is None:
                continue
            settled = rows_of(base + ".csv")
            if c["verdict"]:
                settled = settled + [row]
                settled.sort(key=lambda r: instant(r["time"]))
            cells = []
            for metric in ("wall_ms", "cpu_ms", "alloc_bytes", "live_bytes"):
                value = float(row[metric]) if row[metric] else None
                cells += [number(value, UNITS[metric]), against_last(settled, row, metric)]
            cells.append(caller_share(row))
            lines.append("| {} | {} | {} | {} |".format(c["benchmark"].replace("Benchmark", ""), c["contender"],
                                                        " | ".join(cells), c["verdict"]))
        if args.out and os.path.exists(os.path.join(args.out, "not-run.txt")):
            with open(os.path.join(args.out, "not-run.txt")) as f:
                not_run = [l.rstrip() for l in f if l.strip()]
            if not_run:
                lines += ["", "Not run:", ""] + ["- " + l.strip() for l in not_run]
    changes = load(os.path.join(args.results, "changes.json"), [])
    order = {"regression": 0, "unstable": 1, "history": 2, "improvement": 3}
    shown = sorted((c for c in changes if c["found"] == args.date), key=lambda c: order[c["status"]])
    lines += ["", "## Change points", "",
              "Found tonight: a regression at a commit a night measured, a contender unstable when it has not "
              "settled for {} nights in a row, history at a commit only a backfill measured, and improvements.".format(
                  UNSTABLE_NIGHTS), ""]
    if not shown:
        lines.append("None.")
    for c in shown:
        if c["kind"] == "unstable":
            lines.append("- **unstable** {}.{}: not settled in the last {} nights ({}), since {}".format(
                c["benchmark"], c["contender"], len(c["verdicts"]), ", ".join(c["verdicts"]), c["commit"][:9])
                + (" ({})".format(c["issue"]) if c.get("issue") else ""))
            continue
        moved = ", ".join("{} {:+.1f} %".format(m, v["percent"]) for m, v in c["metrics"].items())
        measured = side_by_side_text(c)
        extra = "; side by side " + measured if measured else ""
        link = " ({})".format(c["issue"]) if c.get("issue") else ""
        lines.append("- **{}** {}.{}: {} from {} to {}{}{}".format(c["status"], c["benchmark"], c["contender"], moved,
                                                                   c["before_commit"][:9], c["commit"][:9], extra, link))
    text = "\n".join(lines) + "\n"
    os.makedirs(os.path.join(args.results, "reports"), exist_ok=True)
    for name in (args.date + ".md", "latest.md"):
        with open(os.path.join(args.results, "reports", name), "w") as f:
            f.write(text)
    print("report: reports/{}.md".format(args.date))


RELEASE_TAG = re.compile(r"v(\d+\.\d+\.\d+\.(?:Alpha\d+|Beta\d+|CR\d+|Final))")


def releases(clone):
    """Each release tag, placed at the commit of the clone's origin/main it was cut from.

    A release is tagged on that line or on a commit forked off it (the version change, a patch);
    its fork point is the code the nights measured. A release whose fork point lies within a
    day of an earlier release's marks no code of its own and is left out: a maintenance release
    has its release's fork point, and a release cut right after another (1.1.0.Beta3, two
    commits after Beta2) has hardly moved from it.
    origin/main, not HEAD: the job leaves the clone at whatever commit it measured last, a
    backfill's included.
    """
    def git(*argv):
        return subprocess.run(["git", "-C", clone, *argv], check=True, capture_output=True, text=True).stdout.strip()

    tags = []
    for tag in git("tag", "--list", "v*").split():
        m = RELEASE_TAG.fullmatch(tag)
        if m:
            commit, time = git("show", "-s", "--format=%H %cI", tag + "^{commit}").split()
            tags.append((time, m.group(1), commit))
    placed = []
    for tagged, version, commit in tags:
        # No merge base: a tag of unrelated history.
        base = subprocess.run(["git", "-C", clone, "merge-base", commit, "origin/main"], capture_output=True, text=True).stdout.strip()
        if base:
            placed.append((instant(git("show", "-s", "--format=%cI", base)), instant(tagged), version, commit, base))
    out = []
    # By fork point, then tag: of two releases cut within a day, the first cut is kept.
    for _, _, version, commit, base in sorted(placed):
        time = git("show", "-s", "--format=%cI", base)
        if any(abs((instant(time) - instant(r["time"])).total_seconds()) < 86400 for r in out):
            continue
        out.append({"version": version, "commit": commit, "base": base, "time": time})
    return out


# The oldest release the benchmarks build against (src/compat).
FIRST_RELEASE = "1.0.0.Final"


def release_versions(args):
    versions = [r["version"] for r in releases(args.clone)]
    if FIRST_RELEASE not in versions:
        raise SystemExit("nightly: {} is not among the releases of {}: {}".format(FIRST_RELEASE, args.clone, versions))
    for version in versions[versions.index(FIRST_RELEASE):]:
        print(version)


def run_times(progress):
    """A run's duration and, per script in the order it ran, the seconds its gate and its measurement took
    and whether its gate failed, from the run's progress.log: "gate S: passed against V  Ns" (absent or
    without seconds when a gate passed before for the same sources), "gate S: FAILED against V  Ns, see L",
    "round 1/1  V  S  exit X  Ns" and "total Ns"."""
    if not os.path.exists(progress):
        return {}
    scripts, out = {}, {}
    with open(progress) as f:
        for line in f:
            words = line.split()
            if not words:
                continue
            if words[0] == "gate" and len(words) > 1:
                entry = scripts.setdefault(words[1].rstrip(":"), {})
                seconds = [int(w.rstrip(",")[:-1]) for w in words[2:] if re.fullmatch(r"\d+s,?", w)]
                if seconds:
                    entry["gate_s"] = seconds[0]
                if words[2:3] == ["FAILED"] or words[2:3] == ["FAILED,"]:
                    entry["gate_failed"] = True
            elif words[0] == "round" and len(words) >= 7:
                entry = scripts.setdefault(words[3], {})
                entry["measure_s"] = entry.get("measure_s", 0) + int(words[-1].rstrip("s"))
            elif words[0] == "total":
                out["minutes"] = round(int(words[1].rstrip("s")) / 60)
    out["scripts"] = [dict(script=k, gate_s=v.get("gate_s", 0), measure_s=v.get("measure_s", 0),
                           **({"gate_failed": True} if v.get("gate_failed") else {})) for k, v in scripts.items()]
    return out


def night_list(results, changes):
    """Each night that wrote a report: what it measured and in how long, or why it recorded nothing,
    how many change points of each status it found, and the contender of each, for the page to link."""
    measured = {n["date"]: n for n in rows_of(os.path.join(results, "nights.csv"))}
    out = []
    for path in sorted(glob.glob(os.path.join(results, "reports", "????-??-??.md"))):
        date = os.path.basename(path)[:-3]
        night = {"date": date, "report": os.path.relpath(path, results)}
        n = measured.get(date)
        if n:
            night.update(version=n["version"], commit=n["commit"], run=n["run"])
            # A run's directory is named after its start without the time's colons, then its version.
            run_dir = os.path.join(results, "runs", "{}-{}".format(n["run"].replace(":", ""), n["version"]))
            night.update(run_times(os.path.join(run_dir, "progress.log")))
            # A status line above the night's table: its analysis failed.
            with open(path) as f:
                lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
            if lines and not lines[0].startswith("|"):
                night["status"] = lines[0]
        else:
            # The status line report() writes under the title of a night that recorded nothing.
            with open(path) as f:
                status = [l.strip() for l in f if l.strip() and not l.startswith("#")]
            night["status"] = status[0] if status else "nothing recorded"
        found, points = {}, []
        for c in changes:
            if c["found"] == date:
                found[c["status"]] = found.get(c["status"], 0) + 1
                points.append({k: c.get(k) for k in ("status", "benchmark", "contender", "machine", "issue")})
        night["changes"] = found
        night["points"] = points
        out.append(night)
    return out


def page(args):
    series = []
    nights = rows_of(os.path.join(args.results, "nights.csv"))
    night_runs = {n["run"] for n in nights}
    latest = nights[-1]["run"] if nights else None
    for settled, unsettled in series_files(args.results):
        meta = series_meta(args.results, settled or unsettled[:-len(".unsettled.csv")] + ".csv")
        meta.update(file=settled, unsettled=unsettled)
        streak = unsettled_streak(args.results, settled, unsettled, night_runs)[0] if unsettled else []
        if is_unstable(streak, latest):
            meta["unstable"] = len(streak)
        series.append(meta)
    # Only machine epochs that hold series: one whose series moved on keeps its file, for the
    # reports that link it.
    held = {s["machine"] for s in series}
    machines = {os.path.basename(p)[:-5]: load(p, {}) for p in glob.glob(os.path.join(args.results, "machines", "*.json"))
                if os.path.basename(p)[:-5] in held}
    changes = load(os.path.join(args.results, "changes.json"), [])
    save(os.path.join(args.results, "history.json"),
         {"generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
          "series": series, "machines": machines, "changes": changes,
          "releases": releases(args.hardwood) if args.hardwood else [],
          "nights": night_list(args.results, changes)})
    shutil.copy(os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates", "history", "index.html"),
                os.path.join(args.results, "index.html"))
    print("page: {} series".format(len(series)))


METRIC_NAMES = {"wall_ms": "wall time", "cpu_ms": "CPU time", "alloc_bytes": "allocation",
                "live_bytes": "heap after collection"}


def side_by_side_text(change):
    """What the side-by-side run measured, per metric, or "" when it did not run."""
    measured = change.get("side_by_side", {}).get("metrics", {})
    return ", ".join("{} {:+.1f} %".format(m, v["percent"]) for m, v in measured.items())


def worse_metrics(change):
    return [METRIC_NAMES[m] for m, v in change["metrics"].items() if v["percent"] > 0]


def github(token, method, url, payload=None):
    request = urllib.request.Request("https://api.github.com/" + url, method=method,
                                     data=json.dumps(payload).encode() if payload is not None else None,
                                     headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def token_of(path):
    with open(path) as f:
        return f.read().strip()


def unstable_issue(c, base):
    title = "{}.{}: not settled for {} nights".format(c["benchmark"].replace("Benchmark", ""), c["contender"],
                                                     len(c["verdicts"]))
    body = "\n".join([
        "{}.{} has not settled in the nightly runs of the N300 since {}: {}. Its rows stay out of the history Otava "
        "analyses, so a regression in it would go unseen.".format(
            c["benchmark"], c["contender"], c["commit"][:9], ", ".join(c["verdicts"])),
        "",
        ("Commits since the last night it settled: https://github.com/hardwood-hq/hardwood/compare/{}...{}".format(
            c["before_commit"], c["commit"]) if c["before_commit"] else "It has not settled in any night of this epoch."),
        "",
        "Either Hardwood changed so that it no longer reaches a steady state (bimodal: forks differ, often a JIT "
        "decision), or the preset's warmup no longer covers it; the run's tables show the forks.",
        "",
        "Unsettled rows: {}{}".format(base, c["file"][:-len(".csv")] + ".unsettled.csv")])
    return title, body


def issues(args):
    token = token_of(args.token_file)
    changes = load(os.path.join(args.results, "changes.json"), [])
    base = "https://github.com/hardwood-hq/hardwood-benchmark-results/blob/main/"
    for c in changes:
        # No issue key yet: not alerted. An empty one: found while alerts were off (mute).
        if c["status"] not in ("regression", "unstable") or "issue" in c:
            continue
        if c["kind"] == "unstable":
            title, body = unstable_issue(c, base)
            c["issue"] = github(token, "POST", "repos/{}/issues".format(args.repo),
                                {"title": title, "body": body, "labels": ["performance"]})["html_url"]
            print("issue: {}".format(c["issue"]))
            save(os.path.join(args.results, "changes.json"), changes)
            continue
        measured = c.get("side_by_side", {}).get("metrics", {})
        moved = []
        for m, v in c["metrics"].items():
            if v["percent"] > 0:
                beside = "{:+.1f} %".format(measured[m]["percent"]) if m in measured else "not run"
                moved.append("| {} | {} | {} | {:+.1f} % | {} |".format(
                    m, number(v["before"], UNITS[m]), number(v["after"], UNITS[m]), v["percent"], beside))
        body = "\n".join([
            "Otava found {}.{} worse between {} and {} in {}, in the nightly history of the N300.".format(
                c["benchmark"], c["contender"], c["before_commit"][:9], c["commit"][:9], ", ".join(worse_metrics(c))),
            "",
            "Commits: https://github.com/hardwood-hq/hardwood/compare/{}...{}".format(c["before_commit"], c["commit"]),
            "",
            "The side-by-side column is both commits measured again, interleaved, the same night: a change that "
            "does not show there may come from the machine rather than the code.",
            "",
            "| Metric | Before | After | Change in the history | Change side by side |",
            "|---|--:|--:|--:|--:|"] + moved + [
            "",
            "Series: {}{}".format(base, c["file"]),
            "Night: {}reports/{}.md".format(base, c["found"]),
            "Reproduce: install both commits as snapshots, then in hardwood-benchmarks `./run-regression.sh --profile nightly --only {} --rounds 2 BEFORE AFTER`.".format(
                c["script"])])
        title = "{}.{}: {} worse since {}".format(c["benchmark"].replace("Benchmark", ""), c["contender"],
                                                  ", ".join(worse_metrics(c)), c["commit"][:9])
        c["issue"] = github(token, "POST", "repos/{}/issues".format(args.repo),
                            {"title": title, "body": body, "labels": ["performance", "bug"]})["html_url"]
        print("issue: {}".format(c["issue"]))
        save(os.path.join(args.results, "changes.json"), changes)


def mute(args):
    """Marks every regression and unstable contender not alerted yet as found while alerts were
    off, so that turning them on opens issues only for what is found from then on."""
    changes = load(os.path.join(args.results, "changes.json"), [])
    for c in changes:
        if c["status"] in ("regression", "unstable") and "issue" not in c:
            c["issue"] = ""
    save(os.path.join(args.results, "changes.json"), changes)


HEALTH_TITLE = "Nightly regression job failing"


def health(args):
    """One issue stays open while nights fail, found by its title among the open issues."""
    token = token_of(args.token_file)
    query = urllib.parse.quote('repo:{} is:issue is:open in:title "{}"'.format(args.repo, HEALTH_TITLE))
    found = [i for i in github(token, "GET", "search/issues?q=" + query)["items"] if i["title"] == HEALTH_TITLE]
    issue = found[0] if found else None
    if args.failing:
        text = "Night of {}: {}".format(args.date, args.failing)
        if issue:
            github(token, "POST", "repos/{}/issues/{}/comments".format(args.repo, issue["number"]), {"body": text})
            print("health: commented on {}".format(issue["html_url"]))
        else:
            body = (text + "\n\nThe nightly regression job in hardwood-benchmarks recorded nothing or could not analyse "
                    "what it recorded. Each failing night adds a comment here; the next good night closes this issue. "
                    "Its log is under `~/nightly/runs/` on the machine.")
            made = github(token, "POST", "repos/{}/issues".format(args.repo),
                          {"title": HEALTH_TITLE, "body": body, "labels": ["performance"]})
            print("health: opened {}".format(made["html_url"]))
    elif issue:
        github(token, "POST", "repos/{}/issues/{}/comments".format(args.repo, issue["number"]),
               {"body": "Night of {}: recorded and analysed again.".format(args.date)})
        github(token, "PATCH", "repos/{}/issues/{}".format(args.repo, issue["number"]), {"state": "closed"})
        print("health: closed {}".format(issue["html_url"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("analyze")
    p.add_argument("results")
    p.add_argument("--date", required=True)
    p.add_argument("--summary")
    p.add_argument("--plan")
    p = sub.add_parser("releases")
    p.add_argument("clone")
    p = sub.add_parser("plan")
    p.add_argument("file")
    p.add_argument("n")
    p.add_argument("field", nargs="?")
    p = sub.add_parser("side-by-side")
    p.add_argument("results")
    p.add_argument("--plan", required=True)
    p.add_argument("--item", type=int, required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--before-version", required=True)
    p.add_argument("--after-version", required=True)
    p = sub.add_parser("report")
    p.add_argument("results")
    p.add_argument("--date", required=True)
    p.add_argument("--summary")
    p.add_argument("--out")
    p.add_argument("--status")
    p = sub.add_parser("page")
    p.add_argument("results")
    p.add_argument("--hardwood")
    p = sub.add_parser("issues")
    p.add_argument("results")
    p.add_argument("--repo", required=True)
    p.add_argument("--token-file", required=True)
    p = sub.add_parser("mute")
    p.add_argument("results")
    p = sub.add_parser("health")
    p.add_argument("--date", required=True)
    p.add_argument("--repo", required=True)
    p.add_argument("--token-file", required=True)
    state = p.add_mutually_exclusive_group(required=True)
    state.add_argument("--failing", metavar="REASON")
    state.add_argument("--ok", action="store_true")
    args = parser.parse_args()
    {"analyze": analyze, "releases": release_versions, "plan": plan_field, "side-by-side": side_by_side, "report": report, "page": page,
     "issues": issues, "mute": mute, "health": health}[args.command](args)


if __name__ == "__main__":
    main()
