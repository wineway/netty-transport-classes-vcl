# 实现说明

## 数据路径与线程

Pipeline → ByteBuf → Java FFM downcall → VCL FIFO → VPP session/TCP → 接口。发送和接收都包含 ByteBuf 与 FIFO 间的复制；FFM 不等于零拷贝。

`VclRuntime` 的独立管理线程创建 VCL application 并保留默认 worker。`VclIoHandler.initialize()` 在实际 I/O 平台线程上注册额外 worker，销毁时在同一线程注销。拒绝虚拟线程，因为 VCL 的 native TLS 无法跟随虚拟线程在 carrier 间移动。

`VclServerBootstrap` 为固定 group 中的每个 EventLoop 创建一个 listener，给各 `ServerBootstrap` 同时指定同一个 parent/child loop。VPP 在 application workers 间选择新连接；Java 不移动已 accept 的 session。客户端在注册完成后才创建 native session。Channel 记录首次所属 EventLoop，拒绝向其他 loop 注册。

## FFM 与 ABI

`NativeVcl` 直接查找 `libvppcom` 和 libc 符号。热路径 read/write/epoll_wait 使用精确 MethodHandle 调用；控制操作使用通用调用。所有 read/write 指针只在同步 downcall 内有效，VCL 不保留这些 Java 缓冲区地址。

`vppcom_endpt_t` 按 25.10 的 48 字节布局描述；IP/port 使用网络字节序。Linux x86_64 `epoll_event` 为 12 字节，data 在偏移 4，使用非对齐 long layout。`pollfd` 为 8 字节。`src/test/native/abi_probe.c` 通过真实头文件验证这些布局及常量，和 Java 布局测试配套使用。

## 事件循环

每个 worker 有一个 VCL 虚拟 epoll，以及一个自有 eventfd。libc `poll()` 等待 worker MQ epoll fd 和 eventfd。poll 前后用零超时的 `vppcom_epoll_wait()` 转换 VCL 事件，不把虚拟 session handle 作为 Linux fd 使用。

先处理 VCL 用户态 ready list 再阻塞，避免共享 FIFO 已就绪但没有新内核通知时丢进度。等待服从 Netty 定时任务，并最多阻塞 10ms。跨线程唤醒与关闭同步保护 eventfd 生命周期；worker native 内存使用 confined Arena，唤醒字使用 shared Arena。

使用 level-triggered 事件和递增 registration token；关闭后过期 token 不会路由到复用 handle 的新 Channel。零兴趣集移除 VCL epoll 注册，恢复兴趣时重新 ADD。读/accept 每轮最多处理 16 条，写使用 Netty write spin 预算，预算用尽但尚未阻塞时排队继续发送。仅当 write 返回 EAGAIN/无进展时保留 OUT 兴趣。

## 生命周期和已知限制

关闭 Channel 时先取消 I/O 注册，再关闭 session；关闭 IoHandler 时先关闭其 Channel，释放 poller，最后注销 worker。Runtime 在 worker 数量非零时拒绝销毁。

检测 VPP detach 后，poller 进入失败状态，关闭 Channel，使 connect/write Promise 失败并继续处理 Java 清理任务；不在原连接上自动重连。native 库保留进程生命周期，application 和 worker 可以显式销毁。

真实 TCP 收到 FIN 后，VCL 处于 VPP_CLOSING；这时 shutdown 可能只设置标志。当 Java 两个方向都关闭时，延后执行 session close，以发送最终 disconnect reply/FIN，并避开 Netty output buffer 收尾的重入。

VPP 本地 cut-through 刻意避免连接到同一 application 的 listener，且 25.10 没有等价 TCP half-close 行为。本地测试使用两个独立 JVM/application；半关闭正确性通过普通 Linux TCP socket 和实际 VPP 接口单独验证。

尚未实现：热升级/断线自动恢复、动态 worker 扩缩容、跨 worker session 迁移、其他 ABI、零拷贝、批量 FFM 读写优化，以及经过测量的 busy-poll/NUMA 调优。
