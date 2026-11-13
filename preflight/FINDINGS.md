# VPP 26.10 能力与零拷贝研究

状态：用户已批准池化 direct ByteBuf RX，以及第一版排除零长度 UDP。其他未验证项目和故障仍须解决。不把探针当作生产 transport，不以 benchmark 代替内存安全。

所有 VPP 源码证据来自本地独立 checkout `/tmp/vcl-vpp-26.10`，提交 `4acb320269bc4f3533e35c80afedac38853b2f60`。下面的行号均相对此提交。原始记录见 [results](results/)。

## 已批准的两项方案选择

1. **RX ByteBuf 所有权（已批准）**：正式接收路径采用池化 direct ByteBuf，在 EventLoop 内复制 FIFO 内容并及时归还 FIFO。默认借用 FIFO 地址无法满足关闭、跨线程 retain 和导出 NIO 视图的安全要求。TX gather 仍是复制，只可作为减少 FFM 调用的优化候选。
2. **UDP 零长度报文（已批准）**：固定不修改 VPP 26.10，第一版排除零长度 UDP。Java 发送显式失败，文档明确 native 接收缺口。

这两项选择之外，下面列出的未验证或失败项目仍需继续工程验证，并不因同意复制或 UDP 边界而自动获准降级。

## TCP RX segment 生命周期

| 场景 | 源码/实验依据 | 结论 |
| --- | --- | --- |
| 获取数据 | `src/vcl/vppcom.c:2428` 的 `read_segments` 调用 `svm_fifo_segments`，从 `rx_bytes_pending` 偏移借出地址并增加 pending 字节数 | 返回的是 FIFO 地址，没有独立 buffer 所有权或 pin token |
| FIFO 回收 | `vppcom.c:2495` 的 `free_segments(handle,n)` 直接丢弃 FIFO 头部 n 字节 | 只能按 FIFO 前缀回收；任意顺序 release 需要 Java 端账本，后面的 buffer 释放不能越过前面仍被持有的范围 |
| 跨线程 retain/release | `free_segments` 取得当前 worker，按当前 worker 的 handle 查找 session | release 必须排回所属平台线程；仅调整 Java 引用计数不能使跨线程 native free 安全 |
| slice/duplicate | 这些视图必须共享根 buffer 的生命周期 | 可以跟踪引用，但不能延长 VPP 映射的生命 |
| 导出 NIO buffer | Netty 本地 `buffer/.../ByteBuf.java:2290` 附近描述共享内容的 NIO 视图 | 已导出的 ByteBuffer 保存原地址，无法在关闭时透明替换为复制后的地址 |
| 连接清理 | `src/vcl/vcl_private.h:427` 的 session free 调用 `vcl_session_detach_fifos`；`vcl_private.c:658` 释放 client FIFO 对象 | 公共 API 没有“所有借出的 Java buffer 已归还”的回调或保活机制，不能证明 close 后安全 |
| worker/application 退出 | `vcl_private.c:120` 清理 session pool；`vppcom.c:1686` 销毁 app，最后解除 heap 映射 | 已借出的对象不能安全跨越 native 生命周期；简单延迟 Channel close 也不能覆盖 VPP 主动断连 |
| VPP 断连 | `vcl_private.c:191` 标记 session detached，随后 detach segments；`:532` → `svm/fifo_segment.c:413` → `svm/ssvm.c:343` 解除映射 | 实验保留 9 字节 segment 及 NIO 视图，VPP 退出后 `detached_query=1` 且 `rx_address_mapped=false`。没有对失效地址解引用 |
| RX 释放通知 | `free_segments` 没有普通 `read_internal` 在 `vppcom.c:2402` 附近的 dequeue notification 逻辑 | 长时间流控/饥饿也必须独立验证，短微基准通过不足以证明正确 |

因此，乱序 release 账本、把 release 调度回 EventLoop、共享 Arena 都只能解决部分问题，不能解决 native 主动 unmap。关闭时补一次 copy 也无法重定向已导出的 NIO buffer，或保护同时在其他线程访问的 buffer。当前没有发现满足计划要求的公开 segment pinning API。

## TX 实际复制路径

`vppcom_session_write_segments`（`vppcom.c:2613`）先计算总长度，非阻塞情况下空间不足返回 EAGAIN，再调用 `svm_fifo_enqueue_segments(..., allow_partial=0)`。

`src/svm/svm_fifo.c:958` 的该函数逐段调用 `svm_fifo_copy_to_chunk`。因此 gather 并未消除应用内存到 FIFO 的复制，也没有向应用暴露可提交的 TX FIFO reservation。Java 可以减少调用次数，不能称为 TX 零拷贝。

已实现独立 RX/TX 微基准。RX 比较 native copy 与读取借用地址后立即 release；TX 比较同样数据量的一次 write、16 次 write、16 段 gather。TX 成功写入时不额外轮询，避免把多出的 epoll 调用混入 16 次写的对照。

## UDP：边界、地址、空报文和 segment

两种模式、两种地址族均通过 packet-generator 注入测试：32 字节报文以 8 字节接收空间截断，来源 IP/端口正确；随后注入空报文和 1 字节标记，`recvfrom` 只交付了标记，没有交付空报文。

源代码中的三个独立限制：

- `vppcom.c:2522` 的 `write_inline` 对 n=0 直接返回成功，尚未写 FIFO；`sendto` 最后调用它。
- `application_interface.h:755` 的 `app_recv_dgram_raw` 对仅有头部的 FIFO 返回 0；有后续数据时可能消费空 datagram。`vppcom.c:2354` 把返回 0 当作未完成报文；`recvfrom` 在 `:4753` 处仅为 rv>0 填来源地址。
- **即使用 segment API 手工拼头部，仍无法补齐发送语义**：`src/vnet/session/session_node.c:1320` 附近先排除只有头部的数据，随后显式对 `data_length==0` 丢弃头部并不发送。

UDP segment 也不是 TCP 的同一种载荷视图：`read_segments` 没有 datagram 分支，返回包含内部 `session_dgram_hdr_t` 的 FIFO 字节；该 47 字节 packed 结构定义在 `src/vnet/session/session_types.h:496`，包含 data_length、data_offset、远端/本地地址、端口、IP 族及 GSO 信息。一次读取可跨报文或停在报文内部。解析该内部协议可能适配部分 RX 语义，但需要额外 ABI、长度和分片测试，并且不能解决上述 TX 限制或 retained buffer 的映射生命问题。本次没有把它加入正式数据路径。

## Worker、等待与故障

独立 `workers` 探针中，两条 Java 平台线程直接使用 `vppcom_worker_register`，各监听相同地址，不用 VLS、不设 boss 线程；各自 accept/echo 并检查 handle 的 worker 编号。80 条连接的分布为 `[32,48]`；第 64 条后关闭第一个 listener，剩余 listener 继续接收 16 条连接。此实验不是 Java 多 listener 原子启动 API，也不覆盖部分启动失败回收。

中断模式下，libc epoll 可以同时注册 VCL MQ epoll fd 和 Java eventfd；预先通知/与进入等待竞态的通知均完成。尚未加入 Netty 定时器/任务队列，不能声称已经证明 EventLoop 无丢失唤醒或高负载公平性。

poll 模式关闭 MQ eventfd，`vppcom_worker_mqs_epfd=-1`，`vppcom_worker_is_detached=-95 (ENOTSUP)`。VPP 退出后持续非阻塞 poll 8 秒，公开查询仍返回 ENOTSUP，RX 映射仍存在。依据：`vppcom.c:5225` 和 `:3725` 附近的 condvar 分支。**不接受把这个状态当作健康连接**；需要设计并验证独立的 VPP 断连监测和失败清理，再实现生产 poll runtime。这个替代检测尚未完成，也不宣称仅凭查询不足便绝对无法实现。

额外生命周期反例保存在 `results/lifecycle-failure/`：先在 worker 0 建立/关闭 IPv4 和 IPv6 TCP，再启动两个 listener worker，`vppcom_session_accept → vcl_worker_flush_mq_events → vcl_mq_dequeue_batch` 请求了约 4 GB 的 vector 扩容，进入 `os_out_of_memory`。gdb 捕获了 native 栈；此运行没有 `probe_complete`，按失败记录。源码未修改；根因尚未确定，不把它直接归因于 VPP 或 FFM，也不通过增加 heap 掩盖。

## 配置能力清单（待确认，不是已接受的兼容边界）

| 能力/选项 | 当前证据 | 状态/后续行为要求 |
| --- | --- | --- |
| IPv4/IPv6 TCP | 本地 cut-through 两模式通过短读写实验 | 已观测基本能力；网络 TCP/FIN/RST/半关闭仍待验证 |
| UDP 单播来源与截断 | 两模式、两地址族注入实验 | 已观测非空 RX；connected/unconnected 完整组合、网络 TX 待验证 |
| 零长度 UDP | 上述 public API、VPP TX 路径与 RX 实验 | 固定当前约束无法满足完整收发要求；需用户决策 |
| 多 worker 相同地址监听 | 独立 worker 实验通过 | 可等价适配服务端分发；不是内核 SO_REUSEPORT 的全部语义 |
| SO_REUSEADDR | `vppcom.c:4248`，仅保存 VCL session flag | 未证明内核等价语义，不能成功返回后静默忽略 |
| SO_REUSEPORT | `vppcom.c:4281`，仅保存 flag；worker 分发本身不依赖设置它 | 应把多 worker listener API 与 socket option 承诺分开 |
| SO_BROADCAST | `vppcom.c:4314`，仅保存 flag | 计划范围为 UDP 单播；配置请求应明确拒绝，待确认 |
| IPV6_V6ONLY | `vppcom.c:4369`，仅保存 flag | 未证明 dual-stack/v6-only 行为；不自动声称支持 |
| SO_KEEPALIVE | `vppcom.c:4401`，仅保存 flag | 不证明传输层 keepalive 生效；待确认显式拒绝 |
| TCP_NODELAY | `vppcom.c:4433`，仅保存 flag | VPP 可能有不同的默认发送策略，尚未证明可等价适配 |
| TCP_KEEPIDLE | `vppcom.c:4465`，把整数折成布尔 flag | 不具备要求的秒数设置语义；待确认显式拒绝 |
| TCP_KEEPINTVL | `vppcom.c:4497`，把整数折成布尔 flag | 不具备要求的秒数设置语义；待确认显式拒绝 |
| SO_SNDBUF/SO_RCVBUF | `vppcom.c:4175–4245` 存取 session 数值；未发现对活动 FIFO 的 resize | 不能把回读成功作为实际容量生效；运行时 FIFO 参数另行验证 |
| SO_LINGER / IP_TOS / 其他扩展选项 | 目前未做完整等价验证 | 未验证，不能自动接受；逐项补证据再确认 |
| autoRead、手动读、allocator、watermark、writeSpinCount、connect timeout/cancel | 应由 Java Channel/IoHandler 实现 | 本阶段尚未实现，不能标为通过 |

## 性能证据的边界

`results/summary.json` 为每个配置 3 次“总耗时/10,000 次操作”的 min/median/max；每个配置预热 2,000 次。它不是消息 p50/p99/p99.9。消息大小为 64 B、1 KiB、64 KiB。版本、CPU、NUMA 和两个进程的 CPU 绑定见 `results/environment.json`，FIFO 为 1 MiB，VCL heap 256 MiB，segment 64 MiB。

这是同 application、本机 cut-through 的 FFM 微基准。没有 Netty allocator/ByteBuf、没有 epoll transport 对照、没有物理网络；JVM compilation 和顺序运行仍影响结果。没有测量进程级 CPU 成本、分配/GC、到达率负载、尾延迟、丢包或拥塞背压。不能据此声称生产吞吐收益，也不设性能门槛。零拷贝是否纳入正式数据路径先取决于内存安全，当前答案是否定的；池化 direct ByteBuf 是用户已批准的替代方案。
