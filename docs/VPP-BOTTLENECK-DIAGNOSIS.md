# VPP/VCL 性能瓶颈诊断

日期：2026-11-13。基于[独立核心正式测量](BENCHMARK-DEDICATED-VPP.md)，追加 AF_PACKET 接收模式对照及 VPP 内置节点计数。

**当前证据支持：64B 延迟的主要问题是 AF_PACKET interrupt 接收与 VPP 文件事件检查节奏；吞吐场景中 AF_PACKET 发送路径是显著成本。尚不能将全部吞吐差距归因于 VPP TCP 或 Java FFM。**

## VPP 为什么没有自动超过 epoll

VPP 通过向量化批处理摊薄调度和指令缓存成本，这并不保证所有 TCP 应用、所有接口都优于 Linux TCP。参考 [VPP 向量处理说明](https://docs.fd.io/vpp/25.10/aboutvpp/scalar-vs-vector-packet-processing.html)。

当前路径为 Linux 客户端 → netkit → AF_PACKET → VPP TCP/session → 共享 FIFO/VCL → Java。AF_PACKET 仍经过内核，发送还有 ring 拷贝和 `sendto` kick；VCL 的共享内存也不代表 Netty ByteBuf 的端到端零拷贝。没有使用 DPDK、真实 NIC、硬件 offload 或 hugepage。64B/1连接/窗口1 的停等负载也没有足够报文形成大的向量。

## AF_PACKET interrupt / polling 单轮诊断对照

保持 Java、网络、帧大小、连接数、窗口、三个绑核位置与正式测量一致。仅改变 AF_PACKET RX 模式；每种模式按 64B、1KiB、64KiB 顺序运行，预热5秒、计量10秒，各1轮。先 interrupt 后 polling，未随机交错，以下结果用于诊断，不能替代三轮正式结果或作为置信区间。

| 场景 | interrupt 消息/秒 | polling 消息/秒 | interrupt p50 μs | polling p50 μs | interrupt p99 μs | polling p99 μs |
|---|---:|---:|---:|---:|---:|---:|
| 64B / 1连接 / 窗口1 | 11,886 | 91,143 | 81.9 | 10.4 | 104.5 | 17.1 |
| 1KiB / 32连接 / 窗口16 | 532,680 | 532,386 | 1,006.6 | 794.1 | 1,331.2 | 3,297.3 |
| 64KiB / 8连接 / 窗口4 | 7,764 | 10,530 | 4,235.3 | 2,543.6 | 6,918.1 | 6,266.9 |

全部6组校验与 drain 成功。接口模式由 `show interface rx-placement` 实际确认，不只依赖配置命令。原始数据：[interrupt](benchmarks/2026-11-13-vpp-diagnostics/interrupt/results.json)、[polling](benchmarks/2026-11-13-vpp-diagnostics/polling/results.json)。

64B 吞吐约提升7.67倍，p50从81.9μs降至10.4μs；1KiB吞吐几乎不变且p99恶化；64KiB吞吐约提升35.6%。因此 polling 解决了显著的小包等待成本，但不是所有场景的完整解决方案。本次未修改默认生产配置或原正式报告。

## 64B 等待时间的源码依据

VPP 25.10 [AF_PACKET 源码](https://github.com/FDio/vpp/blob/v25.10/src/plugins/af_packet/af_packet.c)将新建 RX queue 设为 interrupt。当前正式脚本没有覆盖该模式。

在 [vlib/file.c](https://github.com/FDio/vpp/blob/v25.10/src/vlib/file.c) 中，有输入节点 polling 时，文件事件检查会设置 `file_poll_skip_loops = 1024`；[vlib/main.c](https://github.com/FDio/vpp/blob/v25.10/src/vlib/main.c) 每轮递减该计数，归零后才再次调用 `vlib_file_poll`。诊断运行同时确认 `session-queue` 为 polling、AF_PACKET 为 interrupt。

64B interrupt 的 `show runtime` 记录主循环为12,652,362.72次/秒，故1024次循环约为80.93μs，接近实测RTT p50的81.919μs。这与 polling A/B 的改善共同支持事件检查节奏是主要原因；该换算不是逐包 tracing，不能据此断言每个请求都固定等待1024轮。

该场景 AF_PACKET 平均1.00包/调用，`session-queue` 调用约2亿次、仅处理约34万向量元素。VPP接近满核包含大量无有效消息的循环，不能等同于TCP处理能力已经耗尽。原始记录见[64B interrupt runtime](benchmarks/2026-11-13-vpp-diagnostics/interrupt/runtime-latency-64B-0.txt)。

## 吞吐场景的发送路径成本

以下为 interrupt 诊断的 VPP runtime `Clocks`（近似每个节点处理包的时钟周期）：

| 节点 | 1KiB | 64KiB |
|---|---:|---:|
| host-nvserver-tx（AF_PACKET TX） | 1,940 | 2,570 |
| af-packet-input | 107 | 106 |
| tcp4-established | 176 | 127 |
| tcp4-output | 17.4 | 17.3 |
| session-queue | 453 | 423 |

AF_PACKET TX 的每包成本明显高于这些 TCP 节点。VPP [AF_PACKET device.c](https://github.com/FDio/vpp/blob/v25.10/src/plugins/af_packet/device.c)包含向 TX ring 拷贝及 `sendto` kick。这使 AF_PACKET/内核发送路径成为优先调查对象；节点计数尚不能分开 syscall、复制、内核软中断、调度及等待成本，也不能当作完整进程 CPU 的热点百分比。计数包含预热及正式窗口；源码 [node_cli.c](https://github.com/FDio/vpp/blob/v25.10/src/vlib/node_cli.c)在有向量时按总时钟除以向量数输出 `Clocks`。

1KiB interrupt RX 平均约239.77包/调用，64KiB约253.27包/调用，说明吞吐负载已经形成大批量。单纯强调向量化并不足以解释或消除与epoll的差距。

## Java 适配层与尚未确定的部分

正式测量中 VCL 服务端 JVM 约使用0.050/0.152/0.083核，VPP约使用0.975/0.647/0.543核；不存在JVM满核的证据。吞吐场景VPP也未满核，仍可能有等待、TCP窗口、背压及跨进程通知限制。低CPU不代表这些部分没有延迟成本。

代码中存在重复 readiness interest 更新、通用 FFM 控制调用、标量 read/write 和 ByteBuf/FIFO 复制等可优化点，但尚未做逐项A/B，不能将它们标记为已确认的主瓶颈。64B在不改Java实现的情况下已有大幅改善，也说明不能先将其差距归因于FFM。

`perf stat -e task-clock -- true` 在沙箱内和沙箱外均返回 `No permission to enable task-clock event`。本次没有获得CPU或off-CPU调用栈，因此不声称已完成全路径profile。下一步优先在可采样环境中联合采集VPP/JVM/kernel调用栈、调度和TCP窗口/重传，并比较AF_PACKET与目标NIC/DPDK路径；Java优化再逐项验证。

诊断脚本和VPP源码快照、接口模式、逐场景runtime、CPU计数及校验和保存在[诊断原始目录](benchmarks/2026-11-13-vpp-diagnostics/)。脚本仅运行VCL，并在每场景结束后读取/清零runtime；它不覆盖正式测量脚本。

## JVM 为什么没有吃满：等待采样与忙轮询对照

追加1KiB、32连接、每连接窗口16的诊断。在正式计量的10秒内，由父进程约每2ms读取I/O线程的`stat`、`wchan`、`syscall`，并对`schedstat`取差。采样线程固定CPU 3；客户端、服务端JVM、VPP仍分别固定CPU 0、1、2。以下各1轮，预热5秒、计量10秒，不替代正式三轮结果。

默认阻塞策略的4,557次采样中，3,675次（约80.6%）位于`poll_schedule_timeout.constprop.0`，对应等待VCL通知fd的内核`poll()`。I/O线程实际运行约1.629秒，运行队列等待仅约18.0ms；这表明低CPU主要是主动等待，而非持续就绪但抢不到CPU。采样各字段非原子读取，百分比仅为近似。

为检验是否仅由阻塞等待策略限制吞吐，使用独立诊断class覆盖`VclPoller`：非阻塞VCL epoll无就绪时立即返回，跳过OS阻塞poll，由事件循环持续轮询。原产品源码、`target/classes`和正式数据未改动，AF_PACKET继续使用interrupt。

| 指标 | 默认阻塞 | JVM忙轮询 |
|---|---:|---:|
| 消息/秒 | 515,969 | 495,980 |
| RTT p99 μs | 1,344.5 | 1,435.6 |
| JVM CPU核 | 0.166 | 0.997 |
| VPP CPU核 | 0.646 | 0.656 |
| I/O线程运行秒/10秒 | 1.629 | 9.942 |
| I/O线程运行队列等待ms | 18.0 | 34.5 |

忙轮询的4,538次采样没有观察到内核poll等待（其余少量为futex或运行）。两组均通过校验和drain，cgroup限流为零。将JVM推至接近满核仍未观察到吞吐改善，支持该配置主要受可用数据/发送进度及链路约束，而非JVM处理算力或简单的OS阻塞策略限制；不排除VCL通知逻辑、FIFO背压、TCP窗口、内核发送路径或其他适配问题。

当前客户端是固定窗口闭环负载：收到回显才补发，最多`32 × 16 = 512`条请求在途。正式中位数505,186消息/秒对应`512 / 505186 ≈ 1.01ms`的平均请求在途时间（Little定律，包含客户端排队和整条往返路径）。JVM的摊销CPU消耗约`0.152 / 505186 ≈ 0.30μs/消息`，足以解释为何服务端可以一边完成约50万消息/秒、一边长时间等待。该换算是流量与延迟的关系，不单独证明延迟发生在哪一段，也不意味着吞吐一定能按剩余CPU线性扩大。

另将每连接窗口由16增至128（最多4096条在途）时，结束后仍有128条回显未在5秒内drain，测试失败，因此不报告该组吞吐、不据此推断最大性能。客户端日志与VPP counters一并保留；这也说明当前测试尚不能通过简单增大窗口证明JVM饱和点。故障的具体原因未定位，不能直接归因于丢通知或丢包。

线程采样[汇总](benchmarks/2026-11-13-jvm-wait/sampling-summary.json)、[默认窗口原始结果](benchmarks/2026-11-13-jvm-wait/normal/raw.jsonl)、[忙轮询结果](benchmarks/2026-11-13-jvm-wait/busy/results.json)、[大窗口失败日志](benchmarks/2026-11-13-jvm-wait/normal/client-vcl-window128-0.log)及脚本/诊断class源码在[本轮原始目录](benchmarks/2026-11-13-jvm-wait/)。完整逐次采样以`.json.gz`保存。
