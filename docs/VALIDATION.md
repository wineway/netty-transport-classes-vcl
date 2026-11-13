# 验证记录

验证日期：2026-11-13。

## 基线与路径

- Eclipse Temurin OpenJDK **26.0.2.1+1**，编译目标 `--release 26`。
- Netty **4.2.7.Final**；VPP/VCL **25.10-release**，来自同一组官方发行包。
- Linux x86_64；直接 FFM 调用，无 JNI 或项目运行时 C shim。
- 本地路径：两个独立 JVM / VCL application，通过 VPP 本地 session 通信。
- 报文路径：Linux TCP socket ↔ veth ↔ VPP AF_PACKET ↔ VPP TCP/session ↔ VCL ↔ Netty。

报文测试在独立 user/network namespace 内执行。Linux 地址为 `10.222.0.1`，VPP 地址为 `10.222.0.2`；使用小型 TPACKET_V2 ring，保留 checksum offload 元数据，符合当前环境的 memlock 限额。没有修改主机网络。

## 已通过的验证

| 验证 | 结果 |
|---|---|
| 独立 M0 FFM 探针 | 两个 worker 同端口监听，64 次回显全部成功，各处理 32 个连接 |
| VPP 头文件 ABI 探针 | endpoint、packed epoll_event、pollfd 布局及地址/事件/errno 常量匹配 |
| 单元测试 | 4 项通过：endpoint 字节序与内存、非对齐 epoll event、地址拒绝、native option 拒绝 |
| Linux TCP 互通 | Netty 客户端与 Linux 服务端、Linux 客户端与多 worker Netty 服务端，实际 FIN 与关闭后反向响应通过 |
| 连接生命周期 | 64 次连接回显、connect timeout、取消未完成连接、连接失败、部分监听失败回滚通过 |
| 读取与背压 | AUTO_READ=false、显式 read、16MiB 校验回显、暂停/恢复接收、写水位、阻塞写期间跨线程任务通过 |
| 线程与销毁 | 拒绝虚拟线程拥有 worker，EventLoop 终止后活跃 worker 为零 |
| VPP 退出故障 | 停止脚本自建 VPP 后，Channel 自动关闭，待发送 Promise 失败，清理完成 |

完整验收为 **4 项单元测试 + 11 项集成测试，失败 0、错误 0、跳过 0**。测试通过后产出 `target/netty-vcl-0.1.0-SNAPSHOT.jar`。

复现命令：

```bash
export JAVA_HOME=/path/to/jdk-26
VPP_INCLUDE_DIR=/path/to/vpp/usr/include scripts/check-abi.sh
VPP_PREFIX=/path/to/vpp/usr scripts/verify-vpp.sh
```

`scripts/verify-vpp.sh` 自动设置独立实例的 VCL 配置、网络地址和故障注入 PID。`VppDetachIT` 按字母顺序最后执行，只停止此实例。不要向任意现有 VPP 设置 `VCL_TEST_VPP_PID`。

原始 JUnit XML/文本报告在 `target/surefire-reports/` 和 `target/failsafe-reports/`，拓扑和运行日志在 `target/wire/`。故障测试会主动停止 VPP，因此退出时诊断日志中出现 socket connection refused 是预期的。

## 已确认的上游行为与适配

VPP 本地 transport 避免连接到同一 application 自己的 listener；因此本地集成测试使用两个 JVM，而非在同一 application 中做自连接。

VPP 25.10 cut-through transport 没有 TCP half-close 实现。该路径只验证输出关闭后的写入拒绝；FIN 和半关闭后继续响应的语义由实际 TCP 报文路径验证。

真实 TCP 已接收 FIN 后，VCL shutdown 可能只设置标志；transport 在两个方向关闭后延后关闭 session，发送最后的 disconnect reply/FIN。

libc errno 通过 FFM `captureCallState("errno")` 捕获，避免 JVM/native 转换期间的后续调用覆盖错误码。VCL 自身的负 errno 返回值直接解释，不读取 libc errno。

## 尚未完成的生产验收

- 一小时及更长的持续收发、连接 churn 和 native 内存/fd 趋势检查。
- 目标部署环境中与 Netty epoll 的新建连接速率、扩展能力及峰值比较。当前受限环境已完成固定连接 echo 的吞吐、p50/p99 和 CPU 比较，计入 VPP CPU，见[性能报告](BENCHMARK.md)。
- 真实 NIC、DPDK、NUMA、绑核、真实业务协议和 TLS 组合验证。
- 多进程重启、部署升级、VPP 版本升级及更大 worker/连接规模的兼容性验证。

当前结果支持工程 MVP 的功能可行性，不构成性能提升或生产稳定性结论。
