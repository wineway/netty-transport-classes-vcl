#!/usr/bin/env bash
set -euo pipefail

# All network changes live inside a fresh user/network namespace.
if [[ ${1:-} != --inside ]]; then
    exec unshare --user --map-root-user --net "$0" --inside "$@"
fi
shift
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"
vpp_prefix=${VPP_PREFIX:-/usr}
vpp_bin=${VPP_BIN:-$vpp_prefix/bin/vpp}
vpp_ctl=${VPP_CTL:-$vpp_prefix/bin/vppctl}
vpp_lib=${VPP_LIB_DIR:-$vpp_prefix/lib/x86_64-linux-gnu}
vpp_plugins=${VPP_PLUGIN_DIR:-$vpp_lib/vpp_plugins}
run_dir=$project_root/target/wire
mkdir -p "$run_dir"
export LD_LIBRARY_PATH="$vpp_lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

ip link set lo up
ip link add nvhost type veth peer name nvvpp
ip addr add 10.222.0.1/24 dev nvhost
ip link set nvhost up
ip link set nvvpp up

cat > "$run_dir/startup.conf" <<EOF
unix { nodaemon cli-listen $run_dir/cli.sock runtime-dir $run_dir/vpp }
api-segment { prefix netty-vcl-wire }
statseg { socket-name $run_dir/stats.sock }
plugins { path $vpp_plugins plugin default { disable } plugin af_packet_plugin.so { enable } }
session { enable use-app-socket-api evt_qs_memfd_seg }
buffers { buffers-per-numa 1024 default data-size 2048 }
EOF
"$vpp_bin" -c "$run_dir/startup.conf" > "$run_dir/vpp.log" 2>&1 &
vpp_pid=$!
cleanup() {
    {
        ip -s link
        ip neigh
        "$vpp_ctl" -s "$run_dir/cli.sock" show interface
        "$vpp_ctl" -s "$run_dir/cli.sock" show errors
        "$vpp_ctl" -s "$run_dir/cli.sock" show session verbose
        "$vpp_ctl" -s "$run_dir/cli.sock" show trace
    } > "$run_dir/diagnostics.log" 2>&1 || true
    kill "$vpp_pid" 2>/dev/null || true
    wait "$vpp_pid" 2>/dev/null || true
}
trap cleanup EXIT
for ((attempt=0; attempt<100; attempt++)); do
    if [[ -S $run_dir/cli.sock ]] && "$vpp_ctl" -s "$run_dir/cli.sock" show version > "$run_dir/version.txt" 2>/dev/null; then break; fi
    if ! kill -0 "$vpp_pid" 2>/dev/null; then cat "$run_dir/vpp.log" >&2; exit 1; fi
    sleep 0.05
done
"$vpp_ctl" -s "$run_dir/cli.sock" create host-interface v2 name nvvpp rx-size 2048 tx-size 2048 rx-per-block 32 tx-per-block 32
"$vpp_ctl" -s "$run_dir/cli.sock" show interface > "$run_dir/interfaces.txt"
if ! rg -q '^host-nvvpp[[:space:]]' "$run_dir/interfaces.txt"; then
    cat "$run_dir/vpp.log" >&2
    exit 1
fi
"$vpp_ctl" -s "$run_dir/cli.sock" set interface state host-nvvpp up
"$vpp_ctl" -s "$run_dir/cli.sock" set interface ip address host-nvvpp 10.222.0.2/24
"$vpp_ctl" -s "$run_dir/cli.sock" trace add af-packet-input 50

cat > "$run_dir/vcl.conf" <<EOF
vcl {
  app-socket-api $run_dir/vpp/app_ns_sockets/default
  app-scope-local
  app-scope-global
  use-mq-eventfd
  max-workers 16
  rx-fifo-size 1048576
  tx-fifo-size 1048576
}
EOF
export VCL_CONFIG=$run_dir/vcl.conf VCL_WIRE_LOCAL=10.222.0.2 VCL_WIRE_PEER=10.222.0.1
export VCL_TEST_VPP_PID=$vpp_pid
"${MVN:-mvn}" -B -ntp verify "$@"
