#!/usr/bin/env python3
"""一键录制演示视频（≤ 5 分钟）：deliverables/MiniDiDi_demo.mp4

做的事情（全程无人操作，与考核现场一致）：
  1. 编译并启动服务端，运行 demo 场景脚本（路障、未知障碍、早高峰、空车调度、订单潮按时间自动发生）；
  2. 启动 5 辆模拟车（按 Matlab 车的协议注册，代替 MATLAB）和 1 辆模拟手机车（走路即油门）；
  3. 用 Playwright 录屏：2D 主界面 → 3D 自动巡游 → 3D 跟车（tools/record_demo.js）；
  4. 用 ffmpeg 加上片头、片尾，转成 H.264 的 MP4（1280×720，25 帧/秒）。

用法（仓库根目录；8080 / 9000 / 9001 端口不能被占用）：
    python3 tools/record_demo.py                       # 默认 2D 150 s + 巡游 60 s + 跟车 45 s，加片头片尾、页面加载约 4 分 45 秒
    python3 tools/record_demo.py --two-d 120 --tour 40 --follow 30

需要：JDK、Python 3、Node.js + Playwright（npm i playwright && npx playwright install chromium）、ffmpeg；
片头片尾用 Pillow（pip install pillow）画中文，没有 Pillow 时只有纯色背景。
"""
import argparse
import glob
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'deliverables', 'MiniDiDi_demo.mp4')
W, H = 1280, 720
BG, FG, ACCENT, MUTED = (30, 39, 50), (255, 255, 255), (242, 183, 5), (200, 208, 216)

# 常见系统里的中文字体（文件，ttc 里的字体名）
FONTS = [('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 'Noto Sans CJK SC'),
         ('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 'Noto Sans CJK SC'),
         ('C:/Windows/Fonts/msyhbd.ttc', None), ('C:/Windows/Fonts/msyh.ttc', None),
         ('/System/Library/Fonts/PingFang.ttc', None),
         ('/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc', None)]


def server_up():
    try:
        urllib.request.urlopen('http://127.0.0.1:8080/api/info', timeout=1)
        return True
    except OSError:
        return False


def find_font(size):
    from PIL import ImageFont
    for path, family in FONTS:
        if not os.path.exists(path):
            continue
        for index in range(8):
            try:
                f = ImageFont.truetype(path, size, index=index)
            except OSError:
                break
            if family is None or f.getname()[0] == family:
                return f
    return ImageFont.load_default()


def card(path, lines):
    """画一张 1280×720 的标题卡。lines: [(文字, 字号, 颜色, 上边距)]"""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return False
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    y = 0
    for text, size, color, gap in lines:
        font = find_font(size)
        y += gap
        d.text((110, y), text, font=font, fill=color)
        y += size
    img.save(path)
    return True


def main():
    ap = argparse.ArgumentParser(description='录制 MiniDiDi 全自主演示视频')
    ap.add_argument('--two-d', type=float, default=150, help='2D 主界面录多少秒')
    ap.add_argument('--tour', type=float, default=60, help='3D 自动巡游录多少秒')
    ap.add_argument('--follow', type=float, default=45, help='3D 跟车录多少秒')
    ap.add_argument('--car', default='car-3', help='3D 跟随哪辆车')
    ap.add_argument('--out', default=OUT)
    args = ap.parse_args()
    if server_up():
        sys.exit('8080 端口上已经有服务端在运行，请先关掉它')
    total = args.two_d + args.tour + args.follow + 7 + 20    # 片头片尾 7 s，三次打开页面约 20 s
    if total > 300:
        print(f'提醒：视频总长约 {total:.0f} s，超过课程要求的 5 分钟')

    env = dict(os.environ, LC_ALL=os.environ.get('LC_ALL', 'C.UTF-8'), PYTHONIOENCODING='utf-8')
    build = os.path.join(ROOT, 'scripts', 'build.bat' if os.name == 'nt' else 'build.sh')
    subprocess.run([build] if os.name == 'nt' else ['bash', build], check=True, cwd=ROOT, stdout=subprocess.DEVNULL, env=env)
    cp = os.pathsep.join([os.path.join('server', 'build', 'classes'), os.path.join('server', 'lib', 'gson-2.13.1.jar')])
    procs = []
    work = tempfile.mkdtemp(prefix='minididi_rec_')
    try:
        procs.append(subprocess.Popen(['java', '-Dstdout.encoding=UTF-8', '-cp', cp, 'com.zongc.dw.DwServer',
                                       '--config', os.path.join('server', 'config', 'default.json'), '--scenario', 'demo'],
                                      cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        for _ in range(60):
            if server_up():
                break
            time.sleep(0.5)
        else:
            sys.exit('服务端没有启动')
        py = sys.executable
        procs.append(subprocess.Popen([py, os.path.join('tools', 'mock_devices.py'), '--cars', '5', '--phones', '0', '--kind', 'matlab'],
                                      cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        procs.append(subprocess.Popen([py, os.path.join('tools', 'mock_devices.py'), '--cars', '0', '--phones', '1'],
                                      cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        time.sleep(1.0)
        print(f'开始录制：2D {args.two_d:.0f} s → 3D 巡游 {args.tour:.0f} s → 3D 跟随 {args.car} {args.follow:.0f} s')
        r = subprocess.run(['node', os.path.join('tools', 'record_demo.js'), work, str(args.two_d), str(args.tour),
                            str(args.follow), args.car], cwd=ROOT, capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit('录制失败：' + r.stderr)
        webm = r.stdout.strip().splitlines()[-1]
        if r.stderr.strip():
            print(r.stderr.strip())
    finally:
        for p in reversed(procs):
            p.terminate()
        for p in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()

    title, ending = os.path.join(work, 'title.png'), os.path.join(work, 'end.png')
    has_cards = card(title, [('缩微滴滴 MiniDiDi', 54, FG, 200), ('多智能体无人出租车系统 · 全自主演示', 30, ACCENT, 26),
                             ('服务端运行“期末演示”场景脚本；5 辆模拟车按 Matlab 车协议接入，1 辆手机车由“拿着手机走路的人”驾驶', 20, MUTED, 60),
                             ('录制全程无人操作：路障、未知障碍、早高峰、空车调度、订单潮均由脚本自动触发', 20, MUTED, 14),
                             ('画面顺序：2D 主界面 → 3D 自动巡游 → 3D 跟车视角', 20, MUTED, 14)])
    card(ending, [('谢谢观看', 54, FG, 250), ('综合设计与实践 C · MiniDiDi 小组', 26, ACCENT, 30)])
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    if has_cards:
        cmd = ['ffmpeg', '-y', '-v', 'error',
               '-loop', '1', '-t', '4', '-i', title, '-i', webm, '-loop', '1', '-t', '3', '-i', ending,
               '-filter_complex',
               f'[0:v]scale={W}:{H},setsar=1,fps=25,format=yuv420p[a];[1:v]scale={W}:{H},setsar=1,fps=25,format=yuv420p[b];'
               f'[2:v]scale={W}:{H},setsar=1,fps=25,format=yuv420p[c];[a][b][c]concat=n=3:v=1:a=0[v]',
               '-map', '[v]']
    else:
        cmd = ['ffmpeg', '-y', '-v', 'error', '-i', webm, '-vf', f'scale={W}:{H},fps=25,format=yuv420p']
    cmd += ['-c:v', 'libx264', '-preset', 'slow', '-crf', '28', '-movflags', '+faststart', os.path.abspath(args.out)]
    subprocess.run(cmd, check=True)
    for f in glob.glob(os.path.join(work, '*')):
        os.remove(f)
    os.rmdir(work)
    size = os.path.getsize(args.out) / 1e6
    print(f'已生成 {os.path.relpath(args.out, ROOT)}（{size:.1f} MB）')


if __name__ == '__main__':
    main()
