# 实施进度与待办

包名 `me.wineway.vcl`。从零开发，不读取或恢复旧实现；保留用户工作区/暂存区状态。原始 Netty 和 VPP checkout 不修改。实际验证范围见 [transport/VALIDATION.md](transport/VALIDATION.md)。

## 已完成

- [x] 本地固定 Netty 4.2.20.Final、VPP v26.10、JDK 26，C/FFM ABI 探针、worker 分发与等待实验。
- [x] RX/TX/UDP segment 研究；用户批准池化 direct RX 和首版排除零长度 UDP。
- [x] 用户批准采用独立 libvppcom segment 补丁；原崩溃的 workers-after-close 两模式 ASAN 回归通过，原始记录已归档。
- [x] FFM、runtime、固定 worker 的 IoHandler、libc eventfd/epoll 等待及 poll 模式。
- [x] TCP/UDP Channel、配置、非零临时端口适配、多 listener 原子启动/回滚、注册 token 和 ByteBuf 所有权处理。
- [x] 多 listener helper 每个 child 创建 handler；两模式真实 40 连接及独立关闭 listener 回归通过。
- [x] 30 项 Java/ABI/内核等待测试通过；不是原生语义验收替代品。
- [x] epoll 与两种 VCL 模式共同的 10 项真实网络契约各通过，包含 IPv4/IPv6、UDP connect/disconnect、手动读、半关闭、连接拒绝、16 MiB 背压恢复和任务/定时器进展。
- [x] 两种 VCL 模式原生 attach 失败后资源回收和重新初始化通过。
- [x] 两种 VCL 模式 VPP 退出测试通过，检查 Channel、待写数据/引用计数及后续操作失败。
- [x] 用户确认缓存型原生选项与 SO_BACKLOG 首版明确拒绝，见 CAPABILITIES.md。
- [x] 三后端 5 类基准，各预热、测量 3 次；逐消息延迟、到达丢弃、CPU、分配/GC、VPP counters 归档。
- [x] Maven JAR 打包、EchoServer 示例、构建/运行/测试说明。

## 用户明确暂缓，不应用候选补丁

- [ ] CT 半关闭反向通知：`preflight/patches/vpp-26.10-ct-half-close-proposed.patch`；证据 `preflight/NATIVE-HALF-CLOSE.md`。普通网络 TCP 半关闭通过不能替代 CT 验收。
- [ ] 未 attach SAPI 连接回收：`preflight/patches/vpp-26.10-sapi-unattached-close-proposed.patch`；证据 `preflight/NATIVE-SAPI-CLOSE.md`。原版每次关闭监测连接遗留一个 server fd，16 次后 CLI 超时。带会话的重复启停回归保留失败，不能宣布资源验收完成。

## 剩余验收

- [x] 三后端各 60 秒、8 连接持续负载通过；两个 EventLoop 定时器均持续进展，数据完整性检查通过。原始统计见 transport/results/stress。
- [ ] 修复 SAPI 回收后，完成重复 application/worker/会话启停、worker 索引复用和 VPP server/JVM 双侧 fd/mapping 检查。当前仅空载 JVM 侧短测通过。
- [ ] 更完整的原生初始化/注册失败注入、FIN/RST/关闭竞争及 segment 跨 worker 共享映射/迟到控制事件审计；ASAN 原场景回归不等于证明所有顺序安全。
- [ ] CT connected 本地地址/端口为零的问题仍待处理。
- [ ] SO_LINGER、完整 IP_TOS/ECN 与其余扩展选项需完成能力核实及最终边界确认；当前设置明确失败，未擅自视为已批准兼容边界。
- [ ] 物理 NIC/跨主机网络与更长期负载验收未执行；当前 memif 与 kernel loopback 都是本地结果，没有生产性能门槛或生产收益承诺。
