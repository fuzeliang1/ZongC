#!/usr/bin/env python3
"""生成"缩微城市"栅格地图（JSON），供数字世界 DW、Matlab 模拟车和前端共用。

地图规则（详见 docs/protocol.md 第 5 节）：
  - 每格边长 resolution 米，x 向东、y 向北；JSON 的 rows 从最北一行写到最南一行。
  - 道路宽 2 格（双向两车道），靠右行驶：
        东西向道路：南侧一行向东 '>'，北侧一行向西 '<'
        南北向道路：东侧一列向北 '^'，西侧一列向南 'v'
  - 道路交叉处为路口 '+'，任意方向可通行；其余为建筑 '#'（静态障碍）。

用法：
  python tools/gen_map.py                         # 生成默认地图
  python tools/gen_map.py --widths 8,10,6 --heights 6,5 -o my.json
"""
import argparse
import json
from pathlib import Path


def build_rows(widths, heights):
    """根据各街区的宽、高生成字符栅格，返回 (rows_top_first, width, height)。"""
    road_cols = []   # 每条南北向道路的西侧列号
    x = 0
    for w in widths:
        road_cols.append(x)
        x += 2 + w
    road_cols.append(x)
    width = x + 2

    road_rows = []   # 每条东西向道路的南侧行号（y 向上）
    y = 0
    for h in heights:
        road_rows.append(y)
        y += 2 + h
    road_rows.append(y)
    height = y + 2

    west_cols = set(road_cols)
    east_cols = {c + 1 for c in road_cols}
    south_rows = set(road_rows)
    north_rows = {r + 1 for r in road_rows}

    grid = [['#'] * width for _ in range(height)]   # grid[y][x]，y 向上
    for yy in range(height):
        for xx in range(width):
            on_v = xx in west_cols or xx in east_cols
            on_h = yy in south_rows or yy in north_rows
            if on_v and on_h:
                ch = '+'
            elif on_h:
                ch = '>' if yy in south_rows else '<'
            elif on_v:
                ch = 'v' if xx in west_cols else '^'
            else:
                ch = '#'
            grid[yy][xx] = ch
    rows = [''.join(grid[yy]) for yy in range(height - 1, -1, -1)]   # 北 → 南
    return rows, width, height


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--widths', default='8,10,6,8,9', help='自西向东各街区宽度（格），逗号分隔')
    ap.add_argument('--heights', default='6,5,7,6', help='自南向北各街区高度（格），逗号分隔')
    ap.add_argument('--resolution', type=float, default=1.0, help='每格边长（米）')
    ap.add_argument('--name', default='MiniCity 5x4')
    ap.add_argument('-o', '--output', default=str(Path(__file__).resolve().parent.parent / 'server' / 'maps' / 'city-5x4.json'))
    args = ap.parse_args()

    widths = [int(v) for v in args.widths.split(',')]
    heights = [int(v) for v in args.heights.split(',')]
    rows, w, h = build_rows(widths, heights)
    data = {
        'name': args.name,
        'resolution': args.resolution,
        'legend': {
            '#': '建筑（静态障碍）',
            '+': '路口（任意方向）',
            '.': '开放区域（任意方向）',
            '>': '向东车道', '<': '向西车道', '^': '向北车道', 'v': '向南车道',
        },
        'rows': rows,
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    # 每行单独一行，方便直接在文本编辑器里修改地图
    text = json.dumps({k: v for k, v in data.items() if k != 'rows'}, ensure_ascii=False, indent=2)
    text = text[:-2] + ',\n  "rows": [\n' + ',\n'.join('    ' + json.dumps(r) for r in rows) + '\n  ]\n}\n'
    out.write_text(text, encoding='utf-8')
    print(f'已生成 {out}  ({w} x {h} 格)')


if __name__ == '__main__':
    main()
