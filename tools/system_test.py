#!/usr/bin/env python3
"""系统测试（集成 + 端到端）：把整个系统真的跑起来，自动检查关键要求。只用 Python 标准库。

做的事情（与考核现场一样，不需要人工操作）：
  1. 编译并启动服务端，运行 demo 场景脚本（路障、未知障碍、高峰、订单潮按时间自动发生）；
  2. 启动 5 辆模拟车（按协议注册为 matlab 设备，代替 Matlab 车队）和 1 部模拟手机；
  3. 每 10 s 采样一次运行指标；
  4. 运行到指定时间后杀掉模拟车队，检查 5 辆车是否在 5 s 内由数字世界内部仿真接管（FR-33）；
  5. 上传一张示例照片做图像识别，检查路障被写入地图（FR-52）；
  6. 断言：车车碰撞、撞墙、撞障碍、越权行驶全部为 0；订单在被送达；感知发现了未知障碍。

用法（仓库根目录；8080 / 9000 / 9001 端口不能被占用）：
    python3 tools/system_test.py                 # 默认运行 180 s 后断开车队，再观察 30 s
    python3 tools/system_test.py --seconds 600   # 跑 10 分钟
    python3 tools/system_test.py --udp           # 模拟车的状态改走 UDP
    python3 tools/system_test.py --fleet external --bind-timeout 300
        # 不启动 Python 模拟车，等外部车队（例如 MATLAB 的 run_fleet(..., 时长)）接入；
        # 车队运行到时自己断开，再检查接管。CI 里用它验证 Matlab 车队（.github/workflows/ci.yml）

结果打印成表格，同时写到 --out 指定的 JSON 文件（默认不写）。全部通过时退出码为 0。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = 'http://127.0.0.1:8080'
KEYS = ['created', 'delivered', 'avgWait', 'collisions', 'obstacleHits', 'barrierHits', 'freeContacts',
        'authorityViolations', 'minCarGap', 'minWallGap', 'discoveries', 'obstacleClears', 'uturns',
        'rebalanceMoves', 'maxBlocked', 'externalTaxis', 'freeAgents']


def get(path, timeout=5):
    with urllib.request.urlopen(BASE + path, timeout=timeout) as r:
        return json.load(r)


def post_bytes(path, data, ctype):
    req = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': ctype}, method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def server_up():
    try:
        get('/api/info', timeout=1)
        return True
    except OSError:
        return False


def sample():
    s = get('/api/snapshot')
    for _ in range(50):                     # 服务端刚启动、还没发布第一帧快照时返回 {}
        if 'agents' in s:
            break
        time.sleep(0.1)
        s = get('/api/snapshot')
    drivers = {}
    for a in s['agents']:
        drivers[a['driver']] = drivers.get(a['driver'], 0) + 1
    row = {'sim': s['t'], 'drivers': drivers}
    row.update({k: s['metrics'].get(k) for k in KEYS})
    return row


def main():
    ap = argparse.ArgumentParser(description='MiniDiDi 系统测试')
    ap.add_argument('--seconds', type=float, default=180, help='断开车队之前运行的秒数')
    ap.add_argument('--after', type=float, default=30, help='断开车队之后再观察的秒数')
    ap.add_argument('--scenario', default='demo')
    ap.add_argument('--udp', action='store_true', help='模拟车状态上报走 UDP')
    ap.add_argument('--out', default='', help='把采样结果写到这个 JSON 文件')
    ap.add_argument('--fleet', choices=['mock', 'external'], default='mock',
                    help='mock = 启动 5 辆 Python 模拟车；external = 等外部车队（如 MATLAB）接入，到时由它自己断开')
    ap.add_argument('--bind-timeout', type=float, default=20, help='等待 5 辆车接入的最长秒数')
    ap.add_argument('--exit-timeout', type=float, default=300, help='external 模式下等待外部车队自己断开的最长秒数')
    args = ap.parse_args()

    if server_up():
        sys.exit('8080 端口上已经有服务端在运行，请先关掉它')
    env = dict(os.environ, LC_ALL=os.environ.get('LC_ALL', 'C.UTF-8'), PYTHONIOENCODING='utf-8')
    build = os.path.join(ROOT, 'scripts', 'build.bat' if os.name == 'nt' else 'build.sh')
    subprocess.run([build] if os.name == 'nt' else ['bash', build], check=True, cwd=ROOT,
                   stdout=subprocess.DEVNULL, env=env)

    procs = []
    logs = []

    def spawn(cmd, log_name):
        log = open(os.path.join(ROOT, log_name), 'w', encoding='utf-8')
        logs.append(log)
        p = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        procs.append(p)
        return p

    cp = os.pathsep.join([os.path.join('server', 'build', 'classes'), os.path.join('server', 'lib', 'gson-2.13.1.jar')])
    py = sys.executable
    failures = []
    rows = []
    try:
        spawn(['java', '-Dstdout.encoding=UTF-8', '-cp', cp, 'com.zongc.dw.DwServer',
               '--config', os.path.join('server', 'config', 'default.json'), '--scenario', args.scenario],
              'system_test_server.log')
        for _ in range(60):
            if server_up():
                break
            time.sleep(0.5)
        else:
            sys.exit('服务端 30 s 内没有启动，请看 system_test_server.log')
        print(f'服务端已启动，场景 {args.scenario}')

        cars = None
        if args.fleet == 'mock':
            car_cmd = [py, os.path.join('tools', 'mock_devices.py'), '--cars', '5', '--phones', '0', '--kind', 'matlab']
            if args.udp:
                car_cmd.append('--udp')
            cars = spawn(car_cmd, 'system_test_cars.log')
        else:
            print(f'等待外部车队接入（最长 {args.bind_timeout:.0f} s）')
        spawn([py, os.path.join('tools', 'mock_devices.py'), '--cars', '0', '--phones', '1'], 'system_test_phone.log')

        t_bind = None
        t0 = time.time()
        while time.time() - t0 < args.bind_timeout:
            if sample()['externalTaxis'] == 5:
                t_bind = time.time() - t0
                break
            time.sleep(0.5)
        if t_bind is None:
            failures.append(f'{args.bind_timeout:.0f} s 内没有 5 辆车接入')
        else:
            print(f'5 辆车已接入（{t_bind:.1f} s），驾驶方：{sample()["drivers"]}')

        t0 = time.time()
        next_print = 0
        while time.time() - t0 < args.seconds:
            r = sample()
            r['wall'] = round(time.time() - t0)
            rows.append(r)
            if r['wall'] >= next_print:
                print(f"  {r['wall']:4d} s  仿真 {r['sim']:6.1f} s  送达 {r['delivered']:3d}/{r['created']:3d}  "
                      f"碰撞 {r['collisions']}/{r['obstacleHits']}/{r['barrierHits']}  外部车 {r['externalTaxis']}  "
                      f"发现障碍 {r['discoveries']}")
                next_print += 30
            time.sleep(10)

        # 断开车队：检查接管
        if cars is not None:
            cars.terminate()
            t_kill = time.time()
            t_takeover = None
            while time.time() - t_kill < 10:
                if sample()['externalTaxis'] == 0:
                    t_takeover = time.time() - t_kill
                    break
                time.sleep(0.2)
            if t_takeover is None or t_takeover > 5:
                failures.append(f'断开车队后没有在 5 s 内被接管（{t_takeover}）')
            else:
                print(f'断开车队：{t_takeover:.1f} s 后 5 辆车全部由内部仿真接管')
        else:
            # 外部车队到时自己断开（run_fleet 的 duration 参数），这里只等它断开
            t_wait = time.time()
            while time.time() - t_wait < args.exit_timeout and sample()['externalTaxis'] > 0:
                time.sleep(0.5)
            left = sample()['externalTaxis']
            if left > 0:
                failures.append(f'外部车队 {args.exit_timeout:.0f} s 内没有断开（还剩 {left} 辆）')
            else:
                print(f'外部车队已断开（等了 {time.time() - t_wait:.1f} s），车辆由内部仿真接管')

        # 图像识别：用服务端自带的示例照片，相机放在一条车道上朝东
        with urllib.request.urlopen(BASE + '/api/vision/sample.jpg', timeout=10) as resp:
            img = resp.read()
        v = post_bytes('/api/vision/detect?x=14.5&y=8.5&theta=0&scale=3', img, 'image/jpeg')
        if not (v.get('ok') and v.get('mapped')):
            failures.append('图像识别没有把路障写入地图：' + str(v.get('summary')))
        else:
            print('图像识别：' + v['summary'])

        time.sleep(args.after)
        final = sample()
        final['wall'] = round(time.time() - t0)
        rows.append(final)
        events = get('/api/events?n=300')
        events = events if isinstance(events, list) else events.get('events', [])
    finally:
        for p in reversed(procs):
            if p.poll() is None:
                p.terminate()
        for p in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
        for log in logs:
            log.close()

    for k in ('collisions', 'obstacleHits', 'barrierHits', 'authorityViolations'):
        if final[k] != 0:
            failures.append(f'{k} = {final[k]}（必须为 0）')
    if final['delivered'] <= 0:
        failures.append('没有送达任何订单')
    if args.scenario == 'demo' and args.seconds >= 150 and final['discoveries'] < 1:
        failures.append('demo 场景运行 150 s 以上却没有感知发现未知障碍')
    if final['drivers'].get('INTERNAL', 0) < 5:
        failures.append('断开后内部仿真车少于 5 辆')

    print()
    print('| 指标 | 结果 |')
    print('|---|---|')
    for k, label in [('sim', '仿真时间 (s)'), ('created', '生成订单'), ('delivered', '送达订单'), ('avgWait', '平均等待 (s)'),
                     ('collisions', '车车碰撞'), ('obstacleHits', '撞墙'), ('barrierHits', '撞路障 / 障碍'),
                     ('authorityViolations', '越权行驶'), ('freeContacts', '行人接触（行人从侧面走进车身）'),
                     ('minCarGap', '最小车距 (m)'), ('minWallGap', '最小离墙 (m)'), ('discoveries', '感知发现障碍'),
                     ('obstacleClears', '障碍撤除'), ('uturns', '掉头'), ('maxBlocked', '最长连续受阻 (s)')]:
        print(f'| {label} | {final[k]} |')
    keep = ('接管', '断开', '心跳', '紧急', '发现', '掉头', '图像识别')
    print('\n关键事件：')
    for e in events:
        if any(w in e['msg'] for w in keep):
            print(f"  [{e['t']:7.1f}] {e['msg']}")
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as f:
            json.dump({'samples': rows, 'final': final, 'failures': failures,
                       'events': [e['msg'] for e in events]}, f, ensure_ascii=False, indent=1)
    print()
    if failures:
        print('系统测试未通过：')
        for f in failures:
            print('  ✗ ' + f)
        sys.exit(1)
    print('系统测试全部通过')


if __name__ == '__main__':
    main()
