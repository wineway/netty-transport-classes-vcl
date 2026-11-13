# 实施进度与待办

包名统一为 `me.wineway.vcl`。从零开发，不读取或恢复已删除的旧实现。

## 原生修复验证：按用户要求暂停

- [ ] **安全策略允许后**，验证 `preflight/patches/vpp-26.10-segment-lifetime.patch`。用户已允许独立副本实验，随后明确要求暂缓验证、记录 TODO 并继续 Java 工作。
- 独立源码：`/tmp/vcl26-fix-source`；ASAN 构建：`/tmp/vcl26-fix-asan`。候选补丁已编译，尚未通过运行验证，不可视为修复完成。
- 原始正式版 checkout `/tmp/vcl-vpp-26.10` 保持不变；不能将候选补丁默认纳入正式依赖。
- [ ] 为实验 runner 增加修改版源码、完整 patch 和构建参数的溯源记录。
- [ ] ASAN 与 release 重跑 `workers-after-close`，覆盖先注册 worker、连续/并发创建关闭、worker 索引复用、VPP 退出、app destroy 与资源泄漏。
- [ ] 审核跨 worker 共享映射和同 worker 控制事件顺序，证明迟到删除只释放旧映射。
- [ ] 取得验证结果后，再确认是否采用修改版 VPP。缺少验证时不能宣称生产内存安全。

## Java 实现与交付

- [ ] FFM、runtime、IoHandler 及独立内核等待测试。
- [ ] TCP/UDP Channel、配置、多 listener 原子启动与失败回滚。
- [ ] epoll 与两种 VCL 模式的语义差分、并发故障与资源测试。
- [ ] 逐项列出不支持或无实际效果的 socket 选项，提供源码证据并确认兼容边界。
- [ ] 可复现基准、示例与文档。真实 VPP 测试及性能结果受上述暂停项影响，不以模拟测试代替。

已确认：使用池化 direct ByteBuf 复制接收；首版排除零长度 UDP（显式拒绝发送，记录接收限制）。
