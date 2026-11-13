#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproducible, process-isolated Java 26/VPP 26.10 capability experiments."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import ipaddress
import struct
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent
VPP_COMMIT = "4acb320269bc4f3533e35c80afedac38853b2f60"


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, text=True, **kwargs)


def wait_path(path, process, timeout=20):
    end = time.monotonic() + timeout
    while not path.exists():
        if process.poll() is not None:
            raise RuntimeError(f"Process exited {process.returncode} before {path}")
        if time.monotonic() >= end:
            raise TimeoutError(str(path))
        time.sleep(0.01)


def udp_pcap(path, version, port):
    def checksum(data):
        if len(data) % 2:
            data += b"\0"
        total = sum(struct.unpack(f"!{len(data) // 2}H", data))
        while total >> 16:
            total = (total & 65535) + (total >> 16)
        return (~total) & 65535

    src = ipaddress.ip_address("10.77.0.2" if version == 4 else "fd77::2").packed
    dst = ipaddress.ip_address("10.77.0.1" if version == 4 else "fd77::1").packed
    with path.open("wb") as output:
        # LINKTYPE_RAW. PG injects the packet bytes directly into ip4/ip6-input.
        output.write(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 101))
        for length in (32, 0, 1):
            payload = bytes([0x5A]) * length
            udp = struct.pack("!HHHH", port + 1, port, 8 + length, 0) + payload
            pseudo = src + dst + (struct.pack("!BBH", 0, 17, len(udp)) if version == 4
                                  else struct.pack("!I3xB", len(udp), 17))
            udp = udp[:6] + struct.pack("!H", checksum(pseudo + udp) or 65535) + udp[8:]
            if version == 4:
                ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(udp), 1, 0, 64, 17, 0, src, dst)
                ip = ip[:10] + struct.pack("!H", checksum(ip)) + ip[12:]
            else:
                ip = struct.pack("!IHBB16s16s", 6 << 28, len(udp), 17, 64, src, dst)
            packet = ip + udp
            output.write(struct.pack("<IIII", 0, 0, len(packet), len(packet)) + packet)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Clean local VPP v26.10 checkout")
    parser.add_argument("--build", type=Path, required=True, help="CMake build from that checkout")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    parser.add_argument("--scenario", choices=["smoke", "workers", "workers-after-close", "udp", "retention", "bench", "all"], default="all")
    parser.add_argument("--debug-native", action="store_true", help="Stop at native allocation failure under gdb")
    parser.add_argument("--probe-library", type=Path, help="Alternative libvppcom; requires --probe-source for provenance")
    parser.add_argument("--probe-source", type=Path, help="Source checkout used to build the alternative library (experimental changes recorded)")
    parser.add_argument("--asan-runtime", type=Path, help="Preload ASan in the probe JVM only")
    parser.add_argument("--gdb-script", type=Path, help="Run a diagnostic gdb script")
    args = parser.parse_args()
    args.source, args.build, args.output = (p.resolve() for p in (args.source, args.build, args.output))
    commit = run(["git", "-C", args.source, "rev-parse", "HEAD"], capture_output=True).stdout.strip()
    if commit != VPP_COMMIT:
        raise RuntimeError(f"VPP commit mismatch: {commit}")
    if run(["git", "-C", args.source, "status", "--porcelain", "--untracked-files=no"], capture_output=True).stdout:
        raise RuntimeError("VPP source has tracked changes")
    cache = (args.build / "CMakeCache.txt").read_text()
    if f"CMAKE_HOME_DIRECTORY:INTERNAL={args.source}/src\n" not in cache:
        raise RuntimeError("Build tree is not configured from the pinned source")
    lib = args.build / "lib/x86_64-linux-gnu/libvppcom.so.26.10"
    server_lib = lib
    if args.probe_library:
        lib = args.probe_library.resolve()
    probe_source = args.source
    probe_patch = ""
    probe_cache = cache
    if args.probe_library:
        if not args.probe_source:
            raise RuntimeError("Alternative library requires --probe-source")
        probe_source = args.probe_source.resolve()
        probe_commit = run(["git", "-C", probe_source, "rev-parse", "HEAD"], capture_output=True).stdout.strip()
        if probe_commit != VPP_COMMIT:
            raise RuntimeError("Probe source must be based on the pinned VPP commit")
        untracked = run(["git", "-C", probe_source, "ls-files", "--others", "--exclude-standard", "src"], capture_output=True).stdout
        if untracked:
            raise RuntimeError("Untracked probe source files are not covered by the patch")
        probe_patch = run(["git", "-C", probe_source, "diff", "HEAD", "--binary"], capture_output=True).stdout
        probe_cache = (lib.parents[2] / "CMakeCache.txt").read_text()
        if f"CMAKE_HOME_DIRECTORY:INTERNAL={probe_source}/src\n" not in probe_cache:
            raise RuntimeError("Alternative library build does not match --probe-source")
        if (probe_source / "src/vcl/vppcom.h").read_bytes() != (args.source / "src/vcl/vppcom.h").read_bytes():
            raise RuntimeError("Experimental library changed the public ABI header")
    elif args.probe_source:
        raise RuntimeError("--probe-source requires --probe-library")
    args.output.mkdir(parents=True, exist_ok=False)
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2:
        raise RuntimeError("At least two allowed CPUs required")
    env = dict(os.environ, LD_LIBRARY_PATH=str(server_lib.parent))
    probe_env = dict(env, LD_LIBRARY_PATH=str(lib.parent))
    if args.asan_runtime:
        probe_env.update(LD_PRELOAD=str(args.asan_runtime.resolve()),
                         ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:handle_segv=0:allow_user_segv_handler=1:poison_history_size=1024")
    java = args.java_home / "bin/java"
    javac = args.java_home / "bin/javac"
    classes = ROOT / "target/classes"
    classes.mkdir(parents=True, exist_ok=True)
    run([javac, "--release", "26", "-d", classes, *sorted((ROOT / "src/main/java").rglob("*.java"))])
    abi_exe = args.output / "layout-probe"
    run(["cc", "-std=gnu11", "-Wall", "-Wextra", "-Werror", f"-I{args.source}/src",
         ROOT / "src/test/c/layout_probe.c", "-o", abi_exe])
    abi = args.output / "abi.properties"
    with abi.open("w") as log:
        run([abi_exe], stdout=log)
    java_args = ["taskset", "-c", str(cpus[1]), java, "--enable-native-access=ALL-UNNAMED",
                 "-cp", classes, "me.wineway.vcl.preflight.Probe", abi]
    with (args.output / "abi-wakeup.jsonl").open("w") as log:
        run(java_args, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    metadata = {"recorded_at_utc": datetime.now(timezone.utc).isoformat(),
                "vpp_commit": commit,
                "probe_source": str(probe_source), "probe_experimental": bool(probe_patch),
                "probe_patch_sha256": hashlib.sha256(probe_patch.encode()).hexdigest(),
                "server_library_sha256": hashlib.sha256(server_lib.read_bytes()).hexdigest(),
                "java": run([java, "-version"], capture_output=True).stderr,
                "platform": platform.platform(), "machine": platform.machine(),
                "cpu": Path("/proc/cpuinfo").read_text().split("model name", 1)[-1].splitlines()[0],
                "allowed_cpus": cpus, "vpp_cpu": cpus[0], "jvm_cpu": cpus[1],
                "topology": "single VPP process; loopback interface; same-host VCL sessions; no physical NIC",
                "warmup_operations": 2000, "measured_operations": 10000, "repetitions": 3,
                "library_sha256": hashlib.sha256(lib.read_bytes()).hexdigest(),
                "header_sha256": hashlib.sha256((args.source / "src/vcl/vppcom.h").read_bytes()).hexdigest(),
                "numa": run(["lscpu"], capture_output=True).stdout}
    metadata["probe_sources_sha256"] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in [Path(__file__), ROOT / "src/test/c/layout_probe.c",
                     *sorted((ROOT / "src/main/java").rglob("*.java"))]}
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (args.output / "CMakeCache.txt").write_text(cache)
    (args.output / "probe-CMakeCache.txt").write_text(probe_cache)
    (args.output / "probe-source.patch").write_text(probe_patch)
    with (args.output / "ldd.txt").open("w") as log:
        run(["ldd", lib], stdout=log, env=dict(env, LD_LIBRARY_PATH=str(lib.parent)))
    scenarios = ["smoke", "workers", "udp", "retention", "bench"] if args.scenario == "all" else [args.scenario]
    for mode in ("interrupt", "poll"):
        for scenario in scenarios:
            case = args.output / f"{mode}-{scenario}"
            case.mkdir()
            cli = case / "cli.sock"
            setup = case / "setup.cli"
            setup.write_text("create loopback interface\nset interface state loop0 up\n"
                             "set interface ip address loop0 10.77.0.1/24\n"
                             "set interface ip address loop0 fd77::1/64\n")
            startup = case / "startup.conf"
            startup.write_text(f"unix {{ nodaemon runtime-dir {case} cli-listen {cli} }}\n"
                               f"cpu {{ main-core {cpus[0]} }}\n"
                               "memory { main-heap-size 256M main-heap-page-size default }\n"
                               "buffers { buffers-per-numa 4096 page-size default }\n"
                               f"statseg {{ socket-name {case}/stats.sock size 32M page-size default }}\n"
                               f"socksvr {{ socket-name {case}/api.sock }}\n"
                               "session { enable use-app-socket-api }\nplugins { plugin default { disable } }\n")
            with (case / "vpp.log").open("w") as vlog, (case / "probe.jsonl").open("w") as jlog:
                vpp = subprocess.Popen([str(args.build / "bin/vpp"), "-c", str(startup)],
                                       stdout=vlog, stderr=subprocess.STDOUT, env=env)
                probe = None
                try:
                    sapi = case / "app_ns_sockets/default"
                    wait_path(cli, vpp)
                    wait_path(sapi, vpp)
                    # Execute each setup command synchronously: the CLI socket can become
                    # available before an asynchronous 'unix exec' startup script finishes.
                    with (case / "setup.log").open("w") as log:
                        for command in setup.read_text().splitlines():
                            run([args.build / "bin/vppctl", "-s", cli, *command.split()],
                                stdout=log, env=env, timeout=10)
                    for command in ("show version", "show interface address"):
                        with (case / (command.replace(" ", "-") + ".txt")).open("w") as log:
                            run([args.build / "bin/vppctl", "-s", cli, *command.split()],
                                stdout=log, env=env, timeout=10)
                    ready = case / "retained.ready"
                    command = java_args + [lib, sapi, mode, scenario, ready]
                    if args.debug_native:
                        command = ["gdb", "-batch", "-ex", "set breakpoint pending on",
                                   "-ex", "handle SIGSEGV nostop noprint pass",
                                   "-ex", "handle SIGILL nostop noprint pass",
                                   "-ex", "break os_out_of_memory", "-ex", "run", "-ex", "bt",
                                   "-ex", "frame 1", "-ex", "info args", "--args"] + command
                    elif args.gdb_script:
                        command = ["gdb", "-batch", "-x", args.gdb_script.resolve(), "--args"] + command
                    probe = subprocess.Popen([str(x) for x in command],
                                             stdout=jlog, stderr=subprocess.STDOUT, env=probe_env)
                    if scenario == "udp":
                        for version, port in ((4, 23002), (6, 23004)):
                            wait_path(case / f"udp{version}.ready", probe)
                            pcap = case / f"udp{version}.pcap"
                            udp_pcap(pcap, version, port)
                            with (case / f"pg{version}.log").open("w") as log:
                                command = (f"packet-generator new {{ name udp{version} limit 3 "
                                           f"node ip{version}-input interface loop0 pcap {pcap} }}")
                                run([args.build / "bin/vppctl", "-s", cli, command], stdout=log, env=env, timeout=10)
                                run([args.build / "bin/vppctl", "-s", cli,
                                     f"packet-generator enable-stream udp{version}"], stdout=log, env=env, timeout=10)
                    if scenario == "retention":
                        wait_path(ready, probe)
                        vpp.terminate()
                        vpp.wait(timeout=10)
                    result = probe.wait(timeout=120)
                    completed = any(line == '{"test":"probe_complete","passed":true}'
                                    for line in (case / "probe.jsonl").read_text().splitlines())
                    if result != 0 or not completed:
                        if vpp.poll() is None:
                            with (case / "failure-state.txt").open("w") as log:
                                for command in ("show session verbose", "show app", "show errors"):
                                    log.write(command + "\n"); log.flush()
                                    run([args.build / "bin/vppctl", "-s", cli, *command.split()],
                                        stdout=log, env=env, timeout=10)
                        raise RuntimeError(f"{mode}/{scenario} exited {result}; see {case}/probe.jsonl")
                    print(f"COMPLETE {mode}/{scenario} (observation, not transport acceptance)", flush=True)
                finally:
                    for process in (probe, vpp):
                        if process is not None and process.poll() is None:
                            process.terminate()
                            try:
                                process.wait(timeout=10)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=5)


if __name__ == "__main__":
    main()
