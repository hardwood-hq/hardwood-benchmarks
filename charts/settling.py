"""Whether a regression run's contenders reached steady state before they were measured.

A contender is still warming in a run when its measurement iterations, as the run's JMH log
prints them, fell one after another by more than 5 % in all: its preset's warmup ended before
it settled, so its time is not steady state and no verdict may rest on it.
"""
import os
import re

DROP = 0.05


def still_warming(logs):
    """The (benchmark, contender) pairs still warming in any of the JMH logs `logs`; a
    contender carries its parameters as `[value]` suffixes, as the results files name it."""
    found = set()
    for log in logs:
        if not os.path.exists(log):
            continue
        with open(log, errors="replace") as f:
            text = f.read()
        for block in text.split("# Benchmark: ")[1:]:
            benchmark, contender = block.split("\n", 1)[0].strip().rsplit(".", 2)[-2:]
            params = re.search(r"# Parameters: \((.*)\)", block)
            if params:
                contender += "".join("[{}]".format(p.split(" = ", 1)[1]) for p in params.group(1).split(", "))
            body = block.split("\nResult ")[0]
            times = [float(t) for t in re.findall(r"^Iteration\s+\d+:.*?([\d.]+) ms/op\s*$", body, re.M)]
            falling = len(times) > 1 and all(b < a for a, b in zip(times, times[1:]))
            if falling and times[0] / times[-1] - 1 > DROP:
                found.add((benchmark, contender))
    return found


def run_logs(run_dir):
    """The script logs run-regression.sh keeps in one run directory."""
    return [os.path.join(run_dir, f) for f in sorted(os.listdir(run_dir)) if f.endswith(".log")]
