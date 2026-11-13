import json, os, socket, subprocess, time
from pathlib import Path
out=Path('/tmp/vcl-monitor-churn'); out.mkdir(exist_ok=False)
build=Path('/tmp/vcl26-build'); env=dict(os.environ,LD_LIBRARY_PATH=str(build/'lib/x86_64-linux-gnu'))
cpu=min(os.sched_getaffinity(0)); cli=out/'cli.sock'; sapi=out/'app_ns_sockets/default'
conf=out/'startup.conf'; conf.write_text(f'unix {{ nodaemon runtime-dir {out} cli-listen {cli} }}\ncpu {{ main-core {cpu} }}\nmemory {{ main-heap-size 256M main-heap-page-size default }}\nbuffers {{ buffers-per-numa 4096 page-size default }}\nstatseg {{ socket-name {out}/stats.sock size 32M page-size default }}\nsocksvr {{ socket-name {out}/api.sock }}\nsession {{ enable use-app-socket-api }}\nplugins {{ plugin default {{ disable }} }}\n')
with (out/'vpp.log.txt').open('w') as log:
    vpp=subprocess.Popen([str(build/'bin/vpp'),'-c',str(conf)],env=env,stdout=log,stderr=subprocess.STDOUT)
    try:
        for _ in range(1000):
            if sapi.exists(): break
            if vpp.poll() is not None: raise RuntimeError(vpp.returncode)
            time.sleep(.01)
        with (out/'probe.jsonl').open('w') as result:
            for i in range(65):
                if i:
                    with socket.socket(socket.AF_UNIX,socket.SOCK_SEQPACKET) as monitor: monitor.connect(str(sapi))
                    time.sleep(.01)
                fds=len(list(Path('/proc',str(vpp.pid),'fd').iterdir()))
                try:
                    subprocess.run([str(build/'bin/vppctl'),'-s',str(cli),'show','version'],env=env,capture_output=True,check=True,timeout=1)
                    status='responsive'
                except subprocess.TimeoutExpired: status='timeout'
                row={'connections_closed':i,'server_fds':fds,'cli':status};result.write(json.dumps(row)+'\n');result.flush();print(row,flush=True)
                if status=='timeout':break
    finally:
        vpp.terminate()
        try:vpp.wait(timeout=5)
        except subprocess.TimeoutExpired:vpp.kill();vpp.wait()
