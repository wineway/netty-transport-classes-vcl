# 本地性能基线

这组数据比较同一 Netty 测量代码在 epoll、VCL interrupt 和 VCL poll 上的实际结果。它是本地探索基线，没有生产性能门槛；memif 与 kernel loopback 的网络路径不同，不能将差异全归因于 Java transport。

## 环境与方法

- JDK 26.0.2.1+1，Netty 4.2.20.Final，VPP v26.10，libvppcom 采用已批准的 segment 补丁。原始版本、库/源码/补丁哈希及 CMake 配置见 [environment.json](results/benchmark/environment.json) 和同目录文件。
- 本机 Intel i7-11700T，8 物理核/16 逻辑 CPU、单 NUMA 节点；VPP 固定逻辑 CPU 0、1，客户端 JVM 2、3，对端 JVM 4、5。NUMA 内存策略为默认（唯一 node 0），未关闭 SMT/Turbo 或固定 CPU 频率。硬件详情随 environment.json 保存。
- VCL 使用两个独立 VPP，经 memif 传输 TCP；静态 ARP/NDP，MTU 9000。epoll 使用两个 JVM 和内核 loopback。没有物理 NIC/跨主机数据。每个 JVM 两个固定 EventLoop，VPP 本身保留其默认运行方式，interrupt 指 VCL MQ 通知模式。
- VCL RX/TX FIFO 各 1 MiB、预分配 8 对、heap 256 MiB、初始 segment 64 MiB；Netty pooled direct allocator。epoll 使用内核默认缓冲区及自动调节，不能将其和 VCL FIFO 视为等容量对照。
- 每场景每连接预热 1000 条；随后测量 3 次。每后端独立 JVM，顺序为 epoll → interrupt → poll；未随机化次序，也未声称 JIT 已完全稳定。

| 场景 | 报文大小 | 连接 | 每次/每连接消息数 | 发送模型 |
| --- | ---: | ---: | ---: | --- |
| request-response | 64 B | 1 | 8000 | outstanding=1，请求/响应 |
| fixed-arrival | 64 B | 1 | 5000 | 每 100 µs 一个计划到达，最多 256 outstanding |
| burst | 64 B | 1 | 8192 | 每 1 ms 到达 32 条，最多 256 outstanding |
| multi-connection | 64 B | 8 | 2000 | 每连接 outstanding=16 |
| bulk | 65536 B | 1 | 2048 | outstanding=16 |

报文长度包含 8 字节序号/校验头；吞吐量为完整回传的应用报文字节数/耗时，未计协议头，也没有将 echo 的双向字节再乘二。固定到达与突发延迟从计划发送时刻算起，包含调度迟到；另存实际发送到收到响应的 RTT，避免只报告 RTT 隐藏排队。入队窗口满或 Channel 不可写时，开放到达负载记录 dropped=-1，不把该请求从统计中静默消失。

## 结果

下表各指标分别取 3 次测量的中位数；p99 等为“每次分位数的中位数”，并非合并样本后的分位数。CPU 为客户端 JVM + 对端 JVM + 两个 VPP 的进程 CPU 时间之和，单位 ms/千条成功回传。epoll 没有 VPP 项。

| Backend | 场景 | msg/s | MiB/s | p50 µs | p99 µs | p99.9 µs | CPU ms/千条 | 丢弃到达数 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| epoll | request-response | 61,386 | 3.75 | 13.6 | 38.6 | 358.5 | 46.25 | 0 |
| epoll | fixed-arrival | 9,999 | 0.61 | 61.6 | 508.8 | 1150.4 | 22.00 | 0 |
| epoll | burst | 32,096 | 1.96 | 162.2 | 2645.8 | 2987.2 | 7.32 | 0 |
| epoll | multi-connection | 880,384 | 53.73 | 45.9 | 1925.9 | 2933.4 | 2.50 | 0 |
| epoll | bulk | 40,554 | 2,534.65 | 383.9 | 540.9 | 569.6 | 92.77 | 0 |
| interrupt | request-response | 31,460 | 1.92 | 21.7 | 262.2 | 610.7 | 138.75 | 0 |
| interrupt | fixed-arrival | 9,999 | 0.61 | 80.3 | 1676.4 | 3583.9 | 288.00 | 0 |
| interrupt | burst | 32,087 | 1.96 | 155.1 | 1781.1 | 2958.7 | 73.24 | 0 |
| interrupt | multi-connection | 1,518,001 | 92.65 | 46.1 | 1245.0 | 1491.9 | 2.50 | 0 |
| interrupt | bulk | 19,963 | 1,247.70 | 750.3 | 2606.4 | 3682.0 | 224.61 | 0 |
| poll | request-response | 39,186 | 2.39 | 15.6 | 60.3 | 2949.1 | 157.50 | 0 |
| poll | fixed-arrival | 10,000 | 0.61 | 23.9 | 5759.1 | 9232.6 | 594.00 | 0 |
| poll | burst | 32,093 | 1.96 | 71.1 | 4521.7 | 5566.6 | 187.99 | 0 |
| poll | multi-connection | 1,175,050 | 71.72 | 55.4 | 1584.1 | 1911.3 | 5.00 | 0 |
| poll | bulk | 8,552 | 534.50 | 978.3 | 6158.1 | 6729.6 | 698.24 | 0 |

数据不支持“poll 全面更快”的结论：这轮 poll 的固定到达 p50 较小，但 p99 和 p99.9 明显增大，同时空载与测量期 CPU 成本更高。多连接和大块场景的相对结果也不同，应保留各场景原始数据而不是合成单一速度排名。

## 分配、GC 与空载 CPU

分配为两 JVM 的 ThreadMXBean 累计 Java 堆分配字节差值，不包含 native/FIFO/direct 内存的分配率。下表分配为各次 bytes/成功消息的中位数，GC 为 3 次测量合计（两个 JVM 相加）。

| Backend | 场景 | Java 分配 B/条 | GC 次数 | GC ms |
| --- | --- | ---: | ---: | ---: |
| epoll | request-response | 288.1 | 0 | 0 |
| epoll | fixed-arrival | 368.8 | 0 | 0 |
| epoll | burst | 149.3 | 1 | 4 |
| epoll | multi-connection | 149.8 | 0 | 0 |
| epoll | bulk | 411.8 | 0 | 0 |
| interrupt | request-response | 1402.6 | 1 | 5 |
| interrupt | fixed-arrival | 1482.8 | 1 | 5 |
| interrupt | burst | 375.0 | 0 | 0 |
| interrupt | multi-connection | 383.9 | 1 | 2 |
| interrupt | bulk | 1367.9 | 0 | 0 |
| poll | request-response | 6660.4 | 3 | 7 |
| poll | fixed-arrival | 26579.7 | 5 | 19 |
| poll | burst | 9873.5 | 4 | 16 |
| poll | multi-connection | 645.6 | 0 | 0 |
| poll | bulk | 30968.6 | 3 | 7 |

| Backend | 空载窗口 ms | 客户端 CPU ms | 对端 CPU ms | VPP CPU ms |
| --- | ---: | ---: | ---: | ---: |
| epoll | 250.1 | 0.0 | 0.0 | 0.0 |
| interrupt | 250.1 | 0.0 | 30.0 | 500.0 |
| poll | 250.1 | 500.0 | 590.0 | 510.0 |

CPU 采样边界存在对端控制请求开销；VPP /proc CPU 为时钟 tick 粒度，短批次可能量化为零或明显抖动。因此不据这些短批次推导精确核数或长期容量。统计是进程 CPU 成本，未覆盖所有独立内核 softirq 线程或整机后台任务。

## 原始数据与复现

[results/benchmark](results/benchmark/) 保存每后端的 benchmark.csv、逐消息 latency.csv.gz（无损压缩）、idle.csv、原始日志、JUnit XML 和 VPP counters。backpressure 统计 Channel 不可写的观察次数；window_stalls 独立统计工作负载窗口限制。errors 非零或响应不完整会令测试失败，不生成伪成功结果。dropped 指负载发生器拒绝的到达，不等同网络丢包；VPP counters 包含预热及控制流量，不是精确测量区间抓包。

这轮 45 个测量批次没有到达丢弃；之前独立的一轮在 epoll burst 合计丢弃 160 次到达，原记录保留在 [results/benchmark-pilot](results/benchmark-pilot/)。短基准有明显波动，不能只挑选有利批次。60 秒持续负载的公平性另见 results/stress，它不是本表的性能样本。

运行命令见 README.md，将网络 runner 的 tests 设为 TransportBenchmarkIT。重新生成主表：

```sh
python3 transport/summarize-benchmark.py transport/results/benchmark
```

采集后对 NativeCalls 的 library lookup/固定句柄增加了按库路径的进程级复用，避免多次 runtime 初始化重复持有 global Arena 的动态库引用；该操作发生在测量前初始化阶段。初始化失败/重试对这个交付版本另行回归，原实验哈希保留不改写。VclServer 的 handler supplier 另改为每个 child 调用；基准对端使用直接 ServerBootstrap，不经过该 helper，helper 另外运行了多连接原生回归。两处已暂缓 VPP server 候选补丁均未用于本次基准；每后端新启动 VPP，基准在一个 runtime 生命周期内完成，不代表重复启停资源验收。
