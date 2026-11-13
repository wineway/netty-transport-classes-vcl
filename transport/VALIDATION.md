# 验证记录

本地实际执行，未使用远程 checkout，也未读取或恢复旧实现。Netty `4.2.20.Final`，Java 26.0.2.1+1，VPP v26.10 加用户批准的 libvppcom segment 补丁。VPP server 使用 pristine 源码；两个暂缓候选均未应用。

| 层次 | 实际结果 | 证据/范围 |
| --- | --- | --- |
| Java ABI、状态机、等待、生命周期 | 30 项通过 | `results/unit/`；模拟 I/O 与 libc 等待测试，不冒充 VPP |
| 同组真实网络契约 | epoll、interrupt、poll 各 10 项通过 | `results/network/`，VCL 为同机双 VPP memif，epoll 为 kernel loopback |
| 原生初始化失败 | 两种模式通过 | `results/initialization/` 与交付版 `results/initialization-cache/`，不存在的 SAPI socket 使 attach 失败，fd/mapping 回到基线且可重新初始化 |
| VPP 退出 | 两种 VCL 模式通过 | `results/failure/`，已有 Channel 关闭，8 MiB 待写失败/释放，后续注册和写入失败 |
| 原 segment 内存错误 | 两模式原复现 ASAN 回归通过 | `../preflight/results/diagnostics/segment-fixed-asan/`，完整补丁和构建参数归档 |
| 多 listener helper | 两种 VCL 模式各 40 次连接通过 | `results/server/`，两个 listener 都接受连接，child 与 parent 同 EventLoop；关闭一个后另一个继续接收 20 次 |
| 持续负载公平性 | 三后端各 60 秒、8 连接通过 | `results/stress/`，每个 loop 每 10 ms 定时任务，记录 tick/max lag，逐字节校验数据 |
| JVM 空载重复启停 | interrupt 8 次 fd 稳定、映射归零 | `results/resource-empty/`；不代表 VPP server 无泄漏 |
| 带会话重复启停 | 失败，保留断言 | `results/resource-failure/`；后续 listen 超时，VPP CLI 超时；独立 SAPI 探针定位未 attach 连接回收缺陷 |
| CT 半关闭 | 失败，用户暂缓修复 | `results/ct-half-close-failure/` 及 `../preflight/NATIVE-HALF-CLOSE.md` |
| 三后端完整 Netty 基准 | 5 场景 × 3 次，各后端完成 | `results/benchmark/`，原始逐消息样本、汇总、CPU/分配/GC 与 VPP counters；参见 BENCHMARK.md |
| 物理 NIC/跨主机 | 未执行 | 不将 memif 或 kernel loopback 称为物理网络结果 |

网络契约覆盖 IPv4/IPv6 TCP 128 KiB 往返及地址、UDP 来源/接收截断、bind(0)、connect/disconnect/reconnect、未绑定写入失败后的 connect、TCP 输出半关闭继续接收、手动读取/autoRead、保留 ByteBuf 和 NIO 视图跨关闭、RST 连接拒绝，以及对端暂停读取时 16 MiB 写入阻塞/恢复、任务和定时器进展。

每个网络测试类使用一个 runtime，重复创建/销毁 runtime 的验证另由 NativeResourcesIT 承担并保留失败，避免把资源缺陷隐藏成成功。静态 ARP/NDP 写入测试 VPP，排除第一次 IPv6 UDP 因邻居发现而丢包；真实网络 UDP 本身仍是尽力交付协议。

模拟状态机测试另覆盖连接取消/超时/Promise 与 active 事件顺序、句柄 token 复用、外线程迁移拒绝、partial write、heap/composite 引用计数、半关闭、multi-listener 部分失败回滚及全 runtime 失败广播。这些覆盖不能替代相同情形的原生长期故障注入。

尚未完成的完整验收包括：两处用户暂缓的 server 修复、更长时间负载与 worker 索引反复复用、原生注册失败路径的全面注入、所有 FIN/RST 竞争、CT 地址，以及尚未确认的扩展选项边界。详细待办见根目录 TODO.md。当前产物可供集成评审，不能据此宣称全部批准计划验收通过。

历史文件保留当时配置和哈希；后续源码变化不会回写原实验 JSON。`SHA256SUMS` 用于检查本目录交付文件完整性。网络及 fault 的 environment.json 包含实际源码、库/补丁和服务器构建来源；基准说明标明测量窗口和方法限制。
