# VCL 与 Netty epoll 性能对比：VPP 独立核心复测

**结论：本次环境与资源配置下，当前 VCL MVP 在全部 3 个测试场景中均慢于 epoll，且服务端 Java + VPP 的合计 CPU 消耗更高。**

这反映当前实现、AF_PACKET 路径、服务端 JVM 与 VPP 分别绑核的预算下的结果；不能推断 DPDK 或真实 NIC 场景的上限。

本次与[历史单核报告](BENCHMARK.md)的硬件、内核和接口类型均不同，不能将两份报告的差值直接归因于 VPP 独立绑核。本次 epoll 使用一个服务端 JVM 核，VCL 额外分配一个 VPP 核；未测 epoll 两个 I/O worker 的同总 CPU 预算对照。

后续[瓶颈诊断](VPP-BOTTLENECK-DIAGNOSIS.md)追加了 AF_PACKET interrupt/polling 单轮对照：64B 延迟明显改善，1KiB吞吐未改善；该诊断与以下三轮正式数据分别保留。

## 结果

数值是 3 轮独立测量的中位数；p99 列是各轮 p99 的中位数，不是将各轮样本合并后的百分位。

| 场景（帧长 / 连接数 / 每连接窗口） | transport | 消息/秒 | 单向应用 MiB/s | RTT p50 μs | RTT p99 μs | 服务端 CPU 核 | 负载端 CPU 核 |
|---|---|---:|---:|---:|---:|---:|---:|
| 64B / 1 / 1 | epoll | 111,422 | 6.80 | 8.7 | 11.1 | 0.392 | 0.434 |
| 64B / 1 / 1 | vcl | 11,455 | 0.70 | 85.9 | 106.1 | 1.025 | 0.057 |
| 1024B / 32 / 16 | epoll | 824,230 | 804.91 | 609.3 | 724.0 | 0.614 | 0.689 |
| 1024B / 32 / 16 | vcl | 505,186 | 493.35 | 1,073.2 | 1,341.4 | 0.799 | 0.578 |
| 65536B / 8 / 4 | epoll | 24,649 | 1,540.58 | 1,213.4 | 2,316.3 | 0.553 | 0.587 |
| 65536B / 8 / 4 | vcl | 7,587 | 474.20 | 4,354.0 | 6,914.0 | 0.626 | 0.429 |

服务端 CPU 列包含 JVM 的 user + system 时间；VCL 额外计入 VPP 进程。1.0 核表示一个逻辑核被完整占用。应用吞吐按完成回显的帧数 × 帧长计算，是单方向数值；每帧含 24B 基准头，不包含 TCP/IP/以太网头。

| 场景 | VCL 吞吐 / epoll | VCL p99 / epoll | epoll 消息/服务端 CPU 秒 | VCL 消息/服务端 CPU 秒 |
|---|---:|---:|---:|---:|
| latency-64B | 10.3% | 9.58× | 284,547 | 11,180 |
| throughput-1KiB | 61.3% | 1.85× | 1,343,939 | 631,560 |
| bulk-64KiB | 30.8% | 2.98× | 44,364 | 12,176 |

## 重复测量范围

下列 min–max 是 3 轮观察范围，不是置信区间。

| 场景 | transport | 消息/秒 min–max | p99 μs min–max | JVM CPU 中位数 | VPP CPU 中位数 |
|---|---|---:|---:|---:|---:|
| latency-64B | epoll | 111,023–113,490 | 10.8–11.4 | 0.392 | 0.000 |
| latency-64B | vcl | 11,438–11,485 | 103.9–109.4 | 0.050 | 0.975 |
| throughput-1KiB | epoll | 823,939–890,679 | 711.2–739.8 | 0.614 | 0.000 |
| throughput-1KiB | vcl | 504,533–505,959 | 1,319.9–1,345.5 | 0.152 | 0.647 |
| bulk-64KiB | epoll | 24,541–25,891 | 1,559.6–2,330.6 | 0.553 | 0.000 |
| bulk-64KiB | vcl | 7,570–7,630 | 6,877.2–6,934.5 | 0.083 | 0.543 |

## 测试方法与资源预算

- 时间：2026-11-13T14:01:10Z。CPU：11th Gen Intel(R) Core(TM) i7-11700T @ 1.40GHz；系统：7.2.8-arch1-1。
- openjdk version "26.0.2.1" 2026-08-18；Netty 4.2.7.Final、vpp v25.10-release built by root on a55774381098 at 2025-10-29T10:59:07；两端 JVM 均为 256MiB heap、G1、相同 JVM 参数。
- 容器可见 CPU 为 [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]，cgroup `cpu.max=max 100000`，无 CPU 配额限制。负载端固定 CPU 0；服务端 JVM 固定 CPU 1，VPP 单独固定 CPU 2，VCL 服务端最多使用 2 核，epoll 服务端最多使用 1 核。
- 两个 transport 都固定 1 个服务端 I/O worker；epoll 在测试时没有 VPP 进程争用 CPU。VPP 的 polling 时间完整计入其预算。
- 公共负载端始终使用 Netty epoll。服务端业务 Handler 完全相同，均直接回显 ByteBuf，无额外业务逻辑或 server codec。
- 两端位于不同网络命名空间，始终经过同一 netkit L2 接口对。epoll 由 Linux TCP 处理；VCL 由 VPP AF_PACKET/TCP 处理，不走 VPP 本地 cut-through。
- 双端关闭 TSO/GSO/GRO/TX checksum offload，验证开关确实为零；VPP 使用 TPACKET_V2、RX/TX 各 1024 个 2048B frame。未启用 DPDK、hugepage 或 packet trace。
- 每组预热 5 秒、计量 10 秒，重复 3 轮；轮换 epoll/VCL 的先后顺序。每组使用新客户端 JVM，同一轮/transport 的服务端 JVM 覆盖本轮全部场景。
- 固定帧请求/回显，持续补充每连接窗口；校验 magic、连接号、序列号和末字节，结束后要求所有在途请求 drain 成功。
- RTT 从客户端入队发送到完整回显被解析计时，包含排队、flush 和 TCP 往返。使用 HdrHistogram（三位有效数字）；闭环负载，无 coordinated-omission 修正。
- 服务端/VPP CPU 从 `/proc/PID/stat` 在测量窗口两端取差，包含进程所有线程；负载端用 ProcessHandle CPU 时间。内核软中断 CPU 的归属并未单独隔离。

## 解释与适用范围

- VPP 与服务端 JVM 使用不同逻辑核；VCL 比 epoll 多分配一个 VPP 核。合计 CPU 与每 CPU 秒消息数用于比较资源效率。绑核不保证宿主机独占。
- 负载端 CPU 接近 1 核的样本可能受生成器限制，此时测得的吞吐是该端到端配置的能力，不能当作服务端最大吞吐。
- 单个测量窗口的 cgroup throttled 时间最高为 0.0ms；详细计数保存在原始 JSON。全部应用帧校验及 drain 成功。VPP counters 记录了 OOO packets pushed into rx fifo / Old segment 计数，未进一步定位；正式测量未记录 TX ring overrun / temporary failure。
- 当前 VCL 使用标量 FFM read/write、LT readiness 及较多 interest 更新；epoll 使用成熟 native transport。未通过 profile 区分 polling、TCP、AF_PACKET 和调度的贡献，不能将差距直接归因于 FFM。
- AF_PACKET 包含内核路径成本；关闭 offload 是本报告的显式条件。DPDK、硬件 offload 及不同 CPU 预算需在目标环境中单独测量。
- 本轮未测新建连接/秒、固定到达率下的延迟、长期稳定性，也未测 epoll 多 worker 的最大扩展能力。

## 依赖兼容与实际绑核验证

- 当前用户空间为 Ubuntu 26.04（resolute）amd64、glibc 2.43；VPP/VCL 25.10 官方包面向 Ubuntu 24.04（noble）amd64，发行版并非完全匹配。所有包仅解压到 `/tmp/netty-vcl-deps`，未替换系统安装的 VPP 26.06。
- 下载包 SHA256 与仓库元数据一致；VPP 主程序、libvppcom、AF_PACKET 插件的动态依赖全部可解析。完整插件包声明的 libpcap 依赖未安装，本次仅启用 AF_PACKET，不代表整包所有插件均已验证。详情见[包来源和运行环境](benchmarks/2026-11-13-dedicated-vpp/validation-environment.json)及[动态依赖检查](benchmarks/2026-11-13-dedicated-vpp/dynamic-dependencies.txt)。
- 25.10 头文件 ABI 探针、VPP/AF_PACKET 实际启动、4 项单元测试均通过；18 组正式测量全部完成，帧校验与 drain 成功。完整 11 项功能集成测试未在本次复跑。
- CPU 0、1、2 分别属于物理核 0、1、2，SMT sibling 分别为 8、9、10。实际检查 VPP 唯一线程仅允许 CPU 2，服务端 JVM 全部 21 个线程仅允许 CPU 1，负载端全部 20 个线程仅允许 CPU 0。原始检查见[线程亲和性记录](benchmarks/2026-11-13-dedicated-vpp/affinity-checks.jsonl)。未配置宿主机级 CPU 隔离，也未预留 SMT sibling。
- veth 创建实测返回 `Unknown device type`，因此显式使用当前内核支持的 netkit L2；epoll 与 VCL 的短时收发预检均通过后才开始正式测量。

## 复现和原始数据

```bash
mvn -Pbenchmark -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath
VPP_PREFIX=/path/to/vpp/usr scripts/benchmark.sh --warmup 5 --seconds 10 --repeats 3 --client-cpu 0 --server-cpu 1 --vpp-cpu 2 --link-type netkit --output target/benchmark-new
python3 scripts/summarize-benchmark.py target/benchmark-new/results.json --no-plot
```

需 Java 26、Maven、VPP AF_PACKET plugin、`ip`、`taskset`、`nsenter`、`unshare`、Python 3；画图额外需要 matplotlib。仅操作脚本自建的网络空间与 VPP。输出目录必须全新，避免混合不同运行的样本。

原始数据：[JSON](benchmarks/2026-11-13-dedicated-vpp/results.json)、[CSV](benchmarks/2026-11-13-dedicated-vpp/results.csv)、[环境与源码 hash](benchmarks/2026-11-13-dedicated-vpp/environment.json)。保留各轮 VPP counters 供检查丢包、重传和 ring 错误。
