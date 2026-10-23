#!/usr/bin/env python3
"""效果评估：一键跑完技术报告"效果评估"一章用到的全部离线实验，结果写到 docs/eval/。

用法（仓库根目录）：
    python3 tools/run_evaluation.py            # 完整版，约 5 分钟
    python3 tools/run_evaluation.py --quick    # 快速版（时间、种子减半），用于检查流程

每组实验都用相同的随机种子集合；订单序列只由种子决定（与车怎么跑无关），所以组间是"配对比较"。
输出：
    docs/eval/<实验>.csv   每次运行一行的原始数据（Experiment 工具的 CSV）
    docs/eval/summary.md   各组平均值 ± 标准差，以及结论里引用的关键数字
"""
import argparse
import csv
import io
import os
import statistics as st
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, 'server')
OUT = os.path.join(ROOT, 'docs', 'eval')
CP = os.pathsep.join([os.path.join('build', 'classes'), os.path.join('lib', 'gson-2.13.1.jar')])


def build():
    script = os.path.join(ROOT, 'scripts', 'build.bat' if os.name == 'nt' else 'build.sh')
    subprocess.run([script] if os.name == 'nt' else ['bash', script], check=True, stdout=subprocess.DEVNULL)


def experiment(name, args):
    """运行一组 Experiment，返回 CSV 行（字典列表），同时把原始 CSV 存到 docs/eval/<name>.csv。"""
    path = os.path.join(OUT, name + '.csv')
    cmd = ['java', '-Dstdout.encoding=UTF-8', '-cp', CP, 'com.zongc.dw.tools.Experiment',
           '--config', os.path.join('config', 'default.json'), '--csv', path] + args
    t0 = time.time()
    env = dict(os.environ, LC_ALL='C.UTF-8')
    subprocess.run(cmd, cwd=SERVER, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    with open(path, encoding='utf-8') as f:
        rows = list(csv.DictReader(f))
    print(f'  {name}: {len(rows)} 次运行，{time.time() - t0:.0f} s', flush=True)
    return rows


def agg(rows, key):
    vals = [float(r[key]) for r in rows]
    m = st.mean(vals)
    sd = st.stdev(vals) if len(vals) > 1 else 0.0
    return m, sd


def fmt(rows, key, digits=1, pct=False):
    m, sd = agg(rows, key)
    if pct:
        return f'{m * 100:.{digits}f}% ± {sd * 100:.{digits}f}'
    return f'{m:.{digits}f} ± {sd:.{digits}f}'


def total(rows, *keys):
    return sum(int(float(r[k])) for r in rows for k in keys)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--quick', action='store_true', help='时间和种子减半，用于检查流程')
    a = ap.parse_args()
    minutes = '15' if a.quick else '30'
    seeds3 = '1,2' if a.quick else '1,2,3'
    seeds8 = '1,2,3,4' if a.quick else '1,2,3,4,5,6,7,8'
    os.makedirs(OUT, exist_ok=True)
    print('编译服务端...', flush=True)
    build()
    md = io.StringIO()
    w = md.write
    w('# 效果评估实验结果\n\n')
    w(f'由 `tools/run_evaluation.py{" --quick" if a.quick else ""}` 自动生成（{time.strftime("%Y-%m-%d %H:%M")}）。'
      f'每次仿真 {minutes} 分钟（仿真时间），表中为各随机种子的平均值 ± 标准差。'
      '同一组内各配置使用相同的种子，订单序列完全相同（配对比较）。原始数据见同目录 CSV。\n\n')

    # 1. 派单策略
    print('1/5 派单策略对比', flush=True)
    w('## 1. 派单策略：贪心 vs 匈牙利算法\n\n')
    w('| 场景 | 策略 | 送达 (单) | 平均等待 (s) | 最长等待 (s) | 送达效率 (单/分) | 空驶率 | 碰撞 |\n|---|---|---|---|---|---|---|---|\n')
    key_results = {}
    for taxis, rate, label in [(8, 6, '8 车，6 单/分（轻载）'), (16, 12, '16 车，12 单/分（中载）'),
                               (16, 16, '16 车，16 单/分（接近饱和）')]:
        rows = experiment(f'dispatch-{taxis}x{rate}', ['--minutes', minutes, '--seeds', seeds3,
                                                       '--strategies', 'GREEDY,HUNGARIAN',
                                                       '--taxis', str(taxis), '--rate', str(rate)])
        for strat, name in [('GREEDY', '贪心'), ('HUNGARIAN', '匈牙利')]:
            g = [r for r in rows if r['strategy'] == strat]
            key_results[(taxis, rate, strat)] = agg(g, 'avgWait')[0]
            w(f'| {label if strat == "GREEDY" else ""} | {name} | {fmt(g, "delivered", 0)} | {fmt(g, "avgWait")} | '
              f'{fmt(g, "maxWait")} | {fmt(g, "deliveredPerMin", 2)} | {fmt(g, "emptyRatio", 0, True)} | '
              f'{total(g, "collisions", "obstacleHits", "barrierHits")} |\n')
    g16, h16 = key_results[(16, 16, 'GREEDY')], key_results[(16, 16, 'HUNGARIAN')]
    w(f'\n接近饱和时，匈牙利算法把平均等待从 {g16:.1f} s 降到 {h16:.1f} s（−{(1 - h16 / g16) * 100:.0f}%）。\n\n')

    # 2. 空车调度
    print('2/5 空车调度对比', flush=True)
    w('## 2. 空车调度（潮汐需求：早高峰居民区 → 商务区，晚高峰反向）\n\n')
    w('8 车，场景 `rush-hour.json`，派单均为匈牙利算法。\n\n')
    w('| 到达率 | 空车调度 | 送达 (单) | 平均等待 (s) | 最长等待 (s) | 空驶率 | 调度次数 | 碰撞 |\n|---|---|---|---|---|---|---|---|\n')
    for rate in (4, 5, 6):
        rows = experiment(f'rebalance-rate{rate}', ['--minutes', minutes, '--seeds', seeds8, '--strategies', 'HUNGARIAN',
                                                    '--rebalance', 'off,on', '--rate', str(rate),
                                                    '--scenario', 'rush-hour'])
        means = {}
        for reb, name in [('off', '关'), ('on', '开')]:
            g = [r for r in rows if r['rebalance'] == reb]
            means[reb] = agg(g, 'avgWait')[0]
            w(f'| {str(rate) + " 单/分" if reb == "off" else ""} | {name} | {fmt(g, "delivered", 0)} | {fmt(g, "avgWait")} | '
              f'{fmt(g, "maxWait")} | {fmt(g, "emptyRatio", 0, True)} | {fmt(g, "rebalanceMoves", 0)} | '
              f'{total(g, "collisions", "obstacleHits", "barrierHits")} |\n')
        w(f'| | 变化 | | **{(means["on"] / means["off"] - 1) * 100:+.0f}%** | | | | |\n')
    w('\n空车调度只调动"空车"，车辆接近饱和（几乎没有空车）时无从调度，效果随负载升高而减弱。\n\n')

    # 3. 感知与建图
    print('3/5 感知与建图', flush=True)
    w('## 3. 感知与建图（场景 `perception.json`：4 处未知障碍陆续出现、消失，循环播放）\n\n')
    rows = experiment('perception', ['--minutes', minutes, '--seeds', seeds8, '--strategies', 'HUNGARIAN',
                                     '--scenario', 'perception'])
    w('| 指标 | 结果 |\n|---|---|\n')
    w(f'| 感知发现障碍（次/每次仿真） | {fmt(rows, "discoveries")} |\n')
    w(f'| 障碍消失后被感知撤除（次） | {fmt(rows, "obstacleClears")} |\n')
    w(f'| 障碍出现到被发现的平均时间 (s) | {fmt(rows, "avgDiscoverySec")} |\n')
    w(f'| 最近 60 s 内被核实过的道路比例 | {fmt(rows, "coverage", 0, True)} |\n')
    w(f'| 掉头脱困（次） | {fmt(rows, "uturns")} |\n')
    w(f'| 最长连续受阻 (s) | {fmt(rows, "maxBlocked")} |\n')
    w(f'| 离障碍最小间隙 (m) | {fmt(rows, "minBarrierGap", 2)} |\n')
    w(f'| 碰撞（车车 / 车墙 / 车撞障碍），全部运行合计 | {total(rows, "collisions")} / {total(rows, "obstacleHits")} / '
      f'{total(rows, "barrierHits")} |\n')
    w('\n"发现时间"取决于有没有车恰好经过：障碍出现在车流密集的道路上时通常 1 秒内就被发现，'
      '偏僻路段要等到有车路过。\n\n')

    # 4. 安全性
    print('4/5 安全性长时间运行', flush=True)
    w('## 4. 安全性：长时间、高密度运行\n\n')
    rows = experiment('safety', ['--minutes', '60' if not a.quick else '20', '--seeds', seeds3,
                                 '--strategies', 'GREEDY,HUNGARIAN', '--taxis', '16', '--rate', '16',
                                 '--scenario', 'demo'])
    w('16 车，运行"期末演示"场景（路障、未知障碍、需求潮汐、订单潮），到达率固定为 16 单/分（覆盖场景里的设置），'
      '两种派单策略。\n\n')
    w('| 指标 | 结果 |\n|---|---|\n')
    w(f'| 运行次数 × 时长 | {len(rows)} × {60 if not a.quick else 20} 分钟 |\n')
    w(f'| 送达订单（每次） | {fmt(rows, "delivered", 0)} |\n')
    w(f'| 车车碰撞 / 车墙碰撞 / 车撞障碍（合计） | {total(rows, "collisions")} / {total(rows, "obstacleHits")} / '
      f'{total(rows, "barrierHits")} |\n')
    w(f'| 最小车距 (m) | {min(float(r["minCarGap"]) for r in rows):.3f} |\n')
    w(f'| 最小离墙 (m) | {min(float(r["minWallGap"]) for r in rows):.3f} |\n')
    w(f'| 最长连续受阻 (s) | {max(float(r["maxBlocked"]) for r in rows):.1f} |\n')
    w(f'| 仿真速度（仿真秒 / 墙钟秒） | {fmt(rows, "simSecPerWallSec", 0)} |\n')

    # 5. 图像处理加速
    print('5/5 图像处理测速', flush=True)
    path = os.path.join(OUT, 'vision-bench.csv')
    subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', CP, 'com.zongc.dw.tools.VisionBench', '--csv', path,
                    '--runs', '7' if a.quick else '15'], cwd=SERVER, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, env=dict(os.environ, LC_ALL='C.UTF-8'))
    with open(path, encoding='utf-8') as f:
        rows = list(csv.DictReader(f))
    w('\n## 5. 图像处理加速（颜色分割 + 开运算 + 连通域，同一张图单线程 vs 按行并行）\n\n')
    w(f'测试机器 {rows[0]["threads"]} 个 CPU 核，每种尺寸各跑多次取中位数。服务端实际处理时会先把照片缩到 640 像素宽。\n\n')
    w('| 图像尺寸 | 单线程 (ms) | 并行 (ms) | 加速比 |\n|---|---|---|---|\n')
    for r in rows:
        w(f'| {r["width"]}×{r["height"]} | {float(r["serialMs"]):.2f} | {float(r["parallelMs"]):.2f} | {float(r["speedup"]):.2f} |\n')
    w('\n并行只覆盖颜色分割和开运算（每一行互不依赖），连通域标记仍是串行的，所以加速比低于核数（阿姆达尔定律）；'
      '图越大，可并行部分占比越高，加速越明显。\n')

    with open(os.path.join(OUT, 'summary.md'), 'w', encoding='utf-8') as f:
        f.write(md.getvalue())
    print('完成：docs/eval/summary.md')


if __name__ == '__main__':
    sys.exit(main())
