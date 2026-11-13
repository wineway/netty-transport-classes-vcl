# VPP 26.10 segment 句柄复用故障

已确认：这个故障发生在 libvppcom 内部的 segment 映射管理。使用复制接收或先查询 accept 就绪状态都不能替代该问题的修复。原始正式版源码未改；独立副本已有候选补丁并编译通过，但尚未运行验证。

## 可复现的事件顺序

固定源码 `4acb320269bc4f3533e35c80afedac38853b2f60`，debug + AddressSanitizer 构建，仅修改编译选项。Java 平台线程直接调用 `vppcom_*`，无 VLS。

`workers-after-close` 先在 worker 0 建立并关闭 IPv4/IPv6 TCP，再注册 worker 1、2 并接收连接。gdb 记录到：

```text
ATTACH worker=0 handle=200000000 name=seg-1-1-3
ATTACH worker=1 handle=200000000 name=memfd-8589934592
DETACH worker=0 handle=200000000
ERROR: AddressSanitizer: use-after-poison
```

这里的 handle 是十六进制。worker 1 新映射的 MQ segment 与 worker 0 旧 cut-through segment 的句柄相同。随后 worker 0 消费先前排队的删除事件，删除了句柄当前对应的新映射。

源码路径：

- `src/vnet/session/segment_manager.c:335`：handle 仅由 segment-manager pool index 和 segment index 组成，无 generation。
- `src/vcl/vcl_private.c:509`：attach 使用 `hash_set(segment_table, handle, new_index)` 覆盖旧索引。
- `src/vcl/vppcom.c:1084`：收到删除事件，仅按 handle 调用 detach。
- `src/vcl/vcl_private.c:532`：detach 查当前索引，删除对应 segment。

ASan 的 poison history 明确显示 worker 0 经 `vcl_session_app_del_segment_handler → vcl_segment_detach → fifo_segment_cleanup → _vec_free` 释放 MQ；另一 worker 随后从 `vppcom_epoll_wait → vcl_epoll_wait_handle_mq → svm_msg_q_size` 访问同一区域。这也解释了此前约 4 GB 的异常扩容：队列元数据已失效，而不是 heap 配置过小。

## 已做与未做的规避验证

- accept 前调用公开 `VPPCOM_ATTR_GET_NREAD`：仍可复现 native use-after-poison，不能称为修复。
- 在任何连接建立前注册全部 worker：独立 80 连接、关闭一个 listener 的短测在两种模式的 ASan 下完成。它只证明该实验路径，不证明所有并发关闭/新建组合安全。
- 原有固定版本、不修改 VPP 的约束仍生效，未改为 VLS、未引入私有结构写入、未禁止新的 Channel 操作来掩盖问题。

## 独立副本实验（已批准，运行验证暂缓）

在新的独立 checkout 中制作和验证 libvppcom 映射生命周期修复，原正式版 checkout 保持不变。修复须使旧删除事件只能释放它对应的旧映射，不能释放同句柄的新映射。候选是保留同句柄待删除映射的顺序/归属信息；仅忽略重复 attach 或删除事件不可接受，因为会泄漏资源或让新 session 绑定旧 FIFO。

候选补丁见 [segment-lifetime.patch](patches/vpp-26.10-segment-lifetime.patch)，涉及 attach、正常 detach、断连清理和 app destroy。用户因安全策略要求暂缓验证，继续 Java 实现；完整恢复清单见 [TODO](../TODO.md)。不能预先保证候选设计足够，跨 worker 控制事件排序必须进一步核实。

允许制作实验补丁不等同于已批准最终运行于修改版 VPP；最终版本及兼容边界应以补丁和验证结果再确认。
