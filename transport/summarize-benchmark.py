#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Summarize successful benchmark repetitions without pooling or hiding dropped arrivals."""
import argparse
import csv
from pathlib import Path
from statistics import median


def summarize(directory):
    print('| Backend | 场景 | msg/s | MiB/s | p50 µs | p99 µs | p99.9 µs | CPU ms/千条 | 丢弃到达数 |')
    print('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    for mode in ['epoll', 'interrupt', 'poll']:
        with (directory / mode / 'benchmark.csv').open() as stream:
            rows = list(csv.DictReader(stream))
        for row in rows:
            if int(row['received']) + int(row['dropped']) != int(row['attempted']) or int(row['errors']):
                raise ValueError('Incomplete/error run: inspect raw results')
        for name in dict.fromkeys(row['scenario'] for row in rows):
            samples = [row for row in rows if row['scenario'] == name]
            def med(column): return median(float(row[column]) for row in samples)
            cpu = median(sum(float(row[column]) for column in ['client_cpu_ns', 'peer_cpu_ns', 'vpp_cpu_ns'])
                         / int(row['received']) / 1000 for row in samples)
            dropped = sum(int(row['dropped']) for row in samples)
            print(f"| {mode} | {name} | {med('messages_per_second'):,.0f} | {med('payload_bytes_per_second') / 2**20:,.2f} | "
                  f"{med('p50_ns') / 1000:.1f} | {med('p99_ns') / 1000:.1f} | {med('p999_ns') / 1000:.1f} | {cpu:.2f} | {dropped} |")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    summarize(parser.parse_args().directory)
