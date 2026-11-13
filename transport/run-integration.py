#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the same real transport contracts in isolated JVMs; never silently fall back to simulation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import resource
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent

def run(command, **kwargs):
    return subprocess.run([str(arg) for arg in command], check=True, text=True, **kwargs)

def wait_path(path, process):
    deadline = time.monotonic() + 20
    while not path.exists():
        if process.poll() is not None:
            raise RuntimeError(f"VPP exited {process.returncode}")
        if time.monotonic() >= deadline:
            raise TimeoutError(str(path))
        time.sleep(.01)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', choices=['epoll', 'interrupt', 'poll', 'all'], required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--maven-repo', type=Path, required=True)
    parser.add_argument('--vpp-build', type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests', default='TransportContractIT')
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--abort-unresponsive-vpp', action='store_true', help='Dump native signal-handler stack if this run VPP CLI times out; core dumps disabled')
    parser.add_argument('--property', action='append', default=[], help='Extra JVM test property key=value')
    parser.add_argument('--kill-vpp-on-ready', action='store_true', help='Fault injection: terminate only this run primary VPP when the test signals readiness')
    parser.add_argument('--topology', choices=['loopback', 'memif'], default='loopback')
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    modes = ['epoll', 'interrupt', 'poll'] if args.backend == 'all' else [args.backend]
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2:
        raise RuntimeError('At least two CPUs required')
    metadata = {'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
                'java': run([args.java_home / 'bin/java', '-version'], capture_output=True).stderr,
                'vpp_cpus': cpus[:2] if args.topology == 'memif' else cpus[:1],
                'jvm_cpus': cpus[2:4] if args.topology == 'memif' else cpus[1:3],
                'peer_cpus': cpus[4:6] if len(cpus) >= 6 else cpus[-1:], 'modes': modes,
                'topology': args.topology + '; same host; no physical NIC',
                'hardware': run(['lscpu'], capture_output=True).stdout,
                'kernel': os.uname().release, 'extra_properties': args.property,
                'source_sha256': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in sorted([*(ROOT / 'src').rglob('*.java'), ROOT / 'pom.xml', Path(__file__).resolve()])},
                'netty_jars_sha256': {str(path.relative_to(args.maven_repo)): hashlib.sha256(path.read_bytes()).hexdigest()
                                     for path in sorted((args.maven_repo / 'io/netty').glob('*/4.2.20.Final/*.jar'))}}
    if any(mode != 'epoll' for mode in modes):
        if not args.vpp_build or not args.library:
            raise RuntimeError('VCL requires --vpp-build and --library')
        args.library = args.library.resolve(); args.vpp_build = args.vpp_build.resolve()
        cache = (args.library.parents[2] / 'CMakeCache.txt').read_text()
        source = Path(next(line.split('=', 1)[1] for line in cache.splitlines()
                           if line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL='))).parent
        patch = run(['git', '-C', source, 'diff', 'HEAD', '--binary'], capture_output=True).stdout
        metadata.update(library_sha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
                        library_source_commit=run(['git', '-C', source, 'rev-parse', 'HEAD'], capture_output=True).stdout.strip(),
                        patch_sha256=hashlib.sha256(patch.encode()).hexdigest())
        (args.output / 'vpp.patch').write_text(patch)
        (args.output / 'library-CMakeCache.txt').write_text(cache)
        server_cache = (args.vpp_build / 'CMakeCache.txt').read_text()
        server_source = Path(next(line.split('=', 1)[1] for line in server_cache.splitlines()
                                 if line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL='))).parent
        server_patch = run(['git', '-C', server_source, 'diff', 'HEAD', '--binary'], capture_output=True).stdout
        (args.output / 'server-CMakeCache.txt').write_text(server_cache)
        (args.output / 'server.patch').write_text(server_patch)
        metadata.update(server_source_commit=run(['git', '-C', server_source, 'rev-parse', 'HEAD'], capture_output=True).stdout.strip(),
                        server_patch_sha256=hashlib.sha256(server_patch.encode()).hexdigest(),
                        server_binary_sha256=hashlib.sha256((args.vpp_build / 'bin/vpp').read_bytes()).hexdigest())
    (args.output / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if args.topology == 'memif' and len(cpus) < 3:
        raise RuntimeError('memif topology requires at least three CPUs')
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    env['PATH'] = str(args.java_home.resolve() / 'bin') + os.pathsep + env['PATH']
    for mode in modes:
        case = args.output / mode; case.mkdir()
        vpp = None
        servers = []
        properties = [f'-Dvcl.it.backend={mode}', f'-Dvcl.it.output={case}',
                      '-Dvcl.it.peerCpus=' + ','.join(map(str, metadata['peer_cpus'])),
                      f'-Dvcl.bench.clockTicks={os.sysconf("SC_CLK_TCK")}']
        command = ['taskset', '-c', ','.join(map(str, metadata['jvm_cpus'])), 'mvn', '-B', '-o', '-f', ROOT / 'pom.xml',
                   f'-Dmaven.repo.local={args.maven_repo.resolve()}', f'-Dtest={args.tests}', 'test']
        try:
            if mode == 'epoll':
                command += ['-Pnative-it']
            else:
                server_env = dict(env, LD_LIBRARY_PATH=str(args.vpp_build / 'lib/x86_64-linux-gnu'))
                directories = [case / 'primary', case / 'peer'] if args.topology == 'memif' else [case]
                for index, directory in enumerate(directories):
                    directory.mkdir(exist_ok=True)
                    cli = directory / 'cli.sock'; sapi = directory / 'app_ns_sockets/default'
                    config = directory / 'startup.conf'
                    plugins = 'plugin default { disable }'
                    if args.topology == 'memif':
                        plugins += f" path {args.vpp_build}/lib/x86_64-linux-gnu/vpp_plugins plugin memif_plugin.so {{ enable }}"
                    config.write_text(f'unix {{ nodaemon runtime-dir {directory} cli-listen {cli} }}\n'
                                      f'cpu {{ main-core {cpus[index]} }}\n'
                                      'memory { main-heap-size 256M main-heap-page-size default }\n'
                                      'buffers { buffers-per-numa 4096 page-size default }\n'
                                      f'statseg {{ socket-name {directory}/stats.sock size 32M page-size default }}\n'
                                      f'socksvr {{ socket-name {directory}/api.sock }}\n'
                                      f'session {{ enable use-app-socket-api }}\nplugins {{ {plugins} }}\n')
                    with (directory / 'vpp.log.txt').open('w') as log:
                        process = subprocess.Popen([str(args.vpp_build / 'bin/vpp'), '-c', str(config)],
                                                   stdout=log, stderr=subprocess.STDOUT, env=server_env,
                                                   preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_CORE, (0, 0)))
                    servers.append((process, directory, cli, sapi))
                    wait_path(cli, process); wait_path(sapi, process)
                    setup = ['create loopback interface', 'set interface state loop0 up',
                             'set interface ip address loop0 10.77.0.1/24', 'set interface ip address loop0 fd77::1/64']
                    if args.topology == 'memif':
                        setup = [f'create memif socket id 1 filename {case}/link.sock',
                                 f'create interface memif id 0 socket-id 1 {"master" if index == 0 else "slave"}',
                                 'set interface state memif1/0 up',
                                 f'set interface mac address memif1/0 02:00:00:00:00:0{index + 1}',
                                 f'set interface ip address memif1/0 10.88.0.{index + 1}/24',
                                 f'set interface ip address memif1/0 fd88::{index + 1}/64',
                                 f'set ip neighbor memif1/0 10.88.0.{2 - index} 02:00:00:00:00:0{2 - index} static',
                                 f'set ip neighbor memif1/0 fd88::{2 - index} 02:00:00:00:00:0{2 - index} static']
                    with (directory / 'setup.log.txt').open('w') as log:
                        for line in setup:
                            run([args.vpp_build / 'bin/vppctl', '-s', cli, *line.split()], stdout=log, env=server_env, timeout=10)
                vpp, directory, cli, sapi = servers[0]
                if args.topology == 'memif':
                    for process, directory, control, _ in servers:
                        deadline = time.monotonic() + 20
                        while True:
                            status = run([args.vpp_build / 'bin/vppctl', '-s', control, 'show', 'memif'],
                                         capture_output=True, env=server_env, timeout=10).stdout
                            (directory / 'memif.txt').write_text(status)
                            if ' connected' in status: break
                            if time.monotonic() >= deadline: raise TimeoutError('memif link did not connect')
                            time.sleep(.05)
                    properties += [f'-Dvcl.it.peerSocket={servers[1][3]}', '-Dvcl.it.ipv4=10.88.0.1', '-Dvcl.it.ipv6=fd88::1']
                env['LD_LIBRARY_PATH'] = str(args.library.parent)
                properties += [f'-Dvcl.it.library={args.library}', f'-Dvcl.it.socket={sapi}']
            properties += ['-D' + value for value in args.property]
            properties.append('-Dvcl.bench.vppPids=' + ','.join(str(server[0].pid) for server in servers))
            started = time.time()
            with (case / 'maven.log.txt').open('w') as log:
                test = subprocess.Popen([str(x) for x in command + properties], env=env,
                                        stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    deadline = time.monotonic() + args.timeout
                    killed = False
                    while test.poll() is None:
                        if args.kill_vpp_on_ready and not killed and (case / 'kill-vpp.ready').exists():
                            if vpp is None: raise RuntimeError('VPP fault injection requires a VCL backend')
                            vpp.terminate(); vpp.wait(timeout=10); killed = True
                            (case / 'fault.json').write_text(json.dumps({'action': 'SIGTERM primary VPP', 'pid': vpp.pid}) + '\n')
                        if time.monotonic() >= deadline: raise subprocess.TimeoutExpired(command, args.timeout)
                        time.sleep(.01)
                    returncode = test.returncode
                except subprocess.TimeoutExpired:
                    os.killpg(test.pid, signal.SIGTERM)
                    try: test.wait(timeout=10)
                    except subprocess.TimeoutExpired: os.killpg(test.pid, signal.SIGKILL); test.wait(timeout=5)
                    raise
            reports = ROOT / 'target/surefire-reports'
            for report in reports.glob('TEST-*.xml'):
                if report.stat().st_mtime >= started:
                    shutil.copyfile(report, case / report.name)
            for process, directory, control, _ in servers:
                if process.poll() is None and returncode:
                    for proc_file in ['maps', 'status', 'wchan', 'syscall']:
                        try: (directory / ('failure-' + proc_file + '.txt')).write_text(Path('/proc', str(process.pid), proc_file).read_text())
                        except OSError as error: (directory / ('failure-' + proc_file + '.txt')).write_text(str(error))
                if process.poll() is None and returncode and shutil.which('gdb'):
                    with (directory / 'failure-stack.txt').open('w') as stack:
                        try:
                            subprocess.run(['gdb', '-q', '-batch', '-p', str(process.pid), '-ex', 'thread apply all bt', '-ex', 'detach'], stdout=stack, stderr=subprocess.STDOUT, timeout=20)
                        except subprocess.TimeoutExpired:
                            stack.write('Debugger timed out\n')
                if process.poll() is None:
                    with (directory / 'final-state.txt').open('w') as log:
                        for line in ['show session verbose', 'show errors', 'show interface', 'show ip fib']:
                            log.write(line + '\n'); log.flush()
                            try:
                                run([args.vpp_build / 'bin/vppctl', '-s', control, *line.split()], stdout=log, env=server_env, timeout=10)
                            except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
                                log.write(str(error) + '\n'); log.flush()
                                if args.abort_unresponsive_vpp and isinstance(error, subprocess.TimeoutExpired):
                                    process.send_signal(signal.SIGABRT)
                                    try: process.wait(timeout=5)
                                    except subprocess.TimeoutExpired: pass
                                break
            if returncode:
                raise RuntimeError(f'{mode} failed; see {case}/maven.log.txt')
            print(f'PASS {mode}: {args.tests}', flush=True)
        finally:
            for process, _, _, _ in reversed(servers):
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)

if __name__ == '__main__':
    main()
