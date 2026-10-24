#!/usr/bin/env python3
"""把效果评估实验的 CSV（docs/eval/*.csv，由 tools/run_evaluation.py 生成）画成报告用的图表。

用法（仓库根目录）：
    pip install matplotlib          # 只有这个脚本需要第三方库
    python3 tools/plot_eval.py      # 输出到 docs/images/eval/*.png

每张图只有一个纵轴；柱子上方标出平均值，误差线是各随机种子之间的标准差（与 summary.md 一致）。
颜色：蓝 = 基准方案，橙 = 改进方案（两种颜色在色盲和黑白打印下都能区分，柱上另有数字）。
"""
import csv
import logging
import os
import statistics as st
import sys

try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
except ImportError:
    sys.exit('需要 matplotlib：pip install matplotlib')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVAL = os.path.join(ROOT, 'docs', 'eval')
OUT = os.path.join(ROOT, 'docs', 'images', 'eval')

BLUE, ORANGE = '#2a78d6', '#eb6834'
SURFACE, INK, INK2, GRID = '#fcfcfb', '#0b0b0b', '#52514e', '#e4e3df'

# 常见系统里的中文字体，按顺序找第一个装了的
CJK_FONTS = ['Microsoft YaHei', 'SimHei', 'PingFang SC', 'Heiti SC', 'Noto Sans CJK SC',
             'Source Han Sans SC', 'WenQuanYi Zen Hei', 'WenQuanYi Micro Hei']


def setup_style():
    logging.getLogger('matplotlib.font_manager').setLevel(logging.ERROR)
    installed = {f.name for f in font_manager.fontManager.ttflist}
    font = next((f for f in CJK_FONTS if f in installed), None)
    if font is None:
        print('警告：没找到中文字体，图中的中文可能显示成方框', file=sys.stderr)
    plt.rcParams.update({
        'font.sans-serif': ([font] if font else []) + ['DejaVu Sans'],
        'axes.unicode_minus': False,
        'figure.facecolor': SURFACE,
        'axes.facecolor': SURFACE,
        'savefig.facecolor': SURFACE,
        'axes.edgecolor': INK2,
        'axes.labelcolor': INK2,
        'xtick.color': INK2,
        'ytick.color': INK2,
        'text.color': INK,
        'axes.spines.top': False,
        'axes.spines.right': False,
        'axes.grid': True,
        'axes.grid.axis': 'y',
        'grid.color': GRID,
        'grid.linewidth': 0.8,
        'axes.axisbelow': True,
        'font.size': 11,
    })


def load(name):
    with open(os.path.join(EVAL, name + '.csv'), encoding='utf-8') as f:
        return list(csv.DictReader(f))


def stats(rows, key, **match):
    vals = [float(r[key]) for r in rows if all(r[k] == v for k, v in match.items())]
    if not vals:
        raise ValueError(f'没有数据：{key} {match}')
    return st.mean(vals), (st.stdev(vals) if len(vals) > 1 else 0.0), len(vals)


def grouped_bars(ax, groups, series, ylabel, fmt='{:.1f}'):
    """groups: x 轴分组名；series: [(图例名, 颜色, [(均值, 标准差), ...])]，每组一个值。"""
    n = len(series)
    width = 0.34
    gap = 0.03                      # 同组两根柱子之间留一点缝
    for i, (label, color, vals) in enumerate(series):
        xs = [g + (i - (n - 1) / 2) * (width + gap) for g in range(len(groups))]
        means = [v[0] for v in vals]
        sds = [v[1] for v in vals]
        bars = ax.bar(xs, means, width, color=color, label=label, zorder=2)
        if any(sds):
            ax.errorbar(xs, means, yerr=sds, fmt='none', ecolor=INK2, elinewidth=1, capsize=3, zorder=3)
        for b, m, s in zip(bars, means, sds):
            ax.text(b.get_x() + b.get_width() / 2, m + s + ax_pad(means), fmt.format(m),
                    ha='center', va='bottom', fontsize=10, color=INK)
    ax.set_xticks(range(len(groups)))
    ax.set_xticklabels(groups)
    ax.set_ylabel(ylabel)
    ax.legend(frameon=False, loc='upper left')
    ax.set_ylim(0, ax.get_ylim()[1] * 1.12)


def ax_pad(values):
    return max(values) * 0.015


def save(fig, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    fig.savefig(path, dpi=150, bbox_inches='tight')
    plt.close(fig)
    print('已生成', os.path.relpath(path, ROOT))


def plot_dispatch():
    groups, greedy, hung = [], [], []
    for name, label in [('dispatch-8x6', '8 车，6 单/分\n（轻载）'),
                        ('dispatch-16x12', '16 车，12 单/分\n（中载）'),
                        ('dispatch-16x16', '16 车，16 单/分\n（接近饱和）')]:
        rows = load(name)
        groups.append(label)
        greedy.append(stats(rows, 'avgWait', strategy='GREEDY')[:2])
        hung.append(stats(rows, 'avgWait', strategy='HUNGARIAN')[:2])
    fig, ax = plt.subplots(figsize=(7.2, 4.2))
    grouped_bars(ax, groups, [('贪心', BLUE, greedy), ('匈牙利算法', ORANGE, hung)], '平均等待时间（秒）')
    ax.set_title('派单策略对比：乘客平均等待时间（30 分钟仿真 × 3 个种子）', loc='left', fontsize=12)
    save(fig, 'dispatch-wait.png')


def plot_rebalance():
    groups, off, on = [], [], []
    for rate in (4, 5, 6):
        rows = load(f'rebalance-rate{rate}')
        groups.append(f'{rate} 单/分')
        off.append(stats(rows, 'avgWait', rebalance='off')[:2])
        on.append(stats(rows, 'avgWait', rebalance='on')[:2])
    fig, ax = plt.subplots(figsize=(7.2, 4.2))
    grouped_bars(ax, groups, [('不调度', BLUE, off), ('空车调度', ORANGE, on)], '平均等待时间（秒）')
    ax.set_title('潮汐需求下的空车调度：平均等待时间（8 车 × 8 个种子）', loc='left', fontsize=12)
    save(fig, 'rebalance-wait.png')


def plot_vision():
    rows = load('vision-bench')
    groups = [f"{r['width']}×{r['height']}\n加速比 {float(r['speedup']):.2f}" for r in rows]
    serial = [(float(r['serialMs']), 0.0) for r in rows]
    parallel = [(float(r['parallelMs']), 0.0) for r in rows]
    fig, ax = plt.subplots(figsize=(7.2, 4.2))
    grouped_bars(ax, groups, [('单线程', BLUE, serial), (f"并行（{rows[0]['threads']} 核）", ORANGE, parallel)],
                 '处理耗时（毫秒，越低越好）')
    ax.set_title('图像处理加速：颜色分割 + 开运算 + 连通域（中位数）', loc='left', fontsize=12)
    save(fig, 'vision-speed.png')


def main():
    setup_style()
    plot_dispatch()
    plot_rebalance()
    plot_vision()


if __name__ == '__main__':
    main()
