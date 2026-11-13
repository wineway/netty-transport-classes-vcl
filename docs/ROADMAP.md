# 后续开发计划

更新日期：2026-11-13。面向实现、测试和评审人员；阶段按顺序执行，每阶段独立交付可复现结果。

## 目标与当前依据

保持 **Java 26、Netty 4.2、纯 FFM、无运行时 JNI/C shim、VCL 数据面、多 worker TCP 服务端**。先补齐正确性证据并定位成本，再做优化和生产验收。

现有功能验收为 4 项单元测试、11 项真实 VPP 集成测试；现有性能报告包含 18 个样本。单核服务端预算下，VCL 的吞吐为 epoll 的 17.2% / 42.6% / 30.0%，p99 RTT 约为 3.9–4.3 倍。

VCL JVM 仅使用约 0.054–0.162 核，VPP 使用约 0.836–0.946 核。这个分拆不足以证明 FFM 是主要瓶颈，也不能区分 VPP idle polling 与有效报文处理。AF_PACKET、关闭 offload、VPP/Java 同核和部分 cgroup throttling 均是当前结果的适用条件。

默认主性能场景为 **1KiB / 32 连接 / 窗口 16**；64B 场景保护延迟，64KiB 场景保护带宽。业务协议、连接规模及生产 SLO 尚未指定，暂不据此承诺上线或性能收益。

## 阶段与交付

| 阶段 | 优先级 | 主要交付 | 完成条件 | 估算人日 |
|---|---|---|---|---:|
| P0：补强正确性与构建隔离 | 最高 | wire 大消息/竞态测试、独立 benchmark 产物 | 原有测试及新增回归全部通过，产品包无 benchmark 依赖泄漏 | 1–2 |
| P1：定位性能差距 | 最高 | profiler 记录、双 CPU 预算矩阵、瓶颈结论 | 分清数据面、调度和 Java 适配成本，并排除负载端饱和 | 2–3 |
| P2：优化传输适配层 | 高 | interest 去重、FFM 精确调用、事件循环可观测性 | 正确性通过，每项优化有单独 A/B 结果 | 2–4 |
| P3：验证部署形态与扩展性 | 高 | VPP 独占核、NIC/DPDK 对照、多核报告 | 同总 CPU 预算比较，覆盖吞吐/尾延迟/连接速率 | 3–5 |
| P4：工程化与稳定性 | 高 | 长时压测、故障恢复契约、发布与 CI | 资源稳定、故障有界收敛、交付可复现 | 2–4 |

估算按一名工程师有效工作量计算，不包含硬件准备、外部环境审批及长时测试等待时间。P1/P3 需要额外资源，当前容器总配额 2 核不能完成其独占核对照。

### P0：正确性和产物边界

- 增加真正经过 VPP TCP/接口的 16MiB 全内容比对，覆盖两个方向、慢接收端、部分写和恢复读取；现有基准只检查帧头/序号/末字节，不能代替完整数据测试。
- wire 测试分别覆盖 offload 关闭和开启。先配置足够的 AF_PACKET frame/ring 容量，避免将测试拓扑截断或 ring 溢出误判为 Channel 缺陷；保留接口及 TCP counters。
- 增加持续有 I/O 时的跨线程任务/定时器公平性、暂停读取期间 FIN/RST、关闭与取消竞争、session handle 复用及失败初始化清理测试。
- 审核重复注册、deregister 后重新注册、Runtime 多次创建销毁的边界：同一 EventLoop 的受支持路径可正常恢复，跨 EventLoop 明确拒绝，失败路径不得遗留 session 或未完成 Promise。
- 将 benchmark 移到独立 Maven 模块/输出目录，避免 profile 切换后旧 class 留在产品 JAR；产品与 benchmark 分别构建、分别检查依赖和包内容。

交付：正确性补丁、测试列表、独立产品/基准构建命令。保留现有行为；不在这个阶段重写事件模型。

### P1：先获得可解释的性能结果

- 保留当前每侧单核预算作为回归基线；增加至少 4 个可用独占核且无不足额 quota 的测试环境，负载端用 2 核，服务端总预算用 2 核。
- 对照 A：epoll 2 个 I/O worker 使用 2 核，VCL 1 个 I/O worker + 1 个 VPP 数据面核使用相同 2 核。补充 epoll/VCL 均 1 个 I/O worker 的控制组，避免把线程数变化混入结论。
- 负载端保持相同代码和校验策略。增加负载端线程/连接分片，确认主场景负载端不持续饱和；否则报告为生成器受限结果，不据此判断服务端上限。
- 正式 A/B 默认预热 10 秒、计量 30 秒、至少 5 轮，交替顺序；记录 affinity、NUMA、quota/throttling、offload、VPP 配置、版本和源码 hash。原始数据不可覆盖。
- Java 使用 JFR CPU/allocation/停顿记录；native 使用 perf 的 CPU、调度和上下文切换采样，并结合 VPP runtime/counters。带 profiler 的运行与正式计量分开，防止采样开销污染性能结果；perf 不可用时记录缺口，不虚构 native 归因。
- 增加每轮事件批量、空轮询、wait/wakeup、interest ADD/MOD/DEL、EAGAIN、任务延迟的计数；耗时采样可关闭，默认不对每次 native 调用计时。
- 分别记录 VPP 空载和有载 CPU、AF_PACKET/TCP 处理、VCL/FFM、JVM/GC 及调度等待。检查并定位 Old segment / PAWS counters；确认是否与重传、时间戳或测试配置相关。

交付：瓶颈报告及按预期收益排序的优化清单。若主要成本在 VPP/AF_PACKET 或核心争用，优先处理对应配置/部署，Java 优化不承担弥补全部差距的目标。

### P2：对当前代码做可验证的优化

按下面顺序实施，每项单独提交和比较，避免多个改动同时上线导致无法归因：

1. **interest 去重。** 在 `VclIoHandler` 的 registration 中缓存已成功提交的 mask，mask 未变化时不调用 epoll_ctl；失败不更新缓存。保持零兴趣 DEL、恢复 ADD、关闭幂等和过期 token 防护。
2. **FFM 控制调用精确化。** 将 epoll_ctl、detached、error 等高频调用从 `invokeWithArguments` 改为精确 MethodHandle 调用；保持现有 read/write/epoll_wait 的精确调用，避免装箱和 varargs 分配。继续使用 FFM errno capture。
3. **降低事件分发分配。** 根据 allocation profile，减少事件/操作包装对象和热点映射查找的分配；生命周期和陈旧事件处理规则保持一致。
4. **事件循环参数化。** 新增不可变 `VclIoOptions`，由 `VclIoHandler.newFactory(runtime, options)` 使用；现有工厂签名保留并使用默认值。首版只暴露 `maxEvents=256`、`maxWaitMillis=10`，校验正数和上限；所有等待继续服从 Netty deadline。
5. **等待与公平性调优。** 先优化阻塞模式；只有 profile 证明 wakeup/调度占主要成本时，才实验有界 spin-then-block。实验独立开关、默认关闭，不在共享 CPU 上默认 busy-poll。
6. **批量 I/O 专项。** 仅当 profile 证明 downcall/复制占显著成本时，验证所固定 VCL 版本的 segments API，再决定是否增加批量收发；逐项处理部分消费、FIFO 回绕、ByteBuf 引用计数和关闭竞态。

新增 metrics 使用现有不可变 snapshot 扩展，并记录接口变化。`VclServerBootstrap`、Channel 类型和平台线程归属契约继续保持。

验收：P0 全部回归通过；每项优化在主场景达到跨轮稳定、超过测量噪声的收益，保护场景不得出现可重复的吞吐/尾延迟退化。证据不清晰的复杂改动不合入默认路径；不预设必须提升的百分比。

### P3：VPP 部署与多核能力

- 完成独占 VPP 核、应用核和 NUMA 本地内存/FIFO 的配置，统计所有服务端进程、辅助线程和中断成本。
- 在真实 NIC 环境比较 kernel TCP/epoll 与 VPP；DPDK 作为独立测试路径。使用相同 NIC/MTU/链路，记录驱动、队列、RSS、offload 和 core 分配，不能只对 VCL 增加 CPU 或硬件能力。
- 按服务端总 CPU 预算 2/4/8 核扩展，分别记录 I/O worker 与 VPP worker 配比、连接分布、每 worker 负载及吞吐扩展效率；沿用每 worker 监听和持有连接，不增加 session 迁移。
- 增加 1/32/256/1024 连接、大消息持续流和新建连接/秒测试；连接 churn 监控 TIME_WAIT/临时端口容量，避免将客户端端口耗尽误判成服务端极限。
- 增加固定到达率测试，按已测稳定容量的 25%/50%/75%/90% 施压，报告队列延迟、超时和丢弃；闭环 RTT 和固定到达率延迟分别报告。

交付：目标环境下的资源/性能曲线和推荐配置。是否满足业务 SLO、是否具有相对 epoll 的收益，在此阶段做结论；不把 AF_PACKET 单核结果或 DPDK 结果互相外推。

### P4：稳定性与发布

- 先做 1 小时 smoke soak，再做至少 24 小时混合测试：稳定流量、连接 churn、慢消费者、暂停/恢复读取、FIN/RST、频繁关闭和多 worker 压力。
- 分开统计 heap、direct memory、native RSS/共享段、fd、session、registration 和待完成 Promise。检查稳态及停止负载后的回落，区分池化缓存与持续泄漏。
- 追加 application socket 中断、VPP 正常退出/异常退出、资源耗尽、服务端滚动停止测试，验证错误传播和优雅关闭。
- 新增 runtime health/status 查询，明确区分初始化失败、部分 worker 失败和全部不可用；readiness 不再只由 JVM 存活或 worker 数量判断。
- 保持故障契约：旧 TCP Channel 不恢复；应用在明确失败后重新建立连接。重建 Runtime/Group 提供示例与回归，不隐式重放业务写入。
- CI 分成无 VPP 的单元检查、固定 VPP 版本的真实接口集成检查、独立性能任务；发布前执行 ABI 探针和依赖/产物检查，附兼容矩阵、配置示例和限制说明。
- 应用显式选择 VCL 或 epoll 作为部署配置；不在一个活动 Channel 内静默切换数据面。灰度先扩大实例比例，保留配置级回退到 epoll 的能力。

交付：稳定性报告、故障矩阵、CI、运行手册和候选发布包。没有业务 SLO 和目标部署环境验收前，不标记生产就绪。

## 暂缓事项与决策点

- 暂缓 UDP、IPv6、跨 worker session 迁移、动态扩缩容、VCL TLS 和零拷贝 ByteBuf；优先完成 TCP 正确性和可解释的性能结果。
- 不引入运行时 C shim，不改回 JNI。C 仅用于测试阶段验证 ABI；生产调用继续由 Java FFM 完成。
- 不使用未知生命周期的 FIFO 内存直接构造 Java ByteBuf；零拷贝必须另开设计，明确所有权、释放顺序、流控和异常退出。
- P1 后确定主要投入点，P3 后确定是否继续以性能收益为目标。如果缺少真实部署条件，只交付当前条件下的结果，不用额外调参替代目标环境验证。
- 下一批建议提交顺序：wire 全量校验及构建隔离 → 可观测性与 profile → interest 去重 → FFM 精确调用 → 独占核复测。后续实验以证据决定。

参考：[现有性能报告](BENCHMARK.md)、[功能验证记录](VALIDATION.md)、[架构说明](ARCHITECTURE.md)。
