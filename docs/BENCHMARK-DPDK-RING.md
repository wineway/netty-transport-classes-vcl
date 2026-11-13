# DPDK ring 软件回环与 epoll / VCL TCP 参考：2026-11-14

**DPDK 软件回环成功：无需物理网卡、VFIO 或 hugepage。** 本轮测得约 4.02–4.04 亿次/秒的 ring 包循环，约占一个 CPU 核。同时以当前 Java 实现复跑了一轮 epoll/VCL TCP Echo，全部六个样本完成应用帧校验和 drain。

这两组数据覆盖的路径不同，以下分别列出，不计算“DPDK 比 epoll 快多少”的倍数。ring 数据不能代表 VPP + DPDK + VCL 的端到端性能。

## DPDK 软件回环

使用 DPDK 25.07.0，与 VPP v25.10 官方构建选用的 DPDK 版本一致。源码 SHA256 与官方 `dpdk.mk` 一致，从源码以 release / native / static 配置编译，仅启用 testpmd、vdev bus、ring mempool、ring net PMD。没有替换系统 VPP 或其插件。

| 配置包长 | RX 包/秒 | TX 包/秒 | 全进程 CPU 核 | 计数窗口 |
| --- | ---: | ---: | ---: | ---: |
| 64 B | 404,057,739 | 404,057,739 | 0.998 | 10.0003 s |
| 1024 B | 401,707,738 | 401,707,738 | 0.999 | 10.0003 s |

两项均预热 5 秒、测量 10 秒，各一次；使用单端口 loop、单转发核、burst 32、2048 个 mbuf 的池、256 MiB 普通页内存。主控线程绑定 CPU 0，转发线程绑定 CPU 1；辅助中断线程的亲和性保留在原始记录中，全进程 CPU 已包含它。测量窗口内 RX/TX 计数增量相同，报告的 RX/TX errors、RX missed、RX nombuf 均为 0，停止转发时 TX dropped 为 0。ring PMD 的统计项目有限，这些计数不等于应用层数据完整性校验。

`start tx_first` 注入一批 32 个包，随后由 `io` 模式循环同一批 mbuf。根据本次编译的源码，ring PMD 主要入队/出队 mbuf 指针，`io` forwarding 不访问报文 payload，也不对每次循环重新分配或释放 mbuf。因此 64 B 与 1024 B 的速率接近是合理的，不能按包长相乘后宣称获得相应网络带宽。此版本 ring PMD 没有维护 RX/TX 字节计数，日志中的 bytes 为 0；端口显示的 10 Gbps 也是虚拟链路属性。

运行时 `mlockall()` 因 memlock 限额失败，测试使用未锁定的普通页；两个有效样本均正常 stop / quit，进程退出码为 0。没有启用 TCP/IP、VPP、VCL、JVM、内核网络路径或物理 NIC，也未测 RTT。

## 同期 TCP Echo 复测

公共客户端仍为 Netty epoll；服务端 epoll 使用 Linux TCP，VCL 使用 VPP 25.10 原生 AF_PACKET/TCP。测试没有把该 VCL 路径标成 DPDK。两端经同一隔离的 netkit L2 接口对，关闭 TSO/GSO/GRO/TX checksum offload。客户端 CPU 0、服务端 JVM CPU 1、VPP 单独 CPU 2；服务端各一个 I/O worker。

Java 26.0.2.1、Netty 4.2.7.Final、256 MiB heap、G1；本轮包含此前完成的 epoll 语义对齐修改。每项预热 5 秒、测量 10 秒，各一次，顺序为先 epoll 三项再 VCL 三项。未与 ring 测量同时运行。

| 应用帧 / 连接 / 每连接窗口 | transport | 消息/秒 | 单向应用 MiB/s | RTT p99 μs | JVM CPU 核 | VPP CPU 核 | 服务端合计核 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 64 B / 1 / 1 | epoll | 114,129 | 6.97 | 11.0 | 0.396 | 0.000 | 0.396 |
| 1024 B / 32 / 16 | epoll | 897,561 | 876.52 | 694.3 | 0.614 | 0.000 | 0.614 |
| 65536 B / 8 / 4 | epoll | 25,351 | 1,584.45 | 2,295.8 | 0.555 | 0.000 | 0.555 |
| 64 B / 1 / 1 | vcl | 11,462 | 0.70 | 107.1 | 0.044 | 0.974 | 1.018 |
| 1024 B / 32 / 16 | vcl | 507,545 | 495.65 | 1,326.1 | 0.126 | 0.645 | 0.771 |
| 65536 B / 8 / 4 | vcl | 7,719 | 482.42 | 6,823.9 | 0.079 | 0.548 | 0.627 |

应用吞吐按完成 Echo 的应用帧计数（含 24 B 基准头），与 ring 的虚拟以太网包计数不是同一单位。TCP RTT 包含客户端入队、排队、发送以及完整回显解析，使用 HdrHistogram；本轮为闭环负载。CPU 来自进程全部线程的 user + system 时间，VCL 合计还包含 VPP。

环境为 Ubuntu 26.04 amd64 / Linux 7.2.8-arch1-1 / Intel i7-11700T，CPU 0、1、2 分属不同物理核，SMT sibling 为 8、9、10。没有宿主机 CPU 隔离或 sibling 预留。`cpu.max=max 100000`，两组全部测量窗口的 cgroup throttling 增量均为 0。单轮样本只能用于当前配置下参考，不能作为稳定性或最大能力的结论。

## 如何互相参考

- 这次实测确认当前环境可以运行 DPDK 的 EAL、普通页内存、mbuf/ethdev 和 ring PMD；之前 VPP + AF_PACKET PMD 启动失败不能推广为“当前环境不能运行 DPDK”。
- ring 回环的高 PPS 反映同核、批量、缓存驻留的指针循环成本。TCP Echo 还包含协议栈、跨进程/跨核同步、真实数据访问、队列等待和 JVM transport，ring 数值不能定位这些环节的瓶颈。
- 同期 VCL TCP 结果与此前 AF_PACKET 结果接近，本轮未观察到 Java 语义对齐后整体性能量级的变化。没有测得 VPP + DPDK 的 TCP Echo，不能依据 ring 结果推断换成 DPDK 后 VCL 会提升多少。

## 补充：VPP 已实际接入 DPDK ring

随后在当前环境中直接启用官方 VPP 25.10 的 `dpdk_plugin.so`，创建 `net_ring0`。实测成功创建并置 up `Ethernet0`，`show dpdk version` 确认 DPDK 25.07.0，RX 队列位于 CPU 2 的主线程并使用 polling。该验证也无需物理 NIC、VFIO 或 hugepage。

关键配置如下，完整启动配置和脚本位于归档目录：

```text
cpu { main-core 2 }
buffers { buffers-per-numa 8192 default data-size 2048 }
dpdk {
  no-pci
  no-hugetlb
  no-shconf
  mem-alloc-request 256
  file-prefix netty-vpp-ring
  vdev net_ring0
}
```

保留 `buffers-per-numa 1024` 时，VPP 在创建 DPDK normal mempool 时失败；增加到 8192 后启动成功。两次均明确分配 256 MiB DPDK 普通页内存，使用 `mem-alloc-request` 参数传入 EAL 的 `-m`。这里只记录配置变化与结果，未进一步确定小 buffer pool 失败的内部条件。

将接口置 up、软件 MTU 设为 1500 并分配 `10.223.0.2/24` 后，向不存在的 `10.223.0.1` 发出一次 ping。VPP 发送的 ARP 请求经 `Ethernet0-tx -> DPDK net_ring -> dpdk-input -> arp-input` 返回，接口计数为 **TX 1 包 / 42 字节，RX 1 包 / 42 字节**，`dpdk-input` 也记录 1 个 vector。该结果验证了实际收发路径。

ICMP Echo 没有成功：没有独立对端应答 ARP，VPP 收到自己发出的 ARP 后报 `IP4 source address matches local interface` 并丢弃。接口显示的 drops 为 2。因此这项是接入及 ARP 回环验证，没有生成 VPP + DPDK 的 TCP Echo 吞吐或延迟数据。

VPP 的专用驱动表未识别 `net_ring`，日志报告 `unknown driver`，但通用 ethdev 路径已完成上述接口与收发验证。日志还包含 ring 不支持的 MTU、RSS、MAC 操作；不能据此声称完整网卡功能都可用。

同一个 VPP 进程中的 ring 可以供自定义流量发生器使用。两个独立进程分别创建名为 `net_ring0` 的设备不会自动连通；当前 `--no-huge` / `--no-shconf` 配置也不支持常规 DPDK primary/secondary 共享模式（见下方 EAL 文档）。完整的 VCL TCP Echo 对比仍需一个能与该 ring 交换 TCP 报文的独立逻辑对端。

原始证据：[验证摘要](benchmarks/2026-11-14-vpp-dpdk-ring/validation.json)、[接口计数](benchmarks/2026-11-14-vpp-dpdk-ring/show-interface.txt)、[硬件与 polling 队列](benchmarks/2026-11-14-vpp-dpdk-ring/show-hardware-interfaces.txt)、[DPDK EAL 参数](benchmarks/2026-11-14-vpp-dpdk-ring/show-dpdk-version.txt)、[节点运行计数](benchmarks/2026-11-14-vpp-dpdk-ring/show-runtime.txt)、[完整启动日志](benchmarks/2026-11-14-vpp-dpdk-ring/vpp.log.txt)。

## 复现与原始证据

源码、构建选项、编译日志、命令、线程亲和性和二进制哈希见 [ring 构建环境](benchmarks/2026-11-14-dpdk-ring/build-environment.json)。ring [结果 JSON](benchmarks/2026-11-14-dpdk-ring/results.json)及 [64 B 日志](benchmarks/2026-11-14-dpdk-ring/testpmd-64.log.txt)、[1024 B 日志](benchmarks/2026-11-14-dpdk-ring/testpmd-1024.log.txt)均已归档。

TCP [结果 JSON](benchmarks/2026-11-14-tcp-reference/results.json)、[CSV](benchmarks/2026-11-14-tcp-reference/results.csv)、[环境及 Java 源码哈希](benchmarks/2026-11-14-tcp-reference/environment.json)、[VPP counters](benchmarks/2026-11-14-tcp-reference/vpp-counters-0.txt)与脚本快照已归档。两组目录均包含 `SHA256SUMS`。

```bash
python3 scripts/benchmark-dpdk-ring.py \
  --binary /tmp/netty-vcl-deps/dpdk-25.07/build-ring/app/dpdk-testpmd \
  --output target/ring-new --warmup 5 --seconds 10

JAVA_HOME=/tmp/netty-vcl-deps/jdk/jdk-26.0.2.1+1 \
VPP_PREFIX=/tmp/netty-vcl-deps/vpp/usr \
scripts/benchmark.sh --warmup 5 --seconds 10 --repeats 1 \
  --client-cpu 0 --server-cpu 1 --vpp-cpu 2 --link-type netkit \
  --output target/tcp-new
```

ring 脚本为命令行额外明确指定 `--no-shconf --no-telemetry --nb-cores=1 --forward-mode=io`，并用 `stdbuf` 关闭交互输出缓冲，保证统计输出先于下一提示符被采集。速率从两次 `show port stats all` 的计数差和单调时钟间隔计算；没有把第一次显示的零瞬时速率当作结果，也没有将 RX 与 TX 相加。

官方参考：[DPDK 25.07 ring PMD](https://doc.dpdk.org/guides-25.07/nics/pcap_ring.html)、[EAL 参数](https://doc.dpdk.org/guides-25.07/linux_gsg/linux_eal_parameters.html)、[本次 ring 驱动源码快照](benchmarks/2026-11-14-dpdk-ring/rte_eth_ring.c)、[io forwarding 源码快照](benchmarks/2026-11-14-dpdk-ring/iofwd.c)。
