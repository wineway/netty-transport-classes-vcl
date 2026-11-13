#!/usr/bin/env python3
"""Run epoll/VCL echo measurements with explicit CPU placement across net namespaces."""
import argparse
import csv
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path
import queue
import select
import signal
import socket
import struct
import statistics
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = [('window16', 1024, 32, 16), ('window128', 1024, 32, 128)]


def disable_offloads(interface):
    """Legacy ethtool ioctl, confined to the disposable veth. No extra package needed."""
    states = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        for name, get, set_ in [('tso', 0x1e, 0x1f), ('gso', 0x23, 0x24), ('gro', 0x2b, 0x2c), ('tx-checksum', 0x16, 0x17)]:
            value = (ctypes.c_uint32 * 2)(set_, 0)
            request = struct.pack('16sP', interface.encode(), ctypes.addressof(value)).ljust(40, b'\0')
            fcntl.ioctl(sock, 0x8946, request)
            value[0] = get
            fcntl.ioctl(sock, 0x8946, request)
            states[name] = value[1]
            if value[1] != 0: raise RuntimeError(f'{interface} {name} remains enabled')
    return states


def run(args, **kwargs):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {args}\n{result.stdout}{result.stderr}')
    return result.stdout


def cpu_seconds(pid):
    fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')


def cgroup_cpu():
    p = Path('/sys/fs/cgroup/cpu.stat')
    return dict((key, int(value)) for key, value in (line.split() for line in p.read_text().splitlines())) if p.exists() else {}


class Process:
    def __init__(self, args, log, env=None):
        self.lines = queue.Queue()
        self.log = open(log, 'w')
        self.process = subprocess.Popen([str(a) for a in args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, bufsize=1, env=env)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    @property
    def pid(self):
        return self.process.pid

    def _read(self):
        for line in self.process.stdout:
            self.log.write(line); self.log.flush(); self.lines.put(line.rstrip())
        self.lines.put(None)

    def line(self, timeout=30):
        line = self.lines.get(timeout=timeout)
        if line is None:
            raise RuntimeError(f'Process {self.pid} exited before expected output; see {self.log.name}')
        return line

    def wait_for(self, expected, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            line = self.line(max(.01, deadline - time.monotonic()))
            if line == expected:
                return
        raise TimeoutError(f'No {expected}; see {self.log.name}')

    def stop(self):
        try:
            if self.process.poll() is None:
                try:
                    self.process.stdin.write('STOP\n'); self.process.stdin.flush()
                    self.process.wait(timeout=8)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    self.process.terminate()
                    try: self.process.wait(timeout=3)
                    except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        finally:
            self.reader.join(timeout=1)
            self.process.stdin.close(); self.process.stdout.close(); self.log.close()



def sample_io_wait(pid, stop, result):
    os.sched_setaffinity(0, {3})
    tasks = []
    for task in Path(f'/proc/{pid}/task').iterdir():
        if (task / 'comm').read_text().startswith('multiT'):
            tasks.append(task)
    if len(tasks) != 1:
        result.update(error=f'Expected one I/O thread, found {tasks}')
        return
    task = tasks[0]
    result.update(tid=int(task.name), samples=[], syscall_errors=[])
    result['schedstat_start'] = (task / 'schedstat').read_text().strip()
    result['status_start'] = (task / 'status').read_text()
    while not stop.is_set():
        state = (task / 'stat').read_text().rsplit(')', 1)[1].split()[0]
        wchan = (task / 'wchan').read_text().strip()
        try:
            syscall = (task / 'syscall').read_text().strip()
        except OSError as error:
            syscall = 'unavailable'
            if not result['syscall_errors']: result['syscall_errors'].append(str(error))
        result['samples'].append({'time': time.monotonic(), 'state': state, 'wchan': wchan, 'syscall': syscall})
        stop.wait(.002)
    result['schedstat_end'] = (task / 'schedstat').read_text().strip()
    result['status_end'] = (task / 'status').read_text()


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--disable-offloads':
        print(json.dumps(disable_offloads(sys.argv[2]))); return
    parser = argparse.ArgumentParser()
    parser.add_argument('--warmup', type=int, default=5)
    parser.add_argument('--seconds', type=int, default=10)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--output', default='target/benchmark')
    parser.add_argument('--scenario', choices=[x[0] for x in SCENARIOS])
    parser.add_argument('--client-cpu', type=int, help='client CPU (default: first allowed CPU)')
    parser.add_argument('--server-cpu', type=int, help='server JVM CPU (default: second allowed CPU)')
    parser.add_argument('--vpp-cpu', type=int, help='VPP CPU (default: share the server JVM CPU)')
    parser.add_argument('--link-type', choices=('veth', 'netkit'), default='veth', help='cross-namespace link; netkit uses L2 mode')
    parser.add_argument('--rx-mode', choices=('interrupt', 'polling'), default='interrupt')
    options = parser.parse_args()
    if min(options.warmup, options.seconds, options.repeats) < 1:
        parser.error('warmup/seconds/repeats must be positive')
    output = (ROOT / options.output).resolve(); output.mkdir(parents=True, exist_ok=True)
    if (output / 'raw.jsonl').exists(): raise FileExistsError('Choose a new output directory to avoid mixing benchmark samples')
    cp = str(ROOT / 'target/classes') + ':' + (ROOT / 'target/benchmark-classpath').read_text().strip()
    java = Path(os.environ['JAVA_HOME']) / 'bin/java' if 'JAVA_HOME' in os.environ else Path('java')
    java_args = [java, '--enable-native-access=ALL-UNNAMED', '-Xms256m', '-Xmx256m', '-XX:+UseG1GC',
                 '-XX:ActiveProcessorCount=1', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1',
                 '-cp', cp, 'dev.vcl.netty.benchmark.TransportBenchmark']
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2: raise RuntimeError('At least two allowed CPUs are needed')
    client_cpu = options.client_cpu if options.client_cpu is not None else cpus[0]
    server_cpu = options.server_cpu if options.server_cpu is not None else cpus[1]
    vpp_cpu = options.vpp_cpu if options.vpp_cpu is not None else server_cpu
    if any(cpu not in cpus for cpu in (client_cpu, server_cpu, vpp_cpu)):
        parser.error('selected CPUs must be in the allowed CPU affinity set')
    if client_cpu in (server_cpu, vpp_cpu):
        parser.error('client CPU must differ from server JVM and VPP CPUs')
    prefix = Path(os.environ.get('VPP_PREFIX', '/usr'))
    vpp = Path(os.environ.get('VPP_BIN', prefix / 'bin/vpp'))
    ctl = Path(os.environ.get('VPP_CTL', prefix / 'bin/vppctl'))
    lib = Path(os.environ.get('VPP_LIB_DIR', prefix / 'lib/x86_64-linux-gnu'))
    plugins = Path(os.environ.get('VPP_PLUGIN_DIR', lib / 'vpp_plugins'))
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = str(lib) + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    holder = subprocess.Popen(['unshare', '--net', sys.executable, '-u', '-c',
                               'import os,time; print(os.getpid(),flush=True); time.sleep(86400)'], stdout=subprocess.PIPE, text=True)
    holder_pid = int(holder.stdout.readline().strip())
    peer_prefix = ['nsenter', '-t', str(holder_pid), '-n']
    rows = []
    current = None
    vpp_process = None
    vpp_log = None

    def peer(*args): return run(peer_prefix + list(args), env=env)
    def cli(*args): return run([ctl, '-s', output / 'cli.sock', *args], env=env)

    try:
        run(['ip', 'link', 'set', 'lo', 'up'])
        link_args = ['veth'] if options.link_type == 'veth' else ['netkit', 'mode', 'l2']
        run(['ip', 'link', 'add', 'nvclient', 'type', *link_args, 'peer', 'name', 'nvserver'])
        run(['ip', 'link', 'set', 'nvserver', 'netns', holder_pid])
        run(['ip', 'addr', 'add', '10.223.0.1/24', 'dev', 'nvclient'])
        run(['ip', 'link', 'set', 'nvclient', 'up'])
        peer('ip', 'link', 'set', 'lo', 'up')
        peer('ip', 'link', 'set', 'nvserver', 'up')
        client_offloads = disable_offloads('nvclient')
        server_offloads = json.loads(peer(sys.executable, str(Path(__file__).resolve()), '--disable-offloads', 'nvserver'))
        metadata = {
            'date_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'uname': list(os.uname()),
            'java': subprocess.run([java, '-version'], text=True, capture_output=True).stderr,
            'cpu_model': next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')), ''),
            'allowed_cpus': cpus, 'client_cpu': client_cpu, 'server_cpu': server_cpu, 'vpp_cpu': vpp_cpu,
            'cpu_topology': {str(cpu): {key: Path(f'/sys/devices/system/cpu/cpu{cpu}/topology/{key}').read_text().strip()
                                      for key in ('physical_package_id', 'core_id', 'thread_siblings_list')}
                             for cpu in sorted({client_cpu, server_cpu, vpp_cpu})},
            'cpu_max': Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
            'server_workers': 1, 'heap_mib': 256, 'gc': 'G1, one parallel and one concurrent GC thread',
            'af_packet': {'version': 2, 'rx_tx_frame_bytes': 2048, 'rx_tx_frames_per_block': 1024, 'checksum_gso': False},
            'client_offloads': client_offloads, 'server_offloads': server_offloads,
            'network': f'epoll client in netns A -> {options.link_type} (L2) -> kernel epoll OR VPP AF_PACKET/VCL server in netns B',
            'link_type': options.link_type,
            'server_budget': ('one shared CPU for server JVM plus VPP; no VPP in epoll samples' if vpp_cpu == server_cpu
                              else 'one CPU for server JVM plus a separate VPP CPU; no VPP in epoll samples'),
            'options': vars(options),
            'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for directory in ('src/main', 'src/benchmark') for p in sorted((ROOT / directory).rglob('*.java'))},
            'script_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in (Path(__file__), ROOT / 'scripts/benchmark.sh')},
        }
        (output / 'environment.json').write_text(json.dumps(metadata, indent=2))
        scenarios = [x for x in SCENARIOS if options.scenario is None or x[0] == options.scenario]
        for repeat in range(options.repeats):
            # Alternate which transport runs first to reduce systematic order effects.
            for mode in ['vcl']:
                if mode == 'epoll':
                    peer('ip', 'addr', 'add', '10.223.0.2/24', 'dev', 'nvserver')
                else:
                    startup = output / 'startup.conf'
                    startup.write_text(f'''unix {{ nodaemon cli-listen {output}/cli.sock runtime-dir {output}/vpp }}
cpu {{ main-core {vpp_cpu} }}
api-segment {{ prefix netty-vcl-bench }}
statseg {{ socket-name {output}/stats.sock }}
plugins {{ path {plugins} plugin default {{ disable }} plugin af_packet_plugin.so {{ enable }} }}
session {{ enable use-app-socket-api evt_qs_memfd_seg }}
buffers {{ buffers-per-numa 1024 default data-size 2048 }}
''')
                    vpp_log = open(output / f'vpp-{repeat}.log', 'w')
                    vpp_process = subprocess.Popen(peer_prefix + ['taskset', '-c', str(vpp_cpu), str(vpp), '-c', str(startup)],
                                                   env=env, stdout=vpp_log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + 10
                    while True:
                        if vpp_process.poll() is not None: raise RuntimeError(f'VPP startup failed; see {vpp_log.name}')
                        try:
                            version = cli('show', 'version')
                            if 'vpp v25.10' in version: break
                        except RuntimeError: pass
                        if time.monotonic() > deadline: raise TimeoutError('VPP startup timeout')
                        time.sleep(.05)
                    metadata['vpp_version'] = version.strip()
                    (output / 'environment.json').write_text(json.dumps(metadata, indent=2))
                    # Both kernels use software segmentation/checksums; equal wire packetization.
                    cli('create', 'host-interface', 'v2', 'name', 'nvserver', 'rx-size', '2048', 'tx-size', '2048', 'rx-per-block', '1024', 'tx-per-block', '1024', 'cksum-gso-disable')
                    if 'host-nvserver' not in cli('show', 'interface'): raise RuntimeError('AF_PACKET interface failed')
                    cli('set', 'interface', 'state', 'host-nvserver', 'up')
                    cli('set', 'interface', 'ip', 'address', 'host-nvserver', '10.223.0.2/24')
                    cli('set', 'interface', 'rx-mode', 'host-nvserver', options.rx_mode)
                    (output / 'rx-placement.txt').write_text(cli('show', 'interface', 'rx-placement'))
                    config = output / 'vcl.conf'
                    config.write_text(f'''vcl {{
 app-socket-api {output}/vpp/app_ns_sockets/default
 app-scope-global
 use-mq-eventfd
 max-workers 8
 rx-fifo-size 1048576
 tx-fifo-size 1048576
}}
''')
                    env['VCL_CONFIG'] = str(config)
                run(['ip', 'neigh', 'flush', 'dev', 'nvclient'])
                current = Process(peer_prefix + ['taskset', '-c', str(server_cpu)] + java_args + ['server', mode, '0.0.0.0', '19300', '1'],
                                  output / f'server-{mode}-{repeat}.log', env)
                current.wait_for('READY')
                try:
                    for name, size, connections, window in scenarios:
                        print(f'RUN repeat={repeat + 1}/{options.repeats} transport={mode} scenario={name}', flush=True)
                        client = Process(['taskset', '-c', str(client_cpu)] + java_args + ['client', '10.223.0.2', '19300', str(size), str(connections), str(window), str(options.warmup), str(options.seconds)],
                                         output / f'client-{mode}-{name}-{repeat}.log', env)
                        try:
                            client.wait_for('MEASURE_START', options.warmup + 15)
                            start = time.monotonic(); server_start = cpu_seconds(current.pid)
                            vpp_start = cpu_seconds(vpp_process.pid) if vpp_process else 0
                            cg_start = cgroup_cpu()
                            wait_sample = {}
                            wait_stop = threading.Event()
                            wait_thread = threading.Thread(target=sample_io_wait, args=(current.pid, wait_stop, wait_sample), daemon=True)
                            wait_thread.start()
                            try:
                                client.wait_for('MEASURE_END', options.seconds + 10)
                            finally:
                                wait_stop.set(); wait_thread.join(timeout=2)
                            (output / f'io-wait-{name}.json').write_text(json.dumps(wait_sample, indent=2))
                            elapsed = time.monotonic() - start
                            server_seconds = cpu_seconds(current.pid) - server_start
                            vpp_seconds = cpu_seconds(vpp_process.pid) - vpp_start if vpp_process else 0
                            cg_end = cgroup_cpu()
                            while True:
                                line = client.line(10)
                                if line.startswith('RESULT '):
                                    row = json.loads(line[7:]); break
                            client.process.wait(timeout=10)
                            if client.process.returncode: raise RuntimeError(f'Client failed: {client.log.name}')
                            row.update(transport=mode, scenario=name, repeat=repeat + 1,
                                       server_cpu_seconds=server_seconds, vpp_cpu_seconds=vpp_seconds,
                                       server_cpu_cores=(server_seconds + vpp_seconds) / elapsed,
                                       jvm_server_cpu_cores=server_seconds / elapsed, vpp_cpu_cores=vpp_seconds / elapsed,
                                       client_cpu_cores=row['client_cpu_seconds'] / row['elapsed_seconds'],
                                       cgroup_delta={k: cg_end[k] - cg_start.get(k, 0) for k in cg_end})
                            (output / f'runtime-{name}-{repeat}.txt').write_text(cli('show', 'runtime'))
                            cli('clear', 'runtime')
                            rows.append(row)
                            with (output / 'raw.jsonl').open('a') as f: f.write(json.dumps(row) + '\n')
                            print(f"RESULT {mode} {name}: {row['messages_per_second']:.0f} msg/s, p99={row['p99_us']:.1f} us, server+VPP={row['server_cpu_cores']:.3f} cores, client={row['client_cpu_cores']:.3f} cores", flush=True)
                        finally: client.stop()
                finally:
                    current.stop(); current = None
                    if mode == 'epoll': peer('ip', 'addr', 'del', '10.223.0.2/24', 'dev', 'nvserver')
                    if vpp_process:
                        (output / f'vpp-counters-{repeat}.txt').write_text(cli('show', 'interface') + cli('show', 'errors'))
                        vpp_process.terminate(); vpp_process.wait(timeout=10); vpp_process = None
                        vpp_log.close(); vpp_log = None
        (output / 'results.json').write_text(json.dumps({'environment': metadata, 'samples': rows}, indent=2))
        flat = [{k: v for k, v in row.items() if k != 'cgroup_delta'} for row in rows]
        with (output / 'results.csv').open('w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=list(flat[0])); writer.writeheader(); writer.writerows(flat)
        print(f'Completed {len(rows)} samples: {output}', flush=True)
    finally:
        if current: current.stop()
        if vpp_process:
            vpp_process.terminate()
            try: vpp_process.wait(timeout=5)
            except subprocess.TimeoutExpired: vpp_process.kill(); vpp_process.wait()
        if vpp_log: vpp_log.close()
        holder.terminate()
        try: holder.wait(timeout=3)
        except subprocess.TimeoutExpired: holder.kill(); holder.wait()
        holder.stdout.close()


if __name__ == '__main__':
    main()
