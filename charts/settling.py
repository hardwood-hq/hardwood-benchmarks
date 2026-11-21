"""Whether a regression run's contenders reached steady state, and whether their forks agree.

RegressionProfiler appends one JSON line per iteration and fork to a run's
bench-metrics-<Benchmark>.jsonl. From the measurement iterations:

- a contender is "not settled" when its measurement iterations are faster than its last
  warmup iterations by more than 3 % and more than twice the noise between successive
  iterations, in the median fork, and faster in at least two forks: its preset's warmup
  ended before it reached steady state, so its time supports no verdict. The noise is taken
  from successive differences, which a slow trend barely moves, so jitter alone does not
  make a contender unsettled;
- it is "bimodal" when its forks' means split into groups further apart than 5 % of their
  median and three times the spread within a fork: the JVMs took different JIT decisions
  (a deoptimization, say), so a median between them describes no run.
"""
import glob
import json
import os
import statistics

DROP = 0.03
DROP_OVER_NOISE = 2.0
TAIL = 3
GAP = 0.05
GAP_OVER_NOISE = 3.0


def contender_name(benchmark, params):
    """A contender as the results files name it: the method, then each parameter's value as a
    `[value]` suffix, in the order of the parameters' names."""
    name = benchmark.rsplit(".", 1)[-1]
    return name + "".join("[{}]".format(params[k]) for k in sorted(params))


def iterations(run_dir):
    """{(benchmark class, contender): {fork: {"warmup": [scores], "measurement": [scores]}}}
    of one run, in iteration order."""
    found = {}
    for path in sorted(glob.glob(os.path.join(run_dir, "bench-metrics-*.jsonl"))):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                if record["score"] is None:
                    continue
                cls = record["benchmark"].rsplit(".", 2)[-2]
                key = (cls, contender_name(record["benchmark"], record["params"]))
                phases = found.setdefault(key, {}).setdefault(record["fork"], {"warmup": [], "measurement": []})
                phases[record["phase"]].append(record["score"])
    return found


def forks(run_dir):
    """{(benchmark class, contender): {fork: [measurement scores in order]}} of one run."""
    return {key: {fork: p["measurement"] for fork, p in by_fork.items() if p["measurement"]}
            for key, by_fork in iterations(run_dir).items()}


def fork_means(run_dirs):
    """{(benchmark class, contender): [each fork's mean score]} over the runs `run_dirs`."""
    means = {}
    for run in run_dirs:
        for key, by_fork in forks(run).items():
            means.setdefault(key, []).extend(statistics.mean(s) for s in by_fork.values())
    return means


def noise(scores):
    """The relative noise between successive scores, from their differences."""
    diffs = [b - a for a, b in zip(scores, scores[1:])]
    if not diffs:
        return 0.0
    return (sum(d * d for d in diffs) / len(diffs) / 2) ** 0.5 / statistics.mean(scores)


def verdict(by_fork):
    """"not settled", "bimodal" or None for one contender's
    {fork: {"warmup": [scores], "measurement": [scores]}} of one run."""
    drops, noises = [], []
    for p in by_fork.values():
        tail, measured = p["warmup"][-TAIL:], p["measurement"]
        if tail and measured:
            drops.append(statistics.mean(tail) / statistics.mean(measured) - 1)
            noises.append(noise(tail + measured))
    if drops:
        drop, jitter = statistics.median(drops), statistics.median(noises)
        if drop > max(DROP, DROP_OVER_NOISE * jitter) and sum(d > 0 for d in drops) >= min(2, len(drops)):
            return "not settled"
    measured = {fork: p["measurement"] for fork, p in by_fork.items() if p["measurement"]}
    means = sorted(statistics.mean(s) for s in measured.values())
    if len(means) >= 2:
        median = statistics.median(means)
        gap = max(b - a for a, b in zip(means, means[1:]))
        spread = max((max(s) - min(s)) / 2 for s in measured.values())
        if gap > GAP * median and gap > GAP_OVER_NOISE * spread:
            return "bimodal"
    return None


def verdicts(run_dirs):
    """{(benchmark class, contender): "not settled" | "bimodal"} for a contender that was
    either in any of the runs `run_dirs`; "not settled" outranks "bimodal"."""
    found = {}
    for run in run_dirs:
        for key, by_fork in iterations(run).items():
            v = verdict(by_fork)
            if v and found.get(key) != "not settled":
                found[key] = v
    return found
