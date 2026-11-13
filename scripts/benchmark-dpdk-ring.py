#!/usr/bin/env python3
"""Measure a testpmd ring loop. Packet rates are not TCP application rates."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import select
import subprocess
import time


def cpu_seconds(pid):
    fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')


def cgroup_cpu():
    return dict((key, int(value)) for key, value in
                (line.split() for line in Path('/sys/fs/cgroup/cpu.stat').read_text().splitlines()))


class Testpmd:
    def __init__(self, args, log):
        self.log = log.open('wb')
        self.process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, bufsize=0)
        self.pending = b''

    def prompt(self, timeout=30):
        deadline = time.monotonic() + timeout
        while b'testpmd> ' not in self.pending:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('testpmd did not return a prompt')
            if not select.select([self.process.stdout], [], [], remaining)[0]:
                continue
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise RuntimeError('testpmd exited; inspect the raw log')
            self.log.write(chunk)
            self.log.flush()
            self.pending += chunk
        response, self.pending = self.pending.split(b'testpmd> ', 1)
        return response.decode(errors='replace')

    def command(self, value):
        self.process.stdin.write((value + '\n').encode())
        return self.prompt()

    def close(self):
        try:
            if self.process.poll() is None:
                try:
                    self.process.stdin.write(b'quit\n')
                    tail, _ = self.process.communicate(timeout=10)
                    self.log.write(tail)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    self.process.kill()
                    tail, _ = self.process.communicate()
                    self.log.write(tail)
            if self.process.returncode:
                raise RuntimeError(f'testpmd exit code {self.process.returncode}')
        finally:
            self.log.close()
            if self.process.stdin and not self.process.stdin.closed:
                self.process.stdin.close()
            self.process.stdout.close()


def counters(text):
    names = ('RX-packets', 'TX-packets', 'RX-errors', 'TX-errors', 'RX-missed', 'RX-nombuf')
    result = {}
    for name in names:
        match = re.search(r'\b' + name + r':\s*(\d+)', text)
        if match is None:
            raise RuntimeError(f'Missing counter {name}: {text}')
        result[name] = int(match[1])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', default='0-1')
    parser.add_argument('--warmup', type=float, default=5)
    parser.add_argument('--seconds', type=float, default=10)
    parser.add_argument('--sizes', nargs='+', type=int, default=[64, 1024])
    options = parser.parse_args()
    if options.warmup <= 0 or options.seconds <= 0 or any(s < 64 or s > 1518 for s in options.sizes):
        parser.error('positive timing and packet sizes in [64, 1518] required')
    options.output.mkdir(parents=True, exist_ok=True)
    if (options.output / 'environment.json').exists():
        parser.error('choose a fresh output directory')
    # The cmdline prompt uses write(), while port statistics use printf().
    # Disable stdio buffering so the prompt follows its command's statistics.
    args = ['stdbuf', '-o0', '-e0', str(options.binary.resolve()), '-l', options.cpus, '-m', '256', '--no-huge', '--no-pci',
            '--no-shconf', '--no-telemetry', '--vdev=net_ring0', '--', '--port-topology=loop',
            '--total-num-mbufs=2048', '--nb-cores=1', '--forward-mode=io', '-i']
    environment = {
        'date_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'uname': list(os.uname()), 'cpu_max': Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
        'allowed_cpus': sorted(os.sched_getaffinity(0)), 'command': args,
        'binary_sha256': hashlib.sha256(options.binary.read_bytes()).hexdigest(),
        'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'options': {k: str(v) if isinstance(v, Path) else v for k, v in vars(options).items()},
        'path': 'testpmd io forwarding -> net_ring0 -> same in-process rte_ring -> testpmd',
        'scope': 'mbuf pointer recirculation; no TCP, VPP, VCL, JVM, kernel networking or NIC',
        'hugepages': [line for line in Path('/proc/meminfo').read_text().splitlines()
                      if line.startswith(('HugePages_', 'Hugepagesize', 'Hugetlb'))],
    }
    (options.output / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    samples = []
    for size in options.sizes:
        print(f'RUN ring packet_bytes={size}', flush=True)
        client = Testpmd(args, options.output / f'testpmd-{size}.log')
        try:
            client.prompt()
            (options.output / f'port-info-{size}.txt').write_text(client.command('show port info all'))
            client.command(f'set txpkts {size}')
            client.command('start tx_first')
            (options.output / f'threads-{size}.json').write_text(json.dumps([
                {'tid': int(p.name), 'comm': (p / 'comm').read_text().strip(),
                 'allowed_cpus': sorted(os.sched_getaffinity(int(p.name)))}
                for p in Path(f'/proc/{client.process.pid}/task').iterdir()], indent=2) + '\n')
            time.sleep(options.warmup)
            start_text = client.command('show port stats all')
            start_count = counters(start_text)
            start_time = time.monotonic()
            start_cpu = cpu_seconds(client.process.pid)
            start_cgroup = cgroup_cpu()
            time.sleep(options.seconds)
            end_text = client.command('show port stats all')
            elapsed = time.monotonic() - start_time
            end_cpu = cpu_seconds(client.process.pid)
            end_cgroup = cgroup_cpu()
            end_count = counters(end_text)
            delta = {k: end_count[k] - start_count[k] for k in end_count}
            if min(delta['RX-packets'], delta['TX-packets']) <= 0:
                raise RuntimeError('ring forwarding did not move packets')
            if any(delta[k] for k in ('RX-errors', 'TX-errors', 'RX-missed', 'RX-nombuf')):
                raise RuntimeError(f'Errors during the measurement: {delta}')
            row = {'packet_bytes': size, 'elapsed_seconds': elapsed, 'counters_delta': delta,
                   'rx_packets_per_second': delta['RX-packets'] / elapsed,
                   'tx_packets_per_second': delta['TX-packets'] / elapsed,
                   'process_cpu_seconds': end_cpu - start_cpu,
                   'process_cpu_cores': (end_cpu - start_cpu) / elapsed,
                   'cgroup_delta': {k: end_cgroup[k] - start_cgroup.get(k, 0) for k in end_cgroup}}
            samples.append(row)
            with (options.output / 'raw.jsonl').open('a') as f:
                f.write(json.dumps(row) + '\n')
            client.command('stop')
            print(f"RESULT {size}B: RX {row['rx_packets_per_second'] / 1e6:.3f} Mpps, "
                  f"TX {row['tx_packets_per_second'] / 1e6:.3f} Mpps, CPU {row['process_cpu_cores']:.3f}", flush=True)
        finally:
            client.close()
    (options.output / 'results.json').write_text(json.dumps({'environment': environment, 'samples': samples}, indent=2) + '\n')


if __name__ == '__main__':
    main()
