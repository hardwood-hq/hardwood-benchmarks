#!/usr/bin/env python3
"""Synthesize a per-point-median benchmark snapshot from several repeat runs.

Given N run directories (each a self-contained snapshot with a
`bench-throughput-<Benchmark>.tsv` and its `bench-meta-<Benchmark>.tsv`), this
writes a new snapshot whose every value is the **median across the runs at that
exact contender/width**. The chart generators then render it like any other run
directory, so the published chart is the median of the repeats rather than a
single hand-picked run.

Why per-point median (not "pick the median run"): each contender/width is an
independent measurement, so there is no within-run correlation to preserve —
taking the median at every point is the robust estimate and cannot be accused of
cherry-picking a flattering run. It matters here because the reconstruction
baseline swings run-to-run (~30% at large n) while the fast path and the flat
floor are stable; the median settles the noisy baseline without touching the rest.

The meta sidecar is copied verbatim from the first run (values / codec / host /
build are identical across repeats).

Usage:
    # median of three runs -> results/<pub>/median/
    python charts/median-runs.py results/<pub>/median \
        results/<pub>/run-1 results/<pub>/run-2 results/<pub>/run-3

    # then render as usual:
    python charts/make-fixedlist-chart.py --results-dir results/<pub>/median

Stdlib only; no third-party dependencies.
"""
import glob
import os
import shutil
import statistics
import sys


def median_snapshot(out_dir, run_dirs):
    if len(run_dirs) < 2:
        sys.exit("median-runs: need at least two run directories to take a median")

    # Discover the throughput TSV(s) from the first run; a publication may hold
    # several benchmarks (e.g. flat + filter), so median each one it finds.
    throughput_tsvs = sorted(
        os.path.basename(p)
        for p in glob.glob(os.path.join(run_dirs[0], "bench-throughput-*.tsv")))
    if not throughput_tsvs:
        sys.exit("median-runs: no bench-throughput-*.tsv in " + run_dirs[0])

    os.makedirs(out_dir, exist_ok=True)
    for tsv_name in throughput_tsvs:
        # (pass, contender) -> [ms across runs]; header/order taken from run-1.
        samples = {}
        order = []
        header = "pass\tbenchmark\tcontender\tms_per_op\tm_rows_per_s\tmb_per_s\n"
        benchmark = ""
        for run in run_dirs:
            path = os.path.join(run, tsv_name)
            with open(path) as f:
                for line in f:
                    cols = line.rstrip("\n").split("\t")
                    if len(cols) < 4 or cols[0] == "pass":
                        continue
                    key = (cols[0], cols[2])
                    benchmark = cols[1]
                    if key not in samples:
                        samples[key] = []
                        order.append(key)
                    samples[key].append(float(cols[3]))

        out_tsv = os.path.join(out_dir, tsv_name)
        with open(out_tsv, "w") as f:
            f.write(header)
            for key in order:
                pass_, contender = key
                med = statistics.median(samples[key])
                f.write("{}\t{}\t{}\t{:.3f}\t-\t-\n".format(pass_, benchmark, contender, med))

        # Copy the matching meta sidecar (identical across runs).
        meta_name = tsv_name.replace("bench-throughput-", "bench-meta-")
        meta_src = os.path.join(run_dirs[0], meta_name)
        if os.path.exists(meta_src):
            shutil.copy(meta_src, os.path.join(out_dir, meta_name))

        print("wrote {}  (median of {} runs, {} contenders)".format(
            out_tsv, len(run_dirs), len(order)))


def main():
    if len(sys.argv) < 4:
        sys.exit("usage: median-runs.py OUT_DIR RUN_DIR RUN_DIR [RUN_DIR ...]")
    median_snapshot(sys.argv[1], sys.argv[2:])


if __name__ == "__main__":
    main()
