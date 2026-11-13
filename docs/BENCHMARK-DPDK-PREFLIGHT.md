# VPP + DPDK / epoll：2026-11-14 环境检查

本轮在 DPDK 启动阶段停止，**没有生成有效的 DPDK 性能样本，也没有生成性能对比表**。此前使用 VPP 原生 AF_PACKET 的结果不能作为 DPDK 结果。

后续补充：同日已使用 `testpmd + net_ring0 + --no-huge --no-pci` 成功运行软件回环，见 [DPDK ring 与 TCP 性能参考](BENCHMARK-DPDK-RING.md)。这里的失败仅对应下述 VPP + AF_PACKET PMD 启动尝试；缺少 VFIO 或 hugepage 不代表无法运行所有 DPDK 软件 PMD。

进一步验证已成功让 VPP 本身接入 `net_ring0`，并观察到 ARP 经 DPDK ring 发出和收回，详见同一报告的“VPP 已实际接入 DPDK ring”补充。该验证尚未接入独立 TCP 对端。

原计划继续使用单个服务端 JVM worker，客户端、服务端 JVM、VPP 分别绑定 CPU 0、1、2，执行 64 B 单连接延迟、1 KiB 并发吞吐、64 KiB 批量传输三个场景，每个场景预热 5 秒、测量 10 秒，共一轮。

当前环境为 Ubuntu 26.04 amd64 / Linux 7.2.8-arch1-1，Intel i7-11700T。检查结果如下：

| 项目 | 实际情况 |
| --- | --- |
| VFIO / UIO 设备 | 没有暴露 `/dev/vfio` 或 `/dev/uio*` |
| Hugepage | `HugePages_Total=0`，没有 `/dev/hugepages` |
| 可见网络接口 | `lo`、`mihomo-tun`、`wlo1` |
| PCI 网络设备 | 可见 `iwlwifi`、`e1000e` 驱动的设备目录，但设备 `net` 目录没有映射的接口 |
| VPP DPDK 插件 | 官方 `vpp-plugin-dpdk_25.10-release_amd64.deb`，与所用 VPP/VCL 25.10 匹配 |
| 插件 ABI 检查 | 包 SHA256 与仓库元数据一致；`ldd` 没有缺失动态库 |

这里检查的是当前环境暴露的资源，不能据此判断宿主机是否具备 IOMMU 或可用于 DPDK 的网卡。没有修改宿主机网卡绑定、网络配置或 hugepage 配置。

另外尝试了无需 PCI 直通的 DPDK AF_PACKET 软件 PMD：在隔离的用户及网络命名空间建立 netkit L2 接口，启用 `dpdk_plugin.so`，使用 `no-pci no-hugetlb no-shconf` 和 `vdev net_af_packet0,iface=nvserver`。VPP 报告 `dpdk_config: rte_eal_init returned -1`，未进入流量测试阶段。该日志本身没有给出完整的 EAL 内部失败原因。

[VPP v25.10 官方构建配置](https://github.com/FDio/vpp/blob/v25.10/build/external/packages/dpdk.mk)使用 DPDK 25.07，并明确将 `net/af_packet` 列入禁用驱动。因而该官方插件也不能直接提供这条软件 PMD 路径。即使另行编译启用，按照 [DPDK AF_PACKET PMD 文档](https://doc.dpdk.org/guides/nics/af_packet.html)，它仍通过 Linux 内核发送和接收报文，不能代表物理网卡直通的 DPDK 性能。

完成物理网卡 DPDK 对比需要向测试环境提供可用的直通端口（例如 VFIO 映射的 NIC/VF）、对应内存资源，以及通往相同服务端的客户端路径。满足后才能按上述 CPU 分配生成有效对照。

原始证据位于 [本轮检查目录](benchmarks/2026-11-14-dpdk-preflight/environment.json)，包括实际启动配置、启动日志、插件依赖检查和官方构建配置快照。
