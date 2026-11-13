# Netty VCL transport

Java 26 FFM 直接调用 VPP VCL 的 TCP transport。运行时无 JNI、无项目自编译 C shim；仍需 VPP 自带的 `libvppcom` 及其依赖库。

当前基线固定为 **Java 26、Netty 4.2.7.Final、VPP/VCL 25.10、Linux x86_64 小端**。这是可运行的工程 MVP，尚未做一小时稳定性压测。已增加独立 benchmark profile，用于当前资源预算下与 Netty epoll 的端到端性能对比。

## 能力与边界

- `VclSocketChannel` / `VclServerSocketChannel` 实现 Netty TCP Channel 接口，复用 Pipeline、Promise、ByteBuf 和写缓冲水位。
- `VclIoHandler.newFactory(runtime)` 接入 Netty 4.2 `MultiThreadIoEventLoopGroup`。
- 每个 I/O EventLoop 拥有一个 VCL worker；多 worker 服务端使用同端口监听及 VPP 分流，连接不跨 worker 迁移。
- 支持非阻塞连接、连接超时、部分写、背压、手动读取、真实 TCP FIN 半关闭及关闭清理。
- `runtime.metrics()` 返回收发字节、事件、EAGAIN、活跃 worker/注册项及 poller 故障计数。
- 不支持 IPv6、UDP、`FileRegion/sendfile`、Channel 跨 EventLoop 迁移及虚拟线程 I/O worker。业务处理可以使用其他线程，通过 Channel 调度回所属 EventLoop。
- native socket option 尚未验证其实际效果，显式拒绝设置；支持 Netty 本地选项、`SO_BACKLOG` 和 `ALLOW_HALF_CLOSURE`。FIFO 大小通过 VCL 配置调整。
- VPP 25.10 的本地 cut-through transport **没有 TCP 半关闭能力**，不能用本地连接推断 wire FIN 语义；真正经过 TCP 协议栈的路径已单独验证。
- VPP 退出后关闭受影响 Channel 并使待完成操作失败；不恢复已有 TCP 连接。

## 构建

安装 Java 26 与 Maven 3.9+，设置 `JAVA_HOME`。普通构建不需要 VPP 或 C 编译器：

```bash
mvn test package
```

产物为 `target/netty-vcl-0.1.0-SNAPSHOT.jar`。运行应用需要：

```bash
export VCL_CONFIG=/absolute/path/to/vcl.conf
export LD_LIBRARY_PATH=/path/to/vpp/lib/x86_64-linux-gnu
java --enable-native-access=ALL-UNNAMED -cp ... your.Main
```

没有 preview 参数。默认按 SONAME 加载 `libvppcom.so.25.10`，避免无意绑定其他版本；`-Dvcl.library=/absolute/path/libvppcom.so` 可显式覆盖顶层库，其依赖仍须由 Linux loader 找到。项目按 classpath 使用，若改为命名模块，需要将 native access 授予实际模块。

VPP 与 VCL 库必须来自相同 25.10 构建。修改 VPP 版本或 CPU 架构前，重新验证函数签名、结构体布局和常量；仅通过版本字符串不代表 ABI 相容。

## 接入

服务端使用辅助 Bootstrap，让监听与接收连接留在同一个 EventLoop：

```java
try (VclRuntime runtime = VclRuntime.open("my-service")) {
    var group = new MultiThreadIoEventLoopGroup(2, VclIoHandler.newFactory(runtime));
    try {
        VclServerBinding binding = new VclServerBootstrap(group)
            .childHandler(new ChannelInitializer<VclSocketChannel>() {
                @Override protected void initChannel(VclSocketChannel channel) {
                    channel.pipeline().addLast(new MyProtocolHandler());
                }
            })
            .bind(new InetSocketAddress("0.0.0.0", 9090)).sync().getNow();
        // 保持服务运行；停止时先关 listener，再关闭连接 / group。
        binding.closeAsync().sync();
    } finally {
        group.shutdownGracefully().sync();
    }
}
```

客户端使用标准 `Bootstrap`：

```java
Channel channel = new Bootstrap()
    .group(group)
    .channel(VclSocketChannel.class)
    .handler(new MyClientInitializer())
    .connect("10.0.0.10", 9090).sync().channel();
```

地址仅支持已解析 IPv4。多 worker 服务端必须指定非零端口；`childHandler` 应遵守 Netty 的共享规则，通常传入 `ChannelInitializer`。绑定操作按 worker 顺序执行，失败时关闭本次已经创建的 listener。

一个 JVM 同时只允许一个 `VclRuntime`；管理线程保留 VCL 默认 worker，因此 `max-workers` 至少设为 I/O 线程数加一。Runtime 必须在所有相关 EventLoop 终止后关闭。`binding.closeAsync()` 只关闭 listener，不关闭已接收连接；关闭 group 时会清理其余 Channel。两个方向均 shutdown 后，transport 会关闭 session 完成 VCL 的 FIN 收尾。

可运行的交互式 echo 示例：

```bash
mvn dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath -DincludeScope=runtime
java --enable-native-access=ALL-UNNAMED \
  -cp "target/classes:$(cat target/runtime-classpath)" \
  dev.vcl.netty.example.EchoServer 9090 2
```

## 验证真实 VPP

复制 `config/vcl.conf.example` 并调整 application socket 路径；VPP 需启用 session 和 application socket API，并提供可访问的网络接口：

```text
session { enable use-app-socket-api evt_qs_memfd_seg }
```

可选的 C **测试探针**只验证头文件 ABI，不参与产品构建，也不会生成运行时 shim：

```bash
VPP_INCLUDE_DIR=/path/to/vpp/include scripts/check-abi.sh
```

对现有 VPP 运行本地 session 集成测试：

```bash
VCL_CONFIG=/absolute/path/vcl.conf LD_LIBRARY_PATH=/path/to/vpp/lib/x86_64-linux-gnu mvn verify
```

不提供 `VCL_CONFIG` 时，VPP 集成测试会明确跳过。直接运行的测试不会停止现有 VPP；故障注入只由下面的独立测试脚本启用。

完整验证脚本需要 `unshare`、`ip`、`rg`、VPP AF_PACKET plugin 及允许创建用户/网络命名空间的内核。它在独立网络空间中创建 veth 和 VPP，运行 Linux TCP 互通、跨 JVM VCL 测试，最后终止其自建 VPP 验证故障行为：

```bash
VPP_PREFIX=/path/to/vpp/usr scripts/verify-vpp.sh
```

`VPP_PREFIX` 默认 `/usr`；可覆盖 `VPP_BIN`、`VPP_CTL`、`VPP_LIB_DIR`、`VPP_PLUGIN_DIR` 和 `MVN`。额外参数会传给 Maven。脚本不修改主机网络，并在退出时清理自建 VPP。不要并行运行多个脚本实例，也不要在脚本运行中执行 `mvn clean`，因为测试 socket 和日志位于 `target/wire/`。

结果及未覆盖内容见 [验证记录](docs/VALIDATION.md)，实现说明见 [架构说明](docs/ARCHITECTURE.md)，后续优先级和交付安排见 [开发计划](docs/ROADMAP.md)。

现有IPv4 TCP行为已按Netty 4.2.7.Final epoll transport class逐项对照，修正接收预算、连接错误、FIN/半关闭和Promise顺序等差异，见[语义对照](docs/EPOLL-PARITY.md)。自定义接收分配器与epoll一样要求 `newHandle()` 返回 `RecvByteBufAllocator.ExtendedHandle`。

## 与 epoll 比较

对比结果、资源预算和原始数据见 [性能报告](docs/BENCHMARK.md)。基准代码位于 `src/benchmark/java`，epoll native 和 HdrHistogram 只在 `benchmark` profile 中引入，产品 transport 仍直接使用 FFM。

当前环境中 VPP 单独绑核的复测见 [VPP 独立核心性能报告](docs/BENCHMARK-DEDICATED-VPP.md)。该次复测使用 netkit L2 接口对；历史报告使用 veth，硬件及资源预算也不同。

DPDK `net_ring` 软件回环与同期 epoll/VCL TCP 复测见 [DPDK ring 性能参考](docs/BENCHMARK-DPDK-RING.md)。ring 的包/秒速率只覆盖内存队列转发，不代表 VPP/VCL 的 TCP 吞吐。

```bash
mvn -Pbenchmark -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath
VPP_PREFIX=/path/to/vpp/usr scripts/benchmark.sh --warmup 5 --seconds 10 --repeats 3 --output target/benchmark-new
python3 scripts/summarize-benchmark.py target/benchmark-new/results.json
```

基准通过两个网络命名空间和同一 veth 分别运行 epoll 与 VCL，避免 epoll 走 loopback。负载端占一个核，服务端 JVM 与 VPP 合计共享另一个核；默认每组预热 5 秒、测量 10 秒、重复 3 轮，统计吞吐、RTT p50/p99 和进程 CPU 时间。输出目录必须全新。Python 画图需 matplotlib；运行网络拓扑需允许用户/网络命名空间。

本测试是固定连接数与窗口的闭环 echo，不是生产压测或服务端峰值证明。默认三组为：64B/1连接/窗口1、1KiB/32连接/窗口16、64KiB/8连接/窗口4。

CPU 充足时，可显式给 VPP 分配独立核心。先检查 CPU 拓扑，选择不同物理核上的逻辑 CPU；以下示例使用 CPU 0、1、2。`--vpp-cpu` 不指定时仍与服务端 JVM 共享核心。内核不支持 veth 时，可用 `--link-type netkit` 选择 L2 接口对，结果会记录所用接口类型。服务端 CPU 始终计入 JVM + VPP，两种绑核预算不能直接混合比较：

```bash
VPP_PREFIX=/path/to/vpp/usr scripts/benchmark.sh \
  --client-cpu 0 --server-cpu 1 --vpp-cpu 2 --link-type netkit \
  --warmup 5 --seconds 10 --repeats 3 --output target/benchmark-dedicated
python3 scripts/summarize-benchmark.py target/benchmark-dedicated/results.json \
  --report target/benchmark-dedicated/report.md --no-plot
```
