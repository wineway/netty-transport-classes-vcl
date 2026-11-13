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

先处理 VCL 用户态 ready list 再阻塞，避免共享 FIFO 已就绪但没有新内核通知时丢进度。普通 I/O 等待服从 Netty 定时任务，并最多阻塞 10ms。worker native 内存使用 confined Arena；唤醒字是进程生命周期内共享的只读 8 字节常量，不随 poller 销毁而释放。

跨线程唤醒对齐 [Netty 4.2.7 EpollIoHandler](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/EpollIoHandler.java)：原子 awake 状态合并通知，只有将等待状态改为 awake 的线程才写 eventfd，不在 `wakeup()` 上持有 monitor。I/O 线程准备等待后重新检查 Netty 任务队列，避免它处于 awake 期间被合并的唤醒导致漏处理任务。

退出等待时，若有跨线程请求过唤醒，用 `pendingWakeup` 记录尚未观察到的 eventfd 写入；观察并 drain 该通知后清除。下一次等待或关闭前，最多等待 1 秒观察该通知，超时后继续，与 epoll 的有界等待策略一致。不记录在途调用数量，也不等待每个 FFM 调用返回。进程级唤醒常量消除了单个 poller 关闭其写入缓冲区的竞态。

使用 level-triggered 事件和递增 registration token；关闭后过期 token 不会路由到复用 handle 的新 Channel。零兴趣集移除 VCL epoll 注册，恢复兴趣时重新 ADD；mask不变跳过提交，同loop重新注册时强制恢复注册。submit返回mask，内部token只用于路由。

读/accept 由接收分配器控制批量，默认metadata预算为16，允许自定义；通过direct allocator包装实际调用handle.allocate，配置要求ExtendedHandle。RDHUP时先排空剩余数据，再发输入关闭和输入关闭完成事件。写预算只统计实际native写入，空ByteBuf不消耗预算；预算用尽但尚未阻塞时排队复用flushTask，仅在EAGAIN/无进展时保留OUT。连接、读写异常及shutdown完成顺序尽量沿用Netty基类和epoll实现，详见[逐项对照](EPOLL-PARITY.md)。

## 生命周期和已知限制

关闭 Channel 时先取消 I/O 注册，再关闭 session；关闭 IoHandler 时先关闭其 Channel，释放 poller，最后注销 worker。Runtime 在 worker 数量非零时拒绝销毁。

检测 VPP detach 后，poller 进入失败状态，关闭 Channel，使 connect/write Promise 失败并继续处理 Java 清理任务；不在原连接上自动重连。native 库保留进程生命周期，application 和 worker 可以显式销毁。

真实 TCP 收到 FIN 后，VCL 处于 VPP_CLOSING；这时 shutdown 可能只设置标志。当 Java 两个方向都关闭时，延后执行 session close，以发送最终 disconnect reply/FIN，并避开 Netty output buffer 收尾的重入。

VPP 本地 cut-through 刻意避免连接到同一 application 的 listener，且 25.10 没有等价 TCP half-close 行为。本地测试使用两个独立 JVM/application；半关闭正确性通过普通 Linux TCP socket 和实际 VPP 接口单独验证。

尚未实现：热升级/断线自动恢复、动态 worker 扩缩容、跨 worker session 迁移、其他 ABI、零拷贝、批量 FFM 读写优化，以及经过测量的 busy-poll/NUMA 调优。
