#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Summarize repeated operation-mean times, not per-message latency percentiles."""
import json
from pathlib import Path
import statistics
import sys

root = Path(sys.argv[1])
rows = []
for mode in ("interrupt", "poll"):
    observations = [json.loads(line) for line in (root / f"{mode}-bench/probe.jsonl").read_text().splitlines()
                    if line.startswith("{")]
    groups = {}
    for item in observations:
        if item["test"] not in ("rx_microbench", "tx_microbench"):
            continue
        key = item["test"], item["bytes"], item["path"]
        groups.setdefault(key, []).append(item["elapsed_ns"] / item["operations"])
    for (test, size, path), values in groups.items():
        if len(values) != 3:
            raise RuntimeError(f"Expected three measurements: {mode}/{test}/{size}/{path}")
        rows.append(dict(mode=mode, test=test, bytes=size, path=path, repetitions=3,
                         median_operation_mean_ns=statistics.median(values),
                         min_operation_mean_ns=min(values), max_operation_mean_ns=max(values)))
print(json.dumps({"scope": "local VCL cut-through microbenchmark; not Netty or real-network performance",
                  "rows": rows}, indent=2))
