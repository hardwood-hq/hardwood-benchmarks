#!/usr/bin/env python3
"""The analysis steps of nightly.sh, on a checkout of hardwood-benchmark-results.

  nightly.py analyze RESULTS --summary FILE --plan FILE
      Adds the night the summary records to nights.csv, the commits the nightly job measured
      as its own. Runs Otava over every settled series of the summary's machine epoch, adds the change
      points it finds to changes.json, and writes the confirmation runs the new regressions
      need to --plan: per script, the commit before the change and the commit after it.
  nightly.py plan FILE N FIELD
      Prints field FIELD (script, before or after) of the plan's run N, or with N "count"
      the number of runs.
  nightly.py confirm RESULTS --plan FILE --item N --out DIR --before-version V --after-version V
      Decides the change points of the plan's run N from DIR, a run-regression.sh run of the
      commit before (version V) and the commit after: confirmed when every fork of the commit after is worse
      than every fork of the commit before, by at least half the change Otava found.
  nightly.py report RESULTS --date DATE [--summary FILE --out DIR] [--status TEXT]
      Writes reports/DATE.md and reports/latest.md: what ran, against the median of each
      series' last nights, and the change points found, or why the night recorded nothing.
  nightly.py page RESULTS [--hardwood CLONE]
      Writes history.json, which index.html, the history page, reads; with a Hardwood clone, it
      lists the clone's release tags, so the page marks a release whether or not it was measured.
  nightly.py issues RESULTS --repo OWNER/NAME --token-file FILE
      Opens an issue for each confirmed regression that has none, and records its URL.

A change point is one series' change at one commit. changes.json keeps every one, so that a
change point Otava finds again, or finds a row or two away as rows arrive, is not handled
twice. Only a regression whose commit after a night of the last RECENT_DAYS days measured as
its own is confirmed; others, as backfilled versions bring them, are kept as history.
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
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import settling  # noqa: E402

METRICS = {"wall_ms": "score", "cpu_ms": "cpuMs", "alloc_bytes": "allocBytes", "live_bytes": "liveBytes"}
UNITS = {"wall_ms": "ms", "cpu_ms": "ms", "alloc_bytes": "B", "live_bytes": "B"}
# The caller share from which the report marks a contender as bound by its caller's thread.
CALLER_BOUND = 0.8
RECENT_DAYS = 7
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


def otava(results, tests):
    """Otava's change points of `tests`, {test: [change point]}."""
    found = {}
    names = list(tests)
    for i in range(0, len(names), 50):
        done = subprocess.run([os.environ.get("OTAVA", "otava"), "analyze", "--config-file", "otava.yaml",
                               "--output", "json"] + names[i:i + 50],
                              cwd=results, capture_output=True, text=True)
        if done.returncode != 0:
            raise SystemExit("nightly: otava failed:\n" + done.stderr[-2000:])
        # One JSON object per test, a line each.
        for line in done.stdout.splitlines():
            if line.strip().startswith("{"):
                found.update(json.loads(line))
    return found


def analyze(args):
    summary = load(args.summary, None)
    nights_path = os.path.join(args.results, "nights.csv")
    nights = rows_of(nights_path)
    if not any(n["run"] == summary["run"] for n in nights):
        nights.append({"date": summary["run"][:10], "commit": summary["commit"], "version": summary["version"],
                       "run": summary["run"]})
        with open(nights_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["date", "commit", "version", "run"])
            writer.writeheader()
            writer.writerows(nights)
    tests = {t: f for t, f in tests_of(args.results).items() if f.startswith("series/{}/".format(summary["machine"]))}
    changes = load(os.path.join(args.results, "changes.json"), [])
    now = datetime.datetime.now(datetime.timezone.utc)
    recent_nights = {n["commit"] for n in nights
                     if now.date() - datetime.date.fromisoformat(n["date"]) <= datetime.timedelta(days=RECENT_DAYS)}
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
            worse = {c["metric"]: float(c["forward_change_percent"]) for c in point["changes"]
                     if float(c["forward_change_percent"]) > 0}
            kind = "regression" if worse else "improvement"
            known = handled(changes, tests[test], commits, index, kind)
            if known is not None:
                continue
            meta = series_meta(args.results, tests[test])
            recent = after in recent_nights
            change = dict(meta, file=tests[test], test=test, kind=kind, commit=after, version=rows[index]["version"],
                          before_commit=rows[index - 1]["commit"], before_version=rows[index - 1]["version"],
                          found=now.strftime("%Y-%m-%d"),
                          metrics={c["metric"]: {"percent": float(c["forward_change_percent"]),
                                                 "before": float(c["mean_before"]), "after": float(c["mean_after"]),
                                                 "pvalue": float(c["pvalue"])} for c in point["changes"]})
            if kind == "improvement":
                change["status"] = "improvement"
            elif not recent:
                change["status"] = "history"
            else:
                change["status"] = "pending"
            change["id"] = "{}@{}".format(test, after[:9])
            changes.append(change)
    for change in changes:
        if change["status"] == "pending":
            key = "{} {} {}".format(change["script"], change["before_commit"], change["commit"])
            plan.setdefault(key, {"script": change["script"], "before": change["before_commit"],
                                  "after": change["commit"], "changes": []})["changes"].append(change["id"])
    save(os.path.join(args.results, "changes.json"), changes)
    save(args.plan, list(plan.values()))
    print("{} change points pending confirmation, in {} runs".format(
        sum(len(p["changes"]) for p in plan.values()), len(plan)))


def handled(changes, file, commits, index, kind):
    """The change point of changes.json that this one is: the same series and kind, at the
    same commit or one up to SHIFT_ROWS rows away."""
    for change in changes:
        if change["file"] != file or change["kind"] != kind or change["commit"] not in commits:
            continue
        if abs(commits.index(change["commit"]) - index) <= SHIFT_ROWS:
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


def confirm(args):
    changes = load(os.path.join(args.results, "changes.json"), [])
    by_id = {c["id"]: c for c in changes}
    for item in load(args.plan, [])[args.item:args.item + 1]:
        before = sorted(glob.glob(os.path.join(args.out, args.before_version, "run-*")))
        after = sorted(glob.glob(os.path.join(args.out, args.after_version, "run-*")))
        for cid in item["changes"]:
            change = by_id[cid]
            verdicts = {}
            for metric, found in change["metrics"].items():
                if found["percent"] <= 0:
                    continue
                b = fork_values(before, change["benchmark"], change["contender"], METRICS[metric])
                a = fork_values(after, change["benchmark"], change["contender"], METRICS[metric])
                if len(a) < 2 or len(b) < 2:
                    verdicts[metric] = {"verdict": "not measured"}
                    continue
                percent = (statistics.median(a) / statistics.median(b) - 1) * 100
                held = min(a) > max(b) and percent >= found["percent"] / 2
                verdicts[metric] = {"verdict": "confirmed" if held else "refuted", "percent": round(percent, 2),
                                    "before_forks": [round(v, 4) for v in b], "after_forks": [round(v, 4) for v in a]}
            change["confirmation"] = {"date": datetime.date.today().isoformat(), "metrics": verdicts}
            if any(v["verdict"] == "confirmed" for v in verdicts.values()):
                change["status"] = "confirmed"
            elif all(v["verdict"] == "not measured" for v in verdicts.values()):
                change["status"] = "unmeasured"
            else:
                change["status"] = "refuted"
            print("{}: {}".format(cid, change["status"]))
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
    shown = [c for c in changes if c["status"] in ("pending", "confirmed", "refuted", "unmeasured")
             and (c["found"] == args.date or c.get("confirmation", {}).get("date") == args.date
                  or c["status"] == "pending")]
    lines += ["", "## Change points", ""]
    if not shown:
        lines.append("No new regression.")
    for c in shown:
        moved = ", ".join("{} {:+.1f} %".format(m, v["percent"]) for m, v in c["metrics"].items() if v["percent"] > 0)
        link = " ({})".format(c["issue"]) if c.get("issue") else ""
        lines.append("- **{}** {}.{}: {} from {} to {}{}".format(c["status"], c["benchmark"], c["contender"], moved,
                                                                 c["before_commit"][:9], c["commit"][:9], link))
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
    its fork point is the code the nights measured. A release whose fork point an earlier
    release already has, such as a maintenance release of it, marks nothing new and is left out.
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
    out, bases = [], set()
    for tagged, version, commit in sorted(tags, key=lambda t: instant(t[0])):
        # No merge base: a tag of unrelated history.
        base = subprocess.run(["git", "-C", clone, "merge-base", commit, "origin/main"], capture_output=True, text=True).stdout.strip()
        if not base or base in bases:
            continue
        bases.add(base)
        out.append({"version": version, "commit": commit, "base": base, "time": git("show", "-s", "--format=%cI", base)})
    return out


def page(args):
    series = []
    for path in sorted(glob.glob(os.path.join(args.results, "series", "*", "*", "*.csv"))):
        if path.endswith(".unsettled.csv"):
            continue
        rel = os.path.relpath(path, args.results)
        meta = series_meta(args.results, rel)
        unsettled = rel[:-len(".csv")] + ".unsettled.csv"
        meta.update(file=rel, unsettled=unsettled if os.path.exists(os.path.join(args.results, unsettled)) else None)
        series.append(meta)
    for path in sorted(glob.glob(os.path.join(args.results, "series", "*", "*", "*.unsettled.csv"))):
        rel = os.path.relpath(path, args.results)
        settled = rel[:-len(".unsettled.csv")] + ".csv"
        if not os.path.exists(os.path.join(args.results, settled)):
            meta = series_meta(args.results, settled)
            meta.update(file=None, unsettled=rel)
            series.append(meta)
    # Only machine epochs that hold series: one whose series moved on keeps its file, for the
    # reports that link it.
    held = {s["machine"] for s in series}
    machines = {os.path.basename(p)[:-5]: load(p, {}) for p in glob.glob(os.path.join(args.results, "machines", "*.json"))
                if os.path.basename(p)[:-5] in held}
    changes = [c for c in load(os.path.join(args.results, "changes.json"), []) if c["status"] != "refuted"]
    save(os.path.join(args.results, "history.json"),
         {"generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
          "series": series, "machines": machines, "changes": changes,
          "releases": releases(args.hardwood) if args.hardwood else []})
    shutil.copy(os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates", "history", "index.html"),
                os.path.join(args.results, "index.html"))
    print("page: {} series".format(len(series)))


def confirmed_metrics(change):
    names = {"wall_ms": "wall time", "cpu_ms": "CPU time", "alloc_bytes": "allocation", "live_bytes": "heap after collection"}
    return [names[m] for m, v in change["confirmation"]["metrics"].items() if v.get("verdict") == "confirmed"]


def issues(args):
    with open(args.token_file) as f:
        token = f.read().strip()
    changes = load(os.path.join(args.results, "changes.json"), [])
    for c in changes:
        if c["status"] != "confirmed" or c.get("issue"):
            continue
        moved = []
        for m, v in c["metrics"].items():
            confirmed = c["confirmation"]["metrics"].get(m, {})
            if confirmed.get("verdict") == "confirmed":
                moved.append("| {} | {} | {} | {:+.1f} % | {:+.1f} % |".format(
                    m, number(v["before"], UNITS[m]), number(v["after"], UNITS[m]), v["percent"], confirmed["percent"]))
        base = "https://github.com/hardwood-hq/hardwood-benchmark-results/blob/main/"
        body = "\n".join([
            "The nightly regression run found {}.{} worse between {} and {} in {}, and a run of both commits side by side confirmed it.".format(
                c["benchmark"], c["contender"], c["before_commit"][:9], c["commit"][:9], ", ".join(confirmed_metrics(c))),
            "",
            "Commits: https://github.com/hardwood-hq/hardwood/compare/{}...{}".format(c["before_commit"], c["commit"]),
            "",
            "| Metric | Before | After | Change in the history | Change side by side |",
            "|---|--:|--:|--:|--:|"] + moved + [
            "",
            "Series: {}{}".format(base, c["file"]),
            "Night: {}reports/{}.md".format(base, c["found"]),
            "Reproduce: install both commits as snapshots, then in hardwood-benchmarks `./run-regression.sh --profile nightly --only {} --rounds 2 BEFORE AFTER`.".format(
                c["script"])])
        title = "{}.{}: {} worse since {}".format(c["benchmark"].replace("Benchmark", ""), c["contender"],
                                                  ", ".join(confirmed_metrics(c)), c["commit"][:9])
        request = urllib.request.Request(
            "https://api.github.com/repos/{}/issues".format(args.repo), method="POST",
            data=json.dumps({"title": title, "body": body, "labels": ["performance", "bug"]}).encode(),
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(request) as response:
            c["issue"] = json.load(response)["html_url"]
        print("issue: {}".format(c["issue"]))
        save(os.path.join(args.results, "changes.json"), changes)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("analyze")
    p.add_argument("results")
    p.add_argument("--summary", required=True)
    p.add_argument("--plan", required=True)
    p = sub.add_parser("plan")
    p.add_argument("file")
    p.add_argument("n")
    p.add_argument("field", nargs="?")
    p = sub.add_parser("confirm")
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
    args = parser.parse_args()
    {"analyze": analyze, "plan": plan_field, "confirm": confirm, "report": report, "page": page, "issues": issues}[args.command](args)


if __name__ == "__main__":
    main()
