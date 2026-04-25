#!/usr/bin/env python3
"""
Render NEB benchmark CSVs as paper-quality figures.

Input layout:
    build/benchmark/{scenario}-{mode}.csv       per-tick samples
    build/benchmark/{scenario}-{mode}.meta.json totals + wall time

Outputs (to the same directory):
    {scenario}_timeseries.png/.pdf   cumulative bytes vs tick, 3 modes overlaid
    {scenario}_throughput.png/.pdf   smoothed bytes/tick, 3 modes overlaid
    summary_bars.png/.pdf            total bytes + compression ratio per scenario
    summary.csv                      machine-readable summary (scenario,mode,total_bytes,ratio)

Call:
    python3 scripts/plot_benchmark.py [build/benchmark]
"""
from __future__ import annotations

import csv
import json
import math
import pathlib
import sys
from collections import defaultdict

try:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
except ImportError as exc:
    print(f"[plot] missing deps: {exc}. Install: pip install matplotlib numpy", file=sys.stderr)
    sys.exit(1)


MODE_STYLE = {
    "raw":  {"color": "#888888", "linestyle": "--", "marker": "s", "label": "Vanilla (uncompressed)"},
    "zlib": {"color": "#1f77b4", "linestyle": "-.", "marker": "o", "label": "Vanilla zlib"},
    "neb":  {"color": "#d62728", "linestyle": "-",  "marker": "^", "label": "NotEnoughBandwidth"},
}
MODES = ["raw", "zlib", "neb"]


def load_csv(path: pathlib.Path):
    ticks, db, bc, dp, pc = [], [], [], [], []
    with path.open() as f:
        for row in csv.reader(f):
            if not row or row[0].startswith("#") or row[0] == "tick":
                continue
            ticks.append(int(row[0]))
            db.append(int(row[1]))
            bc.append(int(row[2]))
            dp.append(int(row[3]))
            pc.append(int(row[4]))
    return np.array(ticks), np.array(db), np.array(bc), np.array(dp), np.array(pc)


def moving_average(x, window=40):
    if len(x) < window:
        return x.astype(float)
    kernel = np.ones(window) / window
    return np.convolve(x, kernel, mode="same")


def apply_paper_style():
    plt.rcParams.update({
        "figure.figsize": (7.0, 4.3),
        "figure.dpi": 140,
        "font.family": "DejaVu Serif",
        "font.size": 11,
        "axes.labelsize": 11,
        "axes.titlesize": 12,
        "axes.grid": True,
        "grid.alpha": 0.25,
        "grid.linewidth": 0.6,
        "legend.frameon": False,
        "legend.fontsize": 9,
        "lines.linewidth": 1.5,
        "savefig.bbox": "tight",
        "savefig.pad_inches": 0.05,
    })


def plot_timeseries(scenario: str, runs: dict, out_dir: pathlib.Path):
    fig, ax = plt.subplots()
    for mode in MODES:
        if mode not in runs:
            continue
        ticks, _, bc, *_ = runs[mode]
        style = MODE_STYLE[mode]
        ax.plot(ticks, bc / 1024.0 / 1024.0,
                color=style["color"], linestyle=style["linestyle"],
                label=style["label"])
    ax.set_xlabel("Server tick (20 ticks/s)")
    ax.set_ylabel("Cumulative S2C wire bytes (MiB)")
    ax.set_title(f"NEB bench — {scenario}: cumulative bandwidth")
    ax.legend(loc="upper left")
    base = out_dir / f"{scenario}_timeseries"
    fig.savefig(base.with_suffix(".png"))
    fig.savefig(base.with_suffix(".pdf"))
    plt.close(fig)


def plot_throughput(scenario: str, runs: dict, out_dir: pathlib.Path):
    fig, ax = plt.subplots()
    for mode in MODES:
        if mode not in runs:
            continue
        ticks, db, *_ = runs[mode]
        # Convert per-tick delta bytes to KiB/s (20 ticks/s * 1024 bytes/KiB).
        kibs = (db * 20.0) / 1024.0
        smooth = moving_average(kibs, window=40)
        style = MODE_STYLE[mode]
        # Scatter the raw samples lightly; overlay smoothed line for the trend.
        ax.scatter(ticks, kibs, s=5, alpha=0.18,
                   color=style["color"], edgecolors="none")
        ax.plot(ticks, smooth,
                color=style["color"], linestyle=style["linestyle"],
                label=style["label"])
    ax.set_xlabel("Server tick")
    ax.set_ylabel("Instantaneous S2C throughput (KiB/s, 2s smoothed)")
    ax.set_title(f"NEB bench — {scenario}: bandwidth over time")
    ax.legend(loc="upper right")
    base = out_dir / f"{scenario}_throughput"
    fig.savefig(base.with_suffix(".png"))
    fig.savefig(base.with_suffix(".pdf"))
    plt.close(fig)


def plot_summary(summary: list, out_dir: pathlib.Path):
    scenarios = sorted({row["scenario"] for row in summary})
    width = 0.26
    x = np.arange(len(scenarios))

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))

    # --- total bytes grouped bars ---
    ax = axes[0]
    for i, mode in enumerate(MODES):
        vals = []
        for s in scenarios:
            hit = [r for r in summary if r["scenario"] == s and r["mode"] == mode]
            vals.append(hit[0]["total_bytes"] / 1024.0 / 1024.0 if hit else 0)
        style = MODE_STYLE[mode]
        ax.bar(x + (i - 1) * width, vals, width,
               color=style["color"], label=style["label"],
               edgecolor="black", linewidth=0.5)
    ax.set_xticks(x)
    ax.set_xticklabels(scenarios)
    ax.set_ylabel("Total S2C bytes (MiB)")
    ax.set_title("Total bandwidth per scenario")
    ax.legend(loc="upper left")

    # --- compression ratio vs raw ---
    ax = axes[1]
    ratios = defaultdict(dict)
    for row in summary:
        ratios[row["scenario"]][row["mode"]] = row["total_bytes"]
    for i, mode in enumerate(["zlib", "neb"]):
        vals = []
        for s in scenarios:
            raw = ratios[s].get("raw", 0)
            v = ratios[s].get(mode, 0)
            vals.append((v / raw) if raw > 0 else float("nan"))
        style = MODE_STYLE[mode]
        ax.bar(x + (i - 0.5) * width, vals, width,
               color=style["color"], label=style["label"],
               edgecolor="black", linewidth=0.5)
    ax.axhline(1.0, color="#444", linewidth=0.7, linestyle=":")
    ax.set_xticks(x)
    ax.set_xticklabels(scenarios)
    ax.set_ylabel("Bytes / raw bytes  (lower = better)")
    ax.set_title("Compression ratio vs uncompressed")
    ax.set_ylim(0, 1.1)
    ax.legend(loc="upper right")

    base = out_dir / "summary_bars"
    fig.savefig(base.with_suffix(".png"))
    fig.savefig(base.with_suffix(".pdf"))
    plt.close(fig)


def write_summary_csv(summary: list, out_dir: pathlib.Path):
    by_scenario = defaultdict(dict)
    for row in summary:
        by_scenario[row["scenario"]][row["mode"]] = row["total_bytes"]
    with (out_dir / "summary.csv").open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["scenario", "mode", "total_bytes", "ratio_vs_raw", "wall_ms"])
        for row in summary:
            raw = by_scenario[row["scenario"]].get("raw", 0)
            ratio = (row["total_bytes"] / raw) if raw > 0 else ""
            w.writerow([row["scenario"], row["mode"], row["total_bytes"],
                        f"{ratio:.4f}" if isinstance(ratio, float) and not math.isnan(ratio) else "",
                        row["wall_ms"]])


def main():
    in_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build/benchmark").resolve()
    if not in_dir.is_dir():
        print(f"[plot] no dir: {in_dir}", file=sys.stderr)
        sys.exit(1)
    csvs = sorted(in_dir.glob("*.csv"))
    csvs = [p for p in csvs if p.name != "summary.csv"]
    if not csvs:
        print(f"[plot] no benchmark CSVs in {in_dir}", file=sys.stderr)
        sys.exit(1)

    apply_paper_style()
    runs_by_scenario: dict = defaultdict(dict)
    summary: list = []
    for csv_path in csvs:
        stem = csv_path.stem  # e.g. "roam-neb"
        if "-" not in stem:
            continue
        scenario, mode = stem.split("-", 1)
        data = load_csv(csv_path)
        runs_by_scenario[scenario][mode] = data
        meta_path = csv_path.with_suffix(".meta.json")
        if meta_path.exists():
            meta = json.loads(meta_path.read_text())
            summary.append({
                "scenario": scenario,
                "mode": mode,
                "total_bytes": int(meta.get("total_bytes", 0)),
                "wall_ms": int(meta.get("wall_ms", 0)),
            })

    for scenario, runs in runs_by_scenario.items():
        plot_timeseries(scenario, runs, in_dir)
        plot_throughput(scenario, runs, in_dir)
    if summary:
        plot_summary(summary, in_dir)
        write_summary_csv(summary, in_dir)
    print(f"[plot] wrote figures + summary to {in_dir}")


if __name__ == "__main__":
    main()
