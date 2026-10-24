#!/usr/bin/env python3
"""把 docs/uml/*.puml（PlantUML 源文件）渲染成 docs/images/uml/*.png。

用法（仓库根目录）：
    python3 tools/render_uml.py                 # 渲染全部
    python3 tools/render_uml.py class-world     # 只渲染指定的图（文件名，不带扩展名）
    python3 tools/render_uml.py --svg           # 同时输出 SVG（矢量图，放大不糊）

需要：JDK（服务端本来就要装）。PlantUML 的 jar 按以下顺序查找：
  环境变量 PLANTUML_JAR → tools/plantuml.jar → 自动从 Maven Central 下载到 ~/.cache/minididi/。
用例图、类图、组件图、部署图、状态图需要 Graphviz（命令 dot）来排版；没装时自动改用
PlantUML 内置的 smetana 排版引擎（效果略差，但不用装任何东西）。
时序图、活动图、甘特图不需要 Graphviz。只用 Python 标准库。
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'docs', 'uml')
OUT = os.path.join(ROOT, 'docs', 'images', 'uml')
VERSION = '1.2026.9'
URL = (f'https://repo.maven.apache.org/maven2/net/sourceforge/plantuml/plantuml/{VERSION}/'
       f'plantuml-{VERSION}.jar')


def find_jar():
    env = os.environ.get('PLANTUML_JAR')
    if env and os.path.isfile(env):
        return env
    local = os.path.join(ROOT, 'tools', 'plantuml.jar')
    if os.path.isfile(local):
        return local
    cache = os.path.join(os.path.expanduser('~'), '.cache', 'minididi', f'plantuml-{VERSION}.jar')
    if not os.path.isfile(cache):
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        print(f'下载 PlantUML {VERSION}（约 30 MB）……')
        tmp = cache + '.part'
        urllib.request.urlretrieve(URL, tmp)
        os.replace(tmp, cache)
    return cache


def main():
    ap = argparse.ArgumentParser(description='渲染 docs/uml 下的 PlantUML 图')
    ap.add_argument('names', nargs='*', help='只渲染这些图（不带 .puml）')
    ap.add_argument('--svg', action='store_true', help='同时输出 SVG')
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(SRC, '*.puml')))
    if args.names:
        files = [f for f in files if os.path.splitext(os.path.basename(f))[0] in args.names]
        if not files:
            sys.exit('没有找到指定的图：' + ', '.join(args.names))
    jar = find_jar()
    base = ['java', '-Djava.awt.headless=true', '-jar', jar, '-charset', 'UTF-8', '-failfast2']
    if shutil.which('dot') is None:
        print('提示：没有找到 Graphviz（dot），改用 smetana 排版引擎')
        base.append('-Playout=smetana')
    # PlantUML 的 -o 是相对于源文件所在目录的路径
    rel_out = os.path.relpath(OUT, SRC)
    os.makedirs(OUT, exist_ok=True)
    formats = ['-tpng'] + (['-tsvg'] if args.svg else [])
    for fmt in formats:
        r = subprocess.run(base + [fmt, '-o', rel_out] + files, cwd=SRC)
        if r.returncode != 0:
            sys.exit('渲染失败，请看上面的错误信息（行号指 .puml 文件里的行）')
    print(f'已渲染 {len(files)} 张图 → {os.path.relpath(OUT, ROOT)}')


if __name__ == '__main__':
    main()
