from pathlib import Path
import os,subprocess,time,re
out=Path('/code-repos/netty-transport-classes-vcl/target/vr-probe')
env=dict(os.environ,LD_LIBRARY_PATH='/tmp/netty-vcl-deps/vpp/usr/lib/x86_64-linux-gnu')
(out/'cli.sock').unlink(missing_ok=True)
with (out/'vpp.log').open('w') as log:
 p=subprocess.Popen(['/tmp/netty-vcl-deps/vpp/usr/bin/vpp','-c',str(out/'startup.conf')],env=env,stdout=log,stderr=subprocess.STDOUT)
 try:
  for i in range(100):
   if p.poll() is not None:
    print('STARTUP_EXIT',p.returncode,flush=True);break
   if (out/'cli.sock').exists() and subprocess.run(['/tmp/netty-vcl-deps/vpp/usr/bin/vppctl','-s',str(out/'cli.sock'),'show','version'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=2).returncode == 0:
    for cmd in ['show version','set interface mtu packet 1500 Ethernet0','set interface state Ethernet0 up','set interface ip address Ethernet0 10.223.0.2/24','ping 10.223.0.1 source Ethernet0 repeat 1','show interface','show hardware-interfaces','show dpdk version','show errors','show runtime','show logging']:
     r=subprocess.run(['/tmp/netty-vcl-deps/vpp/usr/bin/vppctl','-s',str(out/'cli.sock'),*cmd.split()],env=env,text=True,capture_output=True,timeout=10)
     print(cmd, r.returncode, r.stdout if cmd != 'show logging' else '(saved to file)',r.stderr,flush=True)
     (out/(re.sub(r'[^a-zA-Z0-9_-]', '-', cmd)+'.txt')).write_text(r.stdout+r.stderr)
    break
   time.sleep(.1)
  else:print('STARTUP_TIMEOUT',flush=True)
 finally:
  if p.poll() is None:
   p.terminate()
   try:p.wait(timeout=10)
   except subprocess.TimeoutExpired:p.kill();p.wait()
print((out/'vpp.log').read_text(),flush=True)
