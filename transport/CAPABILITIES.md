# 能力与兼容边界

目标为 Java 26、Linux x86_64、Netty 4.2.20.Final、VPP v26.10 `4acb320269bc4f3533e35c80afedac38853b2f60`。Java 实现已经可构建和运行；两个用户暂缓的 VPP server 问题仍阻止完整验收。逐项实测结果见 [VALIDATION.md](VALIDATION.md)。

## 已实现和验证的路径

- TCP 和 UDP 单播支持 IPv4/IPv6。双 VPP memif 的普通网络 TCP 半关闭、手动读取/autoRead、16 MiB 背压恢复、任务/定时器进展、来源地址与 UDP 截断已有真实 epoll/两种 VCL 模式共同测试。
- 直接调用公开 vppcom API，一个固定平台 EventLoop 绑定一个 worker。listen、accept、child 读写同线程；迁移到其他 EventLoop 明确失败。VclServer 为每个 loop 建立 listener，全部成功才完成，部分失败回滚；原子启动与回滚另有模拟后端验证，同端口原生分发见 preflight。
- RX 使用用户批准的池化 direct ByteBuf 复制，支持 retain、slice、跨线程释放和导出 NIO 视图。heap/composite TX 合并到拥有明确生命周期的 direct buffer；不是零拷贝。
- UDP connect 使用 native connected session；connected sendto 传空 endpoint。disconnect 用同 worker 的新 native session 重新绑定，并更换注册 token；与 Netty epoll 一样置为 inactive，重新 connect 后恢复 active。未绑定且未连接的 UDP 默认写入失败，需先 bind 或 connect。
- bind(0) 在 Java 选择并保留候选非零端口，native bind 仍检查冲突。VPP 原生绑定 0 不分配端口，不能直接透传。CT 的 connected 本地地址/端口可能仍为 0，网络路径已验证实际地址；未伪造 CT 地址。
- allocator、接收 allocator、autoRead、autoClose、连接超时、writeSpinCount、写水位由 Java 实现；TCP ALLOW_HALF_CLOSURE 已实现。连接失败使用公开 session_get_error 补充错误信息，ATTR_GET_ERROR 是原生占位实现。
- VPP 退出通过独立 SAPI socket 监测，使已存在 Channel、待写入数据及后续注册失败；监测连接触发的 VPP server 回收缺陷见下文。

## 已批准的边界

用户批准包名 `me.wineway.vcl`、池化 direct RX、首版排除零长度 UDP，以及采用独立 libvppcom segment 生命周期补丁。零长度 UDP 发送显式失败；native 接收空报文不可被完整观测。UDP 多播/广播不属于已批准的 UDP 单播范围。

下面的证据来自 pristine `src/vcl/vppcom.c` 和 `src/vcl/vcl_private.h`。设置只缓存字段、未作用于 transport 的选项不能报告成功；用户已确认首版明确拒绝这些设置。

| 选项 | vppcom.c 源码证据 | 行为 |
| --- | --- | --- |
| SO_SNDBUF | 4204–4216，仅设置 sndbuf_size，VPP-TBD | 拒绝按 Channel 调整；runtime FIFO 配置不是同一语义 |
| SO_RCVBUF | 4235–4247，仅设置 rcvbuf_size，VPP-TBD | 拒绝按 Channel 调整 |
| SO_REUSEADDR | 4263–4278，仅保存 VCL attr 位 | 拒绝 |
| SO_REUSEPORT | 4296–4311，仅保存 VCL attr 位 | 拒绝；多 worker 共享 listener 不是 POSIX 选项等价实现 |
| SO_KEEPALIVE | 4416–4430，仅保存 attr 位 | 拒绝 |
| TCP_NODELAY | 4448–4463，仅保存 attr 位 | 拒绝 |
| TCP_KEEPIDLE / TCP_KEEPINTVL | 4479–4525，仅保存秒数，VPP-TBD | 拒绝 |
| SO_BACKLOG | 1949 的 q_len 参数未使用 | 拒绝配置；accept 队列由 VPP 决定 |

## 未通过与暂缓项

- [CT 半关闭反向通知](../preflight/NATIVE-HALF-CLOSE.md)：数据已在 FIFO，但 VPP server 状态检查阻断通知。用户要求只保留 TODO，候选未应用。不能把网络 TCP 半关闭通过扩大为 CT 通过。
- [未 attach SAPI 连接回收](../preflight/NATIVE-SAPI-CLOSE.md)：关闭监测连接后 VPP 遗留 socket/fd，16 次最小复现使 CLI 超时。用户要求只保留 TODO，候选未应用。反复启停真实资源测试保持失败，空载 JVM fd/mapping 短测不能证明服务端无泄漏。
- SO_LINGER 无对应公开 attr；IP_TOS 仅有 SET_DSCP 路径，完整 ECN/TOS 及运行中修改语义尚未核实。当前请求明确拒绝，尚未确认纳入最终兼容边界；其余未知扩展选项也不静默忽略。
- 各后端 60 秒、8 连接高负载及每个 EventLoop 定时器进展已通过；更长期负载、完整 worker 索引复用/注册失败注入、CT 地址、所有 RST/关闭组合及物理 NIC 网络仍未完成完整验收。已测范围以原始记录为准，不由短基准推断。

## 待确认选项的源码证据

- SO_LINGER：`src/vcl/vppcom.h:136–176` 的公开 attr 枚举没有 linger，公开 close/shutdown 也没有可表达 linger=0 强制 RST 或 linger>0 关闭等待期限的参数。不能把 Java 写队列清空当作对端已确认收妥数据。
- IP_TOS/IPv6 traffic class：`vppcom.c:4192–4202` 的 SET_DSCP 只写 `session->dscp`；`:61`、`:88` 仅在 listen/connect 控制消息带给 VPP，未更新活动连接的 transport。公开 API 没有完整 ECN/TOS 设置。将高 6 位或缓存回读当作完整设置成功会掩盖差异。

当前实现对这两项明确抛 UnsupportedOperationException；这是尚未通过的能力项，是否将明确拒绝纳入首版边界需要用户确认。
