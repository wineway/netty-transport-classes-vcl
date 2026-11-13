# VPP 26.10 未 attach SAPI 连接未回收

这是独立于 segment 生命周期和 cut-through 半关闭的第三个问题。用户已明确要求“保留 TODO，先完成其他工作”；不应用或验证候选补丁。

原始 VPP v26.10 `4acb320269bc4f3533e35c80afedac38853b2f60`，无需 Java、libvppcom、TCP 或 segment 补丁。Python 向 SAPI AF_UNIX/SOCK_SEQPACKET socket 连接后直接关闭。每次检查 `/proc/<vpp>/fd` 和 `vppctl show version`。

[原始记录](results/diagnostics/sapi-unattached/probe.jsonl)：初始 13 个 fd；每次连接关闭后增加 1 个。关闭 16 个连接后为 29 个 fd，CLI 从响应转为超时。复现脚本、启动配置和日志在同一目录。当前 ptrace 不可用；SIGABRT 也未给出可用栈，不将推测写成栈证据。

本地源码：`src/vnet/session/session_api.c` 的 `sapi_sock_accept_ready` 将未 attach socket 的 app worker 设为 `APP_INVALID_INDEX`；断开后 `sapi_socket_detach` 在找不到 app worker 时直接返回，未删除 socket 和 clib_file。关闭的连接继续占用服务端资源和事件处理。已有 `sapi_socket_close_w_handle` 会删除 clib_file、关闭 fd、释放 socket pool entry。

[候选补丁](patches/vpp-26.10-sapi-unattached-close-proposed.patch) 只在没有有效 app worker 的断开路径调用现有清理函数。它是源码候选，尚未构建或验证，也未修改任何 VPP checkout。

Java `LinuxWaiter` 为两种模式建立独立 SAPI 连接监测 VPP 退出，因此会触发该问题。仅共享监测连接或将关闭推迟到 JVM 退出仍会遗留服务端 fd，不能称为修复。真实资源测试 `NativeResourcesIT` 保留失败；空载短测只证明 JVM fd/mapping 回收，不能证明服务端回收。

后续验证：独立副本应用候选，重复探针至少 128 次并检查 VPP fd 恒定；两种模式各重复 runtime/worker/会话关闭；重跑 VPP 退出和网络契约。cut-through 半关闭候选仍按用户指示保留 TODO，不一并应用。
