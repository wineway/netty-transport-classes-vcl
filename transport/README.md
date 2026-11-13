# Netty VCL transport

Java 26 FFM transport，包名前缀 `me.wineway.vcl`，目标 Linux x86_64、Netty `4.2.20.Final`、VPP `v26.10`（`4acb320269bc4f3533e35c80afedac38853b2f60`）。生产代码仅依赖 Netty transport 和 JDK；直接调用公开 `vppcom_*` 及 libc，没有 JNI、VLS 或生产 C 辅助库。

当前实现包含 TCP/UDP IPv4/IPv6、runtime、IoHandler、多 listener 启动辅助 API，以及中断和持续 poll 两种模式。原生生命周期仍有未解决项，当前不能视为完整生产验收；见 [能力清单](CAPABILITIES.md)、[验证记录](VALIDATION.md) 和 [待办](../TODO.md)。

## 构建

使用 JDK 26，并保证本地 Maven 仓库已有 Netty `4.2.20.Final` 及本模块 POM 指定的插件/测试依赖。Netty 可以从本地 `/code-repos/netty` 的独立副本构建安装；不要改原 checkout。

```sh
export JAVA_HOME=/path/to/jdk-26
export PATH="$JAVA_HOME/bin:$PATH"
mvn -f transport/pom.xml -Dmaven.repo.local=/path/to/m2 test
mvn -f transport/pom.xml -Dmaven.repo.local=/path/to/m2 package
```

`test` 默认只运行 ABI、内核等待和 Java 状态机测试。`*IT` 必须显式选择；需要本地 VPP 和实际 native 库，不能把模拟后端结果当作 native 验收。

## 接入

```java
try (VclRuntime runtime = VclRuntime.open(
        Path.of("/path/to/libvppcom.so.26.10"),
        Path.of("/run/vpp/app_ns_sockets/default"),
        VclMode.INTERRUPT, 2)) {
    var group = new MultiThreadIoEventLoopGroup(2,
            Thread.ofPlatform().name("vcl-", 0).factory(), runtime.ioHandlerFactory());
    try {
        Channel client = new Bootstrap().group(group)
                .channel(VclSocketChannel.class)
                .handler(myHandler)
                .connect("10.88.0.2", 32100).sync().channel();
        // 按普通 Netty ByteBuf 所有权规则读写。
        client.close().sync();
    } finally {
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }
}
```

运行 JVM 时必须加 `--enable-native-access=ALL-UNNAMED`，并通过 `LD_LIBRARY_PATH` 指向与头文件匹配的 VPP 动态库目录。使用用户已批准的 [segment 生命周期补丁](../preflight/patches/vpp-26.10-segment-lifetime.patch) 构建库；原正式版存在已复现的内存错误。

服务端用 `VclServer.bind(group, address, childHandlerSupplier)`：每个 EventLoop 一个 listener，child 在同一 EventLoop accept/read/write，不另设 boss。supplier 在每个新 child 的 EventLoop 上调用，返回该 child 的 handler 或 ChannelInitializer；全部 bind 成功后才报告成功，失败回收已创建的 listener。`server.close()` 关闭 listeners；group shutdown 关闭其余 child。完整可运行入口为 [EchoServer](src/main/java/me/wineway/vcl/example/EchoServer.java)，参数依次为库路径、SAPI socket、`interrupt|poll`、监听地址、端口、worker 数。

示例入口的完整启动方式（监听地址必须已经配置在 VPP 接口上）：

```sh
VCL_CP=transport/target/netty-transport-classes-vcl-0.1.0-SNAPSHOT.jar
for module in common buffer resolver transport; do
  VCL_CP="$VCL_CP:/path/to/m2/io/netty/netty-$module/4.2.20.Final/netty-$module-4.2.20.Final.jar"
done
LD_LIBRARY_PATH=/path/to/vpp/lib/x86_64-linux-gnu "$JAVA_HOME/bin/java" \
  --enable-native-access=ALL-UNNAMED -cp "$VCL_CP" me.wineway.vcl.example.EchoServer \
  /path/to/libvppcom.so.26.10 /run/vpp/app_ns_sockets/default interrupt 10.88.0.1 8080 2
```

一个进程同一时刻一个 runtime；模式一经选择不能在该进程切换。每个 EventLoop 必须使用固定平台线程；session 不允许迁移。先关闭 Channel/group，再关闭 runtime；错误顺序会明确抛异常。生命周期线程持有 application worker 0，配置 worker 数额外预留该 worker。poll 模式每个活跃 EventLoop 持续占用 CPU。

## 原生配置与复现

VPP 开启 `session { enable use-app-socket-api }`。Java 设置 VCL heap 256 MiB、初始 segment 64 MiB、追加 segment 32 MiB、RX/TX FIFO 各 1 MiB、预分配 8 对 FIFO、事件队列 2048，attach/session 原生超时 5 秒。中断模式启用 MQ eventfd，通过 libc epoll 同时等待 VCL 通知与 Java eventfd；可用时用 epoll_pwait2 纳秒期限，否则回退 epoll_wait。poll 模式不创建 MQ eventfd，也不睡眠。

本地网络测试使用两个独立 VPP，由 memif 相连，不配置主机 NIC。构建 pristine VPP 时启用 `-DVPP_PLUGINS=memif`，目标包含 `vpp vppctl memif_plugin`。libvppcom 使用单独的已批准补丁构建；server 与 library 的来源分别记录。

从本地 VPP checkout 准备全新目录的示例（只应用已批准的 segment 补丁）：

```sh
bash preflight/build-vpp.sh /code-repos/vpp /tmp/vcl-source-new /tmp/vcl-server-new
cmake -S /tmp/vcl-source-new/src -B /tmp/vcl-server-new -DVPP_PLUGINS=memif
cmake --build /tmp/vcl-server-new --target memif_plugin -j4
git clone --shared /tmp/vcl-source-new /tmp/vcl-client-source-new
git -C /tmp/vcl-client-source-new apply "$PWD/preflight/patches/vpp-26.10-segment-lifetime.patch"
cmake -S /tmp/vcl-client-source-new/src -B /tmp/vcl-client-new -G Ninja \
  -DCMAKE_BUILD_TYPE=release -DCMAKE_C_FLAGS=-std=gnu11 \
  -DVPP_USE_LTO=OFF -DVPP_BUILD_VCL_BAPI=OFF -DVPP_PLUGINS=none
cmake --build /tmp/vcl-client-new --target vppcom -j4
```


```sh
python3 transport/run-integration.py --backend all \
  --java-home "$JAVA_HOME" --maven-repo /path/to/m2 \
  --vpp-build /path/to/vpp-memif-build \
  --library /path/to/patched/libvppcom.so.26.10 \
  --topology memif --tests NetworkTransportIT --output /tmp/vcl-network-new
```

runner 只停止自己创建的 VPP/JVM，输出目录必须不存在。所有 Maven 调用离线。epoll 对照需要在本地 Maven 仓库安装 Netty native epoll `linux-x86_64` jar；使用相同 Java 契约和两个 JVM，网络走内核 loopback。VCL 使用静态 ARP/NDP，避免邻居发现丢弃首次 UDP。每次独立记录源码/库/补丁哈希、启动配置、CPU 绑定、原始 Maven/JUnit 结果及 VPP counters。

持续负载测试使用网络命令加 `--tests TransportStressIT`，默认每后端 60 秒、8 连接，可用 `--property vcl.stress.seconds=60` 调整（1–90 秒）。

其他真实测试：`--tests NativeServerIT`（两个 VCL 模式分别运行，验证多 listener 和每 child handler）；`--tests VppFailureIT --kill-vpp-on-ready`（仅单个 VCL backend，loopback）；`--tests NativeInitializationIT`（两种 VCL 模式分别运行，验证 attach 失败清理及重试）；`--tests NativeResourcesIT`（重复 application/worker/会话生命周期，保留已知失败）；`--tests TransportContractIT`（单 VPP cut-through，半关闭仍失败）。不要在没有对应拓扑/故障注入参数时一次执行全部 IT。

基准运行将上述网络命令的 `--tests` 改为 `TransportBenchmarkIT`；默认每场景预热最多 1000 消息/连接，测量 3 次，可用 `--property vcl.bench.repetitions=3` 调整次数。说明及数据在 [BENCHMARK.md](BENCHMARK.md)。
