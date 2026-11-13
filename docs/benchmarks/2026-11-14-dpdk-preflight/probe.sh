#!/bin/bash
set -eu
ip link add nvclient type netkit mode l2 peer name nvserver
ip link set nvclient up
ip link set nvserver up
export LD_LIBRARY_PATH=/tmp/netty-vcl-deps/vpp/usr/lib/x86_64-linux-gnu
exec timeout 8 /tmp/netty-vcl-deps/vpp/usr/bin/vpp -c /code-repos/netty-transport-classes-vcl/target/dpdk-probe/startup.conf
