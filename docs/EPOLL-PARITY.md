# 与 Netty epoll transport 的语义对照

日期：2026-11-14。基准固定为项目实际依赖的 **Netty 4.2.7.Final**，源码来自 Maven Central 同版本 sources JAR；未使用 master 或其他版本推断行为。本轮比较现有 IPv4 TCP transport 支持的 Channel、IoHandler 和配置契约。

## 对照与实现

| 范围 | epoll 参考 | 当前 VCL 实现 |
|---|---|---|
| 唤醒与关闭 | `EpollIoHandler.wakeup/run/closeFileDescriptors` | 原子 awake 合并唤醒、等待前复查任务队列、pendingWakeup 最多等待1秒；无写入引用计数，无 monitor |
| 注册项索引 | `EpollIoHandler` 的 primitive map | 使用 LongObjectHashMap；VCL event data 保存递增 token，避免 session handle 复用误路由 |
| interest 更新 | `AbstractEpollChannel.setFlag/clearFlag` | mask 未变化不提交；成功提交后更新缓存；同 loop 重新注册时重置缓存，重新 ADD |
| submit 返回值 | `EpollIoHandler.DefaultEpollIoRegistration.submit` | 返回提交的 ops mask，失效返回-1；不再返回内部路由 token |
| 就绪处理顺序 | `AbstractEpollUnsafe.handle` | 先完成连接/flush，再处理读取，最后处理 RDHUP |
| 读分配器 | `EpollRecvByteAllocatorHandle`、streaming handle、`PreferredDirectByteBufAllocator` | 适配同样的分配器包装和 direct buffer 策略，保留上游 Apache 许可头；实际调用配置的 handle.allocate |
| 读/accept 批量 | `AbstractEpollStreamChannel.epollInReady`、`AbstractEpollServerChannel.epollInReady` | 使用 reset、attempted/last bytes、消息计数、continueReading、readComplete；移除固定16次循环 |
| RDHUP | streaming allocator handle | 记录 RDHUP，先排空数据再处理EOF；可越过普通 AUTO_READ/批量停止条件；避免持续订阅已观察到的RDHUP |
| 读异常 | `handleReadException` | 先交付已有数据、readComplete、exceptionCaught；IOException/OOM 走输入关闭，普通异常不无条件关闭整个 Channel |
| 写预算 | `AbstractEpollStreamChannel.doWrite/doWriteSingle` | 空 ByteBuf 消耗0次，实际写成功消耗1次，EAGAIN/无进展表示发送缓冲满 |
| 写推进 | 同上与 `AbstractEpollUnsafe.flush0/epollOutReady` | 预算耗尽时清 OUT、排队复用 flushTask；只在写阻塞时订阅 OUT；可写/错误就绪时强制 flush |
| 连接校验与失败 | `AbstractEpollUnsafe.connect/doConnect` | 无效地址、重复连接及重复 pending connect 通过 Promise 失败，不无条件关闭原连接；真实 native connect 失败关闭对应 session |
| 连接完成 | `fulfillConnectPromise/finishConnect` | 捕获激活状态后完成 Promise，必要时发 channelActive；处理取消、超时清理和地址缓存，保留请求主机名 |
| 连接错误 | native connect errno 与异常注解 | 常见 ECONNREFUSED/ETIMEDOUT/网络不可达映射到 ConnectException、ConnectTimeoutException、NoRouteToHostException，并附远端地址；其他 VCL 错误仍保留 errno |
| 输入半关闭 | `AbstractEpollUnsafe.shutdownInput` | EOF 排空后依次发 ChannelInputShutdownEvent、ChannelInputShutdownReadComplete，各一次；显式 shutdownInput 不伪造远端EOF事件 |
| 输出/双向关闭 | `AbstractEpollStreamChannel.shutdown*` | 复用 AbstractUnsafe.shutdownOutput，重复输出关闭按其 ClosedChannelException 行为处理；shutdown 始终尝试两个方向，优先返回输出错误；沿用 EventLoop 分派及完成链 |
| 关闭 Promise 顺序 | `AbstractChannel.AbstractUnsafe.close/doClose0` | 使用 Netty 基类顺序：closeFuture 可以先于 outbound Promise 清理完成；故障测试分别等待两个 Future |
| AUTO_READ 清理 | `AbstractEpollChannel.clearEpollIn` | 跨线程清理任务执行时复查 readPending 与 AUTO_READ，避免清除后来发出的 read 请求 |
| 配置 | `EpollChannelConfig/EpollServerChannelConfig` | recv allocator 要求 ExtendedHandle；server 使用 ServerChannelRecvByteBufAllocator，backlog 默认 NetUtil.SOMAXCONN |
| Bootstrap 选项 | `AbstractBootstrap/ServerBootstrap` | option/childOption 的 null 值移除配置，null option 提前拒绝 |

## VCL 后端保留的差异

- VCL session handle 不是 Linux fd，继续使用 VCL 虚拟 epoll；Linux poll 只等待 MQ epoll fd 与自有 eventfd。VCL worker 的 native TLS 仍要求固定平台线程，接收连接不能跨 worker 迁移；多 worker 服务端仍每个 loop 单独监听。
- VCL 25.10 的真实 TCP 在 CLOSE_WAIT 中 shutdown 可能只标记状态；两个方向关闭后仍须延后 session close，完成 disconnect reply/FIN。该步骤由真实 TCP 回归验证，不能仅复制 Linux socket 的实现后删除。
- VPP 本地 cut-through 不提供真实 TCP half-close；半关闭对齐由 Linux TCP ↔ netkit ↔ AF_PACKET ↔ VPP TCP 的路径验证。
- 本轮没有扩展 IPv6、UDP、FileRegion/sendfile、VCL 原生 socket option、SO_LINGER、TCP fast open、splice、gather write 或 ET 模式。默认保持 LT，ByteBuf/FIFO 之间仍有复制；FFM 数据面不引入 JNI/C shim。
- 唤醒字使用一个进程生命周期的只读8字节 native 常量，替代 JNI 内部的调用栈参数；关闭不释放该常量，也不额外保证所有 native 写入调用均已返回。

## 验证

Java 26.0.2.1、VPP/VCL 25.10，当前 Ubuntu 26.04 环境；隔离 user/network namespace 中使用 netkit L2 替代内核未提供的 veth。执行完整 Maven verify，**7项单元测试 + 20项真实VPP集成测试，失败0、错误0、跳过0**。

- `RecvAllocatorTest`：配置的 allocate 回调被使用、direct buffer 偏好、超过16的读预算、RDHUP 排空语义、ExtendedHandle 校验与 server allocator 类型。
- `TransportSemanticsIT`：无效/重复 connect 后可继续收发、pending connect 冲突与取消、连接拒绝的异常类型和地址、请求主机名保留、空写不消耗写预算、输出关闭失败后仍关闭输入、AUTO_READ=false 时 FIN 排空和事件顺序、同 loop 注销后重新注册、submit 返回 mask。
- 原有 TCP 互通、半关闭、连接生命周期、大消息背压、跨线程任务、唤醒/关闭竞争和 VPP 退出用例全部通过。

完整报告在 `target/surefire-reports/`、`target/failsafe-reports/`，测试拓扑日志在 `target/wire/`。本轮是源码对照与VCL回归，不是运行Netty全部上游测试套件；性能报告仍对应其各自源码hash，本轮不宣称吞吐提升。

## 上游源码

- [EpollIoHandler](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/EpollIoHandler.java)
- [AbstractEpollChannel](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/AbstractEpollChannel.java)
- [AbstractEpollStreamChannel](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/AbstractEpollStreamChannel.java)
- [AbstractEpollServerChannel](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/AbstractEpollServerChannel.java)
- [EpollRecvByteAllocatorHandle](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-classes-epoll/src/main/java/io/netty/channel/epoll/EpollRecvByteAllocatorHandle.java)
- [PreferredDirectByteBufAllocator](https://github.com/netty/netty/blob/netty-4.2.7.Final/transport-native-unix-common/src/main/java/io/netty/channel/unix/PreferredDirectByteBufAllocator.java)
