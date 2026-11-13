# Cut-through 半关闭后的反向通知缺失

真实 Netty 契约测试在 epoll 全部通过；VCL interrupt/poll 的半关闭反向发送用例失败。已采用用户批准的 segment 生命周期补丁，故这是另一个路径的问题。

复现：client 写 3 字节后 `shutdownOutput()`；server 收到 EOF 并保持输出打开，再写回 1 字节。server write Promise 成功，client 没有收到 read 事件。10 秒后在 client 所属 EventLoop 直接调用公开 `vppcom_session_read`，返回 **1 字节，值为 9**，同时 `readPending=true`、`autoRead=true`。数据已经进入共享 FIFO，但就绪通知没有交付。

记录：`/tmp/vcl-transport-halfclose-diag/interrupt/maven.log.txt`；原始断言保留为失败，没有用直接 read 使测试伪通过。

固定 VPP 26.10 本地源码：

- `application_local.c:713`：`ct_session_half_close` 对 peer 调用 `session_transport_closing_notify`。
- `application_local.c:1008`：`ct_session_can_tx` 只允许 READY、CLOSING、APP_CLOSED，遗漏收到 FIN 后仍可写的 TRANSPORT_CLOSING。
- `application_local.c:1023`：因此 `ct_session_tx` 提前返回，不发 `session_enqueue_notify`。
- 同文件 `ct_app_rx_evt` 和 `ct_session_tx` 对 peer 的 TRANSPORT_CLOSING 状态也阻断通知；半关闭时仍需反向数据及 FIFO 排空通知。

候选最小修改见 [ct-half-close-proposed.patch](patches/vpp-26.10-ct-half-close-proposed.patch)。这是 **VPP server 的 cut-through transport** 修改，与已经批准的 libvppcom segment 补丁不同。候选补丁尚未应用、编译或验收；拟在独立 checkout 试验，保留原正式版源码。需覆盖单侧 FIN、双方 FIN、FIN 前排队数据、反向写满/排空与正常 close/reset。

用户已明确要求保留 TODO、先完成其他工作，因此不应用或验证本候选补丁。已批准的 segment 生命周期补丁不受此暂缓影响。

另一个独立观察：VPP 的 bind(port=0) 保留零端口，CT client 的本地地址/端口也由 VPP 填为零；UDP 在同一 VPP 的 loopback 上自发自收被 spoofed-local/source-lookup 检查丢弃。后续测试需区分端口分配适配、CT 地址语义和有效的网络拓扑，不能把这些失败标为 UDP transport 已通过。
