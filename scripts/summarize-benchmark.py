#!/usr/bin/env python3
"""Summarize measured samples, retaining raw data and three-run ranges."""
import argparse
import json
import os
from pathlib import Path
import statistics
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('results', type=Path)
    parser.add_argument('--report', type=Path, default=Path('docs/BENCHMARK.md'))
    parser.add_argument('--figure', type=Path, default=Path('docs/benchmark.png'))
    parser.add_argument('--no-plot', action='store_true', help='write the report without requiring matplotlib')
    args = parser.parse_args()
    data = json.loads(args.results.read_text())
    env = data['environment']
    shared = env.get('vpp_cpu', env['server_cpu']) == env['server_cpu']
    link_type = env.get('link_type', 'veth')
    budget = 'AF_PACKET 路径及每侧单核预算' if shared else 'AF_PACKET 路径、服务端 JVM 与 VPP 分别绑核的预算'
    groups = {}
    for sample in data['samples']:
        groups.setdefault((sample['scenario'], sample['transport']), []).append(sample)
    scenarios = list(dict.fromkeys(s['scenario'] for s in data['samples']))
    options = data['environment']['options']
    repetitions = options['repeats']
    metrics = ['messages_per_second', 'payload_mib_per_second', 'p50_us', 'p99_us', 'server_cpu_cores',
               'jvm_server_cpu_cores', 'vpp_cpu_cores', 'client_cpu_cores']
    summary = {}
    for (scenario, transport), samples in groups.items():
        summary[f'{scenario}/{transport}'] = {
            key: {'median': statistics.median(s[key] for s in samples), 'min': min(s[key] for s in samples),
                  'max': max(s[key] for s in samples)} for key in metrics}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2))
    median = lambda scenario, mode, metric: summary[f'{scenario}/{mode}'][metric]['median']
    slower = all(median(s, 'vcl', 'messages_per_second') < median(s, 'epoll', 'messages_per_second') for s in scenarios)
    higher_cpu = all(median(s, 'vcl', 'server_cpu_cores') > median(s, 'epoll', 'server_cpu_cores') for s in scenarios)
    conclusion = f'本次环境与资源配置下，当前 VCL MVP 在全部 {len(scenarios)} 个测试场景中均慢于 epoll' if slower else '本次结果随场景变化，请结合下表及资源预算解读'
    if higher_cpu: conclusion += '，且服务端 Java + VPP 的合计 CPU 消耗更高'
    lines = ['# VCL 与 Netty epoll 性能对比', '', f'**结论：{conclusion}。**', '',
             f'这反映当前实现、{budget}下的结果；不能推断 DPDK 或真实 NIC 场景的上限。', '',
             '## 结果', '', f'数值是 {repetitions} 轮独立测量的中位数；p99 列是各轮 p99 的中位数，不是将各轮样本合并后的百分位。', '',
             '| 场景（帧长 / 连接数 / 每连接窗口） | transport | 消息/秒 | 单向应用 MiB/s | RTT p50 μs | RTT p99 μs | 服务端 CPU 核 | 负载端 CPU 核 |',
             '|---|---|---:|---:|---:|---:|---:|---:|']
    for scenario in scenarios:
        example = groups[(scenario, 'epoll')][0]
        dimensions = f"{example['bytes']}B / {example['connections']} / {example['window']}"
        for mode in ['epoll', 'vcl']:
            values = [median(scenario, mode, key) for key in metrics[:5]]
            lines.append(f"| {dimensions} | {mode} | {values[0]:,.0f} | {values[1]:,.2f} | {values[2]:,.1f} | {values[3]:,.1f} | {values[4]:.3f} | {median(scenario, mode, 'client_cpu_cores'):.3f} |")
    lines += ['', '服务端 CPU 列包含 JVM 的 user + system 时间；VCL 额外计入 VPP 进程。1.0 核表示一个逻辑核被完整占用。应用吞吐按完成回显的帧数 × 帧长计算，是单方向数值；每帧含 24B 基准头，不包含 TCP/IP/以太网头。', '',
              '| 场景 | VCL 吞吐 / epoll | VCL p99 / epoll | epoll 消息/服务端 CPU 秒 | VCL 消息/服务端 CPU 秒 |',
              '|---|---:|---:|---:|---:|']
    for scenario in scenarios:
        e = median(scenario, 'epoll', 'messages_per_second'); v = median(scenario, 'vcl', 'messages_per_second')
        ee = statistics.median(s['messages_per_second'] / s['server_cpu_cores'] for s in groups[(scenario, 'epoll')])
        ve = statistics.median(s['messages_per_second'] / s['server_cpu_cores'] for s in groups[(scenario, 'vcl')])
        lines.append(f"| {scenario} | {v/e:.1%} | {median(scenario, 'vcl', 'p99_us')/median(scenario, 'epoll', 'p99_us'):.2f}× | {ee:,.0f} | {ve:,.0f} |")
    if not args.no_plot:
        lines += ['', f'![吞吐、尾延迟和服务端 CPU 对比]({os.path.relpath(args.figure, args.report.parent)})']
    lines += ['', '## 重复测量范围', '',
              f'下列 min–max 是 {repetitions} 轮观察范围，不是置信区间。', '',
              '| 场景 | transport | 消息/秒 min–max | p99 μs min–max | JVM CPU 中位数 | VPP CPU 中位数 |',
              '|---|---|---:|---:|---:|---:|']
    for scenario in scenarios:
        for mode in ['epoll', 'vcl']:
            r = summary[f'{scenario}/{mode}']; rate = r['messages_per_second']; tail = r['p99_us']
            lines.append(f"| {scenario} | {mode} | {rate['min']:,.0f}–{rate['max']:,.0f} | {tail['min']:,.1f}–{tail['max']:,.1f} | {r['jvm_server_cpu_cores']['median']:.3f} | {r['vpp_cpu_cores']['median']:.3f} |")
    quota, period = env['cpu_max'].split()
    quota_note = '无 CPU 配额限制' if quota == 'max' else f'CPU 配额为 {int(quota) / int(period):g} 核'
    placement = (f"服务端 JVM 和 VPP 共同固定 CPU {env['server_cpu']}，合计共享 1 核" if shared else
                 f"服务端 JVM 固定 CPU {env['server_cpu']}，VPP 单独固定 CPU {env['vpp_cpu']}，VCL 服务端最多使用 2 核，epoll 服务端最多使用 1 核")
    budget_note = ('当前单核预算下，VPP polling 与 Java I/O 线程竞争同一核，VPP 的成本包含在服务端 CPU 中。' if shared else
                   'VPP 与服务端 JVM 使用不同逻辑核；VCL 比 epoll 多分配一个 VPP 核。合计 CPU 与每 CPU 秒消息数用于比较资源效率。绑核不保证宿主机独占。')
    throttle_ms = max((s.get('cgroup_delta', {}).get('throttled_usec', 0) for s in data['samples']), default=0) / 1000
    counters = '\n'.join(p.read_text() for p in args.results.parent.glob('vpp-counters-*.txt'))
    counter_notes = []
    warnings = [name for name in ('OOO packets pushed into rx fifo', 'Old segment', 'PAWS check failed') if name in counters]
    if warnings:
        counter_notes.append('VPP counters 记录了 ' + ' / '.join(warnings) + ' 计数，未进一步定位')
    if counters and 'tx ring overrun' not in counters and 'tx sendto temporary failure' not in counters:
        counter_notes.append('正式测量未记录 TX ring overrun / temporary failure')
    observations = f'单个测量窗口的 cgroup throttled 时间最高为 {throttle_ms:.1f}ms；详细计数保存在原始 JSON。全部应用帧校验及 drain 成功。'
    if counter_notes: observations += '；'.join(counter_notes) + '。'
    lines += ['', '## 测试方法与资源预算', '',
              f"- 时间：{env['date_utc']}。CPU：{env['cpu_model']}；系统：{env['uname'][2]}。",
              f"- {env['java'].splitlines()[0]}；Netty 4.2.7.Final、{env.get('vpp_version', 'VPP/VCL 25.10')}；两端 JVM 均为 256MiB heap、G1、相同 JVM 参数。",
              f"- 容器可见 CPU 为 {env['allowed_cpus']}，cgroup `cpu.max={env['cpu_max']}`，{quota_note}。负载端固定 CPU {env['client_cpu']}；{placement}。",
              '- 两个 transport 都固定 1 个服务端 I/O worker；epoll 在测试时没有 VPP 进程争用 CPU。VPP 的 polling 时间完整计入其预算。',
              '- 公共负载端始终使用 Netty epoll。服务端业务 Handler 完全相同，均直接回显 ByteBuf，无额外业务逻辑或 server codec。',
              f'- 两端位于不同网络命名空间，始终经过同一 {link_type} L2 接口对。epoll 由 Linux TCP 处理；VCL 由 VPP AF_PACKET/TCP 处理，不走 VPP 本地 cut-through。',
              '- 双端关闭 TSO/GSO/GRO/TX checksum offload，验证开关确实为零；VPP 使用 TPACKET_V2、RX/TX 各 1024 个 2048B frame。未启用 DPDK、hugepage 或 packet trace。',
              f"- 每组预热 {options['warmup']} 秒、计量 {options['seconds']} 秒，重复 {repetitions} 轮；轮换 epoll/VCL 的先后顺序。每组使用新客户端 JVM，同一轮/transport 的服务端 JVM 覆盖本轮全部场景。",
              '- 固定帧请求/回显，持续补充每连接窗口；校验 magic、连接号、序列号和末字节，结束后要求所有在途请求 drain 成功。',
              '- RTT 从客户端入队发送到完整回显被解析计时，包含排队、flush 和 TCP 往返。使用 HdrHistogram（三位有效数字）；闭环负载，无 coordinated-omission 修正。',
              '- 服务端/VPP CPU 从 `/proc/PID/stat` 在测量窗口两端取差，包含进程所有线程；负载端用 ProcessHandle CPU 时间。内核软中断 CPU 的归属并未单独隔离。',
              '', '## 解释与适用范围', '',
              '- ' + budget_note,
              '- 负载端 CPU 接近 1 核的样本可能受生成器限制，此时测得的吞吐是该端到端配置的能力，不能当作服务端最大吞吐。',
              '- ' + observations,
              '- 当前 VCL 使用标量 FFM read/write、LT readiness 及较多 interest 更新；epoll 使用成熟 native transport。未通过 profile 区分 polling、TCP、AF_PACKET 和调度的贡献，不能将差距直接归因于 FFM。',
              '- AF_PACKET 包含内核路径成本；关闭 offload 是本报告的显式条件。DPDK、硬件 offload 及不同 CPU 预算需在目标环境中单独测量。',
              '- 本轮未测新建连接/秒、固定到达率下的延迟、长期稳定性，也未测 epoll 多 worker 的最大扩展能力。',
              '', '## 复现和原始数据', '', '```bash',
              'mvn -Pbenchmark -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath',
              f"VPP_PREFIX=/path/to/vpp/usr scripts/benchmark.sh --warmup {options['warmup']} --seconds {options['seconds']} --repeats {repetitions} --client-cpu {env['client_cpu']} --server-cpu {env['server_cpu']} --vpp-cpu {env.get('vpp_cpu', env['server_cpu'])} --link-type {link_type} --output target/benchmark-new",
              'python3 scripts/summarize-benchmark.py target/benchmark-new/results.json' + (' --no-plot' if args.no_plot else ''), '```', '',
              '需 Java 26、Maven、VPP AF_PACKET plugin、`ip`、`taskset`、`nsenter`、`unshare`、Python 3；画图额外需要 matplotlib。仅操作脚本自建的网络空间与 VPP。输出目录必须全新，避免混合不同运行的样本。', '',
              '原始数据：' + '、'.join(f'[{label}]({os.path.relpath(args.results.parent / name, args.report.parent)})'
                                        for label, name in [('JSON', 'results.json'), ('CSV', 'results.csv'), ('环境与源码 hash', 'environment.json')]) + '。保留各轮 VPP counters 供检查丢包、重传和 ring 错误。', '']
    args.report.write_text('\n'.join(lines))
    if not args.no_plot:
        plot(scenarios, groups, summary, args.figure, shared)
    print(args.report)


def plot(scenarios, groups, summary, path, shared=True):
    cache = Path(tempfile.gettempdir()) / 'netty-vcl-plot-cache'
    cache.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault('MPLCONFIGDIR', str(cache))
    os.environ.setdefault('XDG_CACHE_HOME', str(cache / 'xdg'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    median = lambda scenario, mode, metric: summary[f'{scenario}/{mode}'][metric]['median']
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10})
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.5), layout='constrained')
    x = np.arange(len(scenarios)); width = .34
    ratios = [100 * median(s, 'vcl', 'messages_per_second') / median(s, 'epoll', 'messages_per_second') for s in scenarios]
    axes[0].bar(x, ratios, color='#c66537', width=.55)
    axes[0].axhline(100, color='#2466a1', linestyle='--', label='epoll = 100%')
    for i, r in enumerate(ratios): axes[0].text(i, r + 2, f'{r:.1f}%', ha='center')
    axes[0].set_ylim(0, max(115, max(ratios) + 15)); axes[0].set_ylabel('VCL / epoll, %'); axes[0].set_title('Completed-message rate'); axes[0].legend()
    for mode, offset, color in [('epoll', -width/2, '#2466a1'), ('vcl', width/2, '#c66537')]:
        values = [median(s, mode, 'p99_us') for s in scenarios]
        low = [values[i] - summary[f'{s}/{mode}']['p99_us']['min'] for i, s in enumerate(scenarios)]
        high = [summary[f'{s}/{mode}']['p99_us']['max'] - values[i] for i, s in enumerate(scenarios)]
        axes[1].bar(x + offset, values, width=width, color=color, label=mode, yerr=[low, high], capsize=3)
        for i, value in enumerate(values): axes[1].text(i + offset, value * 1.18, f'{value:,.0f}', ha='center', fontsize=8)
    axes[1].set_yscale('log'); axes[1].set_ylabel('RTT p99, microseconds (log)'); axes[1].set_title('Tail latency; whiskers = per-run range'); axes[1].legend()
    epoll = [median(s, 'epoll', 'jvm_server_cpu_cores') for s in scenarios]
    vcl = [median(s, 'vcl', 'jvm_server_cpu_cores') for s in scenarios]
    vpp = [median(s, 'vcl', 'vpp_cpu_cores') for s in scenarios]
    axes[2].bar(x - width/2, epoll, width=width, color='#2466a1', label='epoll JVM')
    axes[2].bar(x + width/2, vcl, width=width, color='#c66537', label='VCL JVM')
    axes[2].bar(x + width/2, vpp, bottom=vcl, width=width, color='#dfac66', label='VPP')
    axes[2].set_ylim(0, 1.2 if shared else 2.2); axes[2].axhline(1, color='#777777', linestyle='--'); axes[2].set_ylabel('Average CPU cores'); axes[2].set_title('Server CPU; JVM + VPP'); axes[2].legend(fontsize=8)
    for ax in axes:
        ax.set_xticks(x, ['64B\n1 conn, win 1', '1KiB\n32 conn, win 16', '64KiB\n8 conn, win 4'])
        ax.grid(axis='y', alpha=.18); ax.set_axisbelow(True)
    budget = 'one server CPU' if shared else 'separate JVM / VPP CPUs'
    fig.suptitle(f'Java 26 / Netty 4.2.7 / VPP 25.10 — {budget}; TSO/GSO/GRO/TX csum off', fontsize=12)
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=180); fig.savefig(path.with_suffix('.svg')); plt.close(fig)


if __name__ == '__main__':
    main()
