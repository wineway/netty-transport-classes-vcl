# Java 26 / VPP 26.10 原生能力验证

这是批准计划第 1 阶段的新实现。它包含 Java FFM 探针、测试专用 C ABI 探针、构建/运行脚本及原始实验记录，**还不是可用的 Netty transport**。旧实现的 269 个已删除文件均未恢复；没有修改 Netty 或原始 VPP checkout；另有用户批准采用的独立 libvppcom segment 修复，也没有引入 JNI、VLS 或生产 C 辅助库。

第 1 阶段尚未验收通过：[研究与待确认事项](FINDINGS.md)。存在标准 ByteBuf 生命周期与借用 FIFO 内存的冲突，以及 VPP 26.10 不支持零长度 UDP 发送的确定缺口。用户现已批准池化 direct ByteBuf 和首版排除零长度 UDP，后续实现采用 `me.wineway` 包名前缀。原始记录保留更名前的名称与哈希，不修改历史实验数据。

## 复现

依赖：Linux x86_64、JDK 26、Maven、GCC、CMake、Ninja、Python 3、python3-ply、OpenSSL 开发头文件。需要允许 VPP 使用 Unix socket 的 `SO_PASSCRED`、共享内存和 eventfd。无须物理网卡或修改主机网络接口。

从已有本地仓库创建独立 checkout，固定正式版提交，不联网拉取：

```sh
bash preflight/build-vpp.sh /code-repos/vpp /tmp/vcl26-source-new /tmp/vcl26-build-new
```

源码提交必须为 `4acb320269bc4f3533e35c80afedac38853b2f60`，对应 `v26.10`。构建只启用本次本地验证需要的 VPP core 和 SAPI VCL；插件关闭，BAPI 关闭。`-std=gnu11` 避免本机 GCC 16 的默认 C23 模式与该版本源码的 const 检查冲突。它是编译选项，不是源码补丁。后续真实网卡测试需要另行构建相应插件。

设置 `JAVA_HOME` 为你的 JDK 26 安装目录后：

```sh
export PATH="$JAVA_HOME/bin:$PATH"
mvn -f preflight/pom.xml package
python3 preflight/run.py \
  --source /tmp/vcl26-source-new --build /tmp/vcl26-build-new \
  --java-home "$JAVA_HOME" --output /tmp/vcl26-run-new --scenario all
python3 preflight/summarize.py /tmp/vcl26-run-new
```

`package` 只验证 Java 构建；原生实验由 `run.py` 执行，不能把 Maven 的 “No tests to run” 当成原生验证成功。输出目录必须不存在，脚本不会覆盖之前的记录。

`all` 为两种模式分别创建独立 VPP/JVM 进程，运行以下 5 组实验：

| 实验 | 检查内容 |
| --- | --- |
| smoke | IPv4/IPv6 TCP，本地 cut-through 的普通读写和立即释放的 segment 读取 |
| workers | 两个平台线程各注册 worker、监听相同地址/端口、同线程 accept/echo、关闭其中一个 listener 后再建 16 条连接 |
| udp | packet-generator 按顺序注入 32 字节、0 字节、1 字节 UDP；检查来源、截断、空报文观测结果；调用空 sendto |
| retention | 保留 segment 和导出的 NIO 视图后终止测试 VPP，检查断连查询和 `/proc/self/maps`，不访问已失效地址 |
| bench | 三种消息大小的 RX copy/borrow 和 TX 单次写/16 次写/gather 微基准 |

每次运行先做 C/FFM 的 81 项 ABI 比较和 1,000 次 libc eventfd/epoll 唤醒检查。中断模式另外检查 VCL MQ epoll 与 Java eventfd 嵌套在同一个内核 epoll 中。这里的“中断模式”表示 MQ eventfd 开启；数据微基准主动非阻塞轮询，**不是完整 Netty 中断 EventLoop 的性能测试**。

脚本的 `COMPLETE` 只表示实验按预期完成，包括成功复现已知缺口，**不表示 transport 验收通过**。每个 JVM 必须在清理后打印 `probe_complete`，否则脚本失败；gdb 停在断点不能被记为成功。

额外的、当前失败的生命周期实验：

```sh
python3 preflight/run.py \
  --source /tmp/vcl26-source-new --build /tmp/vcl26-build-new \
  --java-home "$JAVA_HOME" --output /tmp/vcl26-lifecycle-new \
  --scenario workers-after-close --debug-native
```

它在同一 application 中先建立/关闭 IPv4、IPv6 TCP，再注册两个 worker；原版在 VCL MQ 处理路径触发异常大的分配请求。`--debug-native` 需要 gdb，会在 `os_out_of_memory` 截获回溯，未完成的运行返回失败。根因及已批准的修复见 [NATIVE-LIFECYCLE.md](NATIVE-LIFECYCLE.md)，补丁版两种模式的 ASAN 回归见 `results/diagnostics/segment-fixed-asan/`。不能据此推断所有 worker 启动顺序都安全。

## 结果和范围

正式记录放在 [results](results/)，报告见 [FINDINGS.md](FINDINGS.md)。记录包含 JDK、CPU/NUMA、CPU affinity、VPP 提交、头文件/库/探针源码 SHA-256、动态库依赖、CMake 配置、VPP 启动配置、报文输入和原始输出。所有实验均为本机测试。

目标 Netty 是本地 `/code-repos/netty` 的 `4.2.20.Final`（提交 `4fb6a5f308382b4bee1bff3ef80b45ea8b35166d`）。已读取其 `IoHandler` 契约和 ByteBuf NIO 视图约定；本模块没有 Netty 依赖，尚未编译或声称完成 Channel 兼容性验证。

后续 Netty 实现已在 [transport](../transport/README.md) 提供，其真实网络、故障、基准及剩余验收范围见 [VALIDATION.md](../transport/VALIDATION.md)。本模块保留第一阶段原始实验记录；其中早期“未完成”描述应结合后续记录阅读。CT 半关闭和未 attach SAPI 连接回收按用户要求保留 TODO。
