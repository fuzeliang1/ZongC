#!/usr/bin/env python3
"""模拟设备（协议参考实现）——只用 Python 标准库。

用途：
  1. 在没有 Matlab / 手机的情况下，模拟 N 辆"外部模拟车"接入数字世界，测试设备接入全流程；
  2. 作为 docs/protocol.md 的可运行参考实现，Matlab（matlab/SimCar.m）和 Gazebo 桥接
     都按同样的逻辑实现：收 path / permit / world，按纯追踪 + 速度 PI 控制行驶，10 Hz 上报 state；
     手机车（PhoneCar）与安卓 App 的 RouteFollower 同一套逻辑：沿路线前进，走路速度就是油门。

用法：
  python tools/mock_devices.py                       # 5 辆车（car-1 … car-5），连接 127.0.0.1:9000
  python tools/mock_devices.py --cars 3 --start 4    # 绑定 car-4 … car-6
  python tools/mock_devices.py --phones 1            # 另外模拟 1 部手机车（人拿着手机走走停停，绑定 car-6）
  python tools/mock_devices.py --cars 0 --phones 1   # 只模拟手机车
  python tools/mock_devices.py --udp                 # 状态改走 UDP 9001
  python tools/mock_devices.py --duration 60         # 运行 60 秒后退出并打印统计
"""
import argparse
import json
import math
import queue
import random
import socket
import sys
import threading
import time


# ============================================================ 网络连接

class Link:
    """一条 TCP 连接：每行一条 JSON。读线程把收到的消息放进队列，主循环 poll() 取走。"""

    def __init__(self, host, port, name):
        self.host, self.port, self.name = host, port, name
        self.sock = None
        self.inbox = queue.Queue()
        self.alive = False
        self.lock = threading.Lock()

    def connect(self, timeout=3.0):
        s = socket.create_connection((self.host, self.port), timeout=timeout)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.settimeout(None)
        self.sock = s
        self.alive = True
        threading.Thread(target=self._reader, name=self.name + '-r', daemon=True).start()

    def _reader(self):
        buf = b''
        try:
            while self.alive:
                data = self.sock.recv(65536)
                if not data:
                    break
                buf += data
                while b'\n' in buf:
                    line, buf = buf.split(b'\n', 1)
                    line = line.strip()
                    if line:
                        try:
                            self.inbox.put(json.loads(line.decode('utf-8')))
                        except ValueError as e:
                            print(f'[{self.name}] 无法解析: {e}', file=sys.stderr)
        except OSError:
            pass
        self.alive = False

    def send(self, obj):
        if not self.alive:
            return False
        try:
            with self.lock:
                self.sock.sendall((json.dumps(obj, separators=(',', ':')) + '\n').encode('utf-8'))
            return True
        except OSError:
            self.alive = False
            return False

    def poll(self):
        out = []
        while True:
            try:
                out.append(self.inbox.get_nowait())
            except queue.Empty:
                return out

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except (OSError, AttributeError):
            pass


# ============================================================ 模拟车控制器

def wrap(a):
    while a > math.pi:
        a -= 2 * math.pi
    while a <= -math.pi:
        a += 2 * math.pi
    return a


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class SimCar:
    """独轮车（差速）运动学模型 + 纯追踪（Pure Pursuit）转向 + 速度 PI 控制。

    关键约束：沿路线的弧长 s 不能超过服务端授权的停车位置 stopS（permit 消息）。
    """

    def __init__(self, agent_id, noise=0.0):
        self.agent_id = agent_id
        self.noise = noise
        # 位姿与速度
        self.x = self.y = self.th = 0.0
        self.v = self.w = 0.0
        self.ready = False
        # 参数（welcome 消息会覆盖）
        self.r, self.vmax, self.a_acc, self.a_dec, self.v_turn = 0.3, 1.5, 1.0, 1.5, 0.6
        self.a_brake, self.w_max, self.a_lat, self.kp = 3.0, 3.0, 0.8, 3.0
        # 路线与授权
        self.wp = []
        self.cum = [0.0]
        self.route_id = -1
        self.stop_s = 0.0
        self.final = False
        self.seg = 0
        self.global_search = True
        self.others = []
        # 统计
        self.routes = self.permits = 0
        self.dist = 0.0

    # ---------------- 消息处理
    def on_message(self, m):
        t = m.get('type')
        if t == 'welcome':
            self.agent_id = m['agentId']
            p = m['pose']
            self.x, self.y, self.th, self.v = p['x'], p['y'], p['theta'], p.get('v', 0.0)
            prm = m.get('params', {})
            self.r = prm.get('bodyRadius', self.r)
            self.vmax = prm.get('vMax', self.vmax)
            self.a_acc = prm.get('aAcc', self.a_acc)
            self.a_dec = prm.get('aDec', self.a_dec)
            self.v_turn = prm.get('vTurn', self.v_turn)
            self.ready = True
        elif t == 'path':
            self.wp = [tuple(p) for p in m['waypoints']]
            self.cum = [0.0]
            for i in range(1, len(self.wp)):
                self.cum.append(self.cum[-1] + math.dist(self.wp[i - 1], self.wp[i]))
            self.route_id = m['routeId']
            self.stop_s = 0.0          # 新路线在收到 permit 之前不能走
            self.final = False
            self.seg = 0
            self.global_search = True  # 新路线第一次投影做全局搜索，之后只在附近搜索
            self.routes += 1
        elif t == 'permit':
            if m['routeId'] == self.route_id:
                self.stop_s = m['stopS']
                self.final = m['final']
                self.permits += 1
        elif t == 'world':
            self.others = [a for a in m['agents'] if a['id'] != self.agent_id]
        elif t == 'error':
            print(f'[{self.agent_id}] 服务端错误: {m.get("message")}', file=sys.stderr)

    # ---------------- 几何
    def project(self):
        """把车辆位置投影到路线折线上，返回弧长 s（只在当前段附近搜索，保证单调前进）。"""
        n = len(self.wp)
        if n == 1:
            return -math.dist((self.x, self.y), self.wp[0])
        best_s, best_d, best_i = 0.0, float('inf'), self.seg
        if self.global_search:
            lo_i, hi_i = 0, n - 1
            self.global_search = False
        else:
            lo_i, hi_i = max(0, self.seg - 2), min(n - 1, self.seg + 8)
        for i in range(lo_i, hi_i):
            (ax, ay), (bx, by) = self.wp[i], self.wp[i + 1]
            L = self.cum[i + 1] - self.cum[i]
            t = ((self.x - ax) * (bx - ax) + (self.y - ay) * (by - ay)) / (L * L)
            lo = -10.0 if i == 0 else 0.0               # 允许落在第一个路点之前
            hi = 10.0 if i == n - 2 else 1.0            # 允许落在最后一个路点之后
            t = clamp(t, lo, hi)
            px, py = ax + (bx - ax) * t, ay + (by - ay) * t
            d = math.hypot(self.x - px, self.y - py)
            if d < best_d - 1e-9:
                best_d, best_s, best_i = d, self.cum[i] + t * L, i
        self.seg = best_i
        return best_s

    def point_at(self, s):
        n = len(self.wp)
        if n == 1 or s >= self.cum[-1]:
            return self.wp[-1]
        if s <= 0:
            return self.wp[0]
        i = max(0, min(n - 2, self.seg))
        while i < n - 2 and self.cum[i + 1] < s:
            i += 1
        while i > 0 and self.cum[i] > s:
            i -= 1
        (ax, ay), (bx, by) = self.wp[i], self.wp[i + 1]
        t = (s - self.cum[i]) / (self.cum[i + 1] - self.cum[i])
        return ax + (bx - ax) * t, ay + (by - ay) * t

    # ---------------- 控制 + 运动学
    def step(self, dt):
        if not self.ready:
            return
        v_ref, kappa, alpha = 0.0, 0.0, 0.0
        s_now = 0.0
        if self.wp:
            s_now = self.project()
            # 纯追踪：在前视距离处取目标点
            ld = clamp(0.3 + 0.3 * self.v, 0.35, 0.6)
            tx, ty = self.point_at(s_now + ld)
            dx, dy = tx - self.x, ty - self.y
            dist = math.hypot(dx, dy)
            if dist > 0.05:
                alpha = wrap(math.atan2(dy, dx) - self.th)
                kappa = 2 * math.sin(alpha) / dist
            # 速度参考：授权内刹得住 + 转弯限速
            d_stop = self.stop_s - s_now
            v_ref = min(self.vmax, math.sqrt(2 * self.a_dec * max(0.0, d_stop)))
            if abs(kappa) > 1e-3:
                v_ref = min(v_ref, max(self.v_turn, math.sqrt(self.a_lat / abs(kappa))))
            if d_stop < 0.01:
                v_ref = 0.0
            if abs(alpha) > 1.0:                        # 方向偏差太大：先原地转向
                v_ref = min(v_ref, 0.05)
            # 本地安全兜底：正前方很近处有其他车则急停（正常情况下服务端授权已保证安全）
            c, s = math.cos(self.th), math.sin(self.th)
            for o in self.others:
                rx, ry = o['x'] - self.x, o['y'] - self.y
                fwd, lat = rx * c + ry * s, -rx * s + ry * c
                if 0 < fwd < 2 * self.r + 0.12 + 0.3 * self.v and abs(lat) < (self.r + o.get('r', self.r)) * 0.9:
                    v_ref = 0.0
                    break

        # 速度 PI（这里只用 P，积分项留给同学们扩展）+ 加速度限幅
        a = clamp(self.kp * (v_ref - self.v), -self.a_brake, self.a_acc)
        v_new = max(0.0, self.v + a * dt)
        if self.wp and s_now + v_new * dt > self.stop_s:  # 硬约束：绝不越过授权停车点
            v_new = max(0.0, (self.stop_s - s_now) / dt)
        self.v = v_new
        if abs(alpha) > 1.0 and self.v < 0.1:
            self.w = math.copysign(self.w_max * 0.6, alpha)
        else:
            self.w = clamp(self.v * kappa, -self.w_max, self.w_max)

        # 独轮车运动学积分
        nx = self.x + self.v * math.cos(self.th) * dt
        ny = self.y + self.v * math.sin(self.th) * dt
        if self.noise > 0:
            nx += random.gauss(0, self.noise) * dt
            ny += random.gauss(0, self.noise) * dt
        self.dist += math.hypot(nx - self.x, ny - self.y)
        self.x, self.y = nx, ny
        self.th = wrap(self.th + self.w * dt)
        self.last_s, self.last_alpha = s_now, alpha

    def state_msg(self, seq):
        return {'type': 'state', 'agentId': self.agent_id, 'seq': seq, 't': int(time.time() * 1000),
                'x': round(self.x, 4), 'y': round(self.y, 4), 'theta': round(self.th, 4),
                'v': round(self.v, 4), 'w': round(self.w, 4)}


class PhoneCar:
    """手机车（kind = phone）：人拿着手机走路来驾驶数字世界里的一辆车，与安卓 App 的 RouteFollower 同一套逻辑。

    车的位置由服务端规划的路线决定（人不可能沿地图上的街道走），车的快慢由人走路决定：
    期望车速 = 走路速度 × 驾驶比例，再受最高车速、转弯限速、"在授权停车点 stopS 前刹得住"的刹车曲线
    和加减速度限制；沿路线折线推进弧长 s（任何时候 s ≤ stopS），上报 s 处的位姿。
    这里用"走一会儿（15~40 s，1.0~1.4 m/s）、停一会儿（2~6 s）"模拟拿着手机的人。
    """

    BEFORE_START = 1.0   # 新路线投影时，允许车位于第一个路点之前的最大距离（m）
    TURN_EPS = 0.05      # 相邻两段航向差超过它（rad）就算转弯段

    def __init__(self, agent_id, scale=1.0):
        self.agent_id = agent_id
        self.scale = scale
        self.x = self.y = self.th = 0.0
        self.v = self.w = 0.0
        self.ready = False
        self.vmax, self.a_acc, self.a_dec, self.v_turn = 1.5, 1.0, 1.5, 0.6
        self.wp, self.cum, self.head, self.turn = [], [0.0], [], []
        self.route_id = -1
        self.s = self.stop_s = 0.0
        # 拿着手机的人
        self.walking = False
        self.walk_v = 0.0
        self.phase_left = random.uniform(1, 4)
        # 统计
        self.routes = self.permits = 0
        self.dist = 0.0

    # ---------------- 消息处理
    def on_message(self, m):
        t = m.get('type')
        if t == 'welcome':
            self.agent_id = m['agentId']
            p = m.get('pose', {})
            self.x, self.y, self.th = p.get('x', self.x), p.get('y', self.y), p.get('theta', self.th)
            self.v = self.w = 0.0
            prm = m.get('params', {})
            self.vmax = prm.get('vMax', self.vmax)
            self.a_acc = prm.get('aAcc', self.a_acc)
            self.a_dec = prm.get('aDec', self.a_dec)
            self.v_turn = min(self.vmax, prm.get('vTurn', self.v_turn))
            self.wp = []
            self.ready = True
        elif t == 'path' and m.get('waypoints'):
            self.wp = [tuple(p) for p in m['waypoints']]
            self.cum = [0.0]
            for i in range(1, len(self.wp)):
                self.cum.append(self.cum[-1] + math.dist(self.wp[i - 1], self.wp[i]))
            self._headings()
            self.route_id = m['routeId']
            self.s = self._project()
            self.stop_s = 0.0          # 新路线在收到它的 permit 之前不能走
            self._place()
            self.routes += 1
        elif t == 'permit':
            if self.wp and m['routeId'] == self.route_id:
                self.stop_s = m['stopS']   # 直接采用最新一条（授权可能被收回）
                self.permits += 1
        elif t == 'error':
            print(f'[{self.agent_id}] 服务端错误: {m.get("message")}', file=sys.stderr)

    # ---------------- 几何
    def _headings(self):
        n = max(1, len(self.wp) - 1)
        self.head, self.turn = [0.0] * n, [False] * n
        nxt = None
        for i in range(n - 1, -1, -1):
            if i + 1 < len(self.wp) and self.cum[i + 1] - self.cum[i] > 1e-9:
                (ax, ay), (bx, by) = self.wp[i], self.wp[i + 1]
                nxt = math.atan2(by - ay, bx - ax)
            self.head[i] = nxt
        prev = None
        for i in range(n):
            if self.head[i] is None:              # 零长度段（原地转向）：沿用前一段的航向
                self.head[i] = self.th if prev is None else prev
            self.turn[i] = i > 0 and abs(wrap(self.head[i] - self.head[i - 1])) > self.TURN_EPS
            prev = self.head[i]

    def _seg(self, s):
        """s 所在的段号：cum[k] ≤ s < cum[k+1]。"""
        k = 0
        while k < len(self.head) - 1 and self.cum[k + 1] <= s:
            k += 1
        return k

    def _point(self, s):
        n = len(self.wp)
        if n == 1 or s >= self.cum[-1]:
            return self.wp[-1]
        if s < 0:                                 # 车在路线起点后面一点：沿第一段反向延长
            h = self.head[0]
            return self.wp[0][0] + s * math.cos(h), self.wp[0][1] + s * math.sin(h)
        k = self._seg(s)
        (ax, ay), (bx, by) = self.wp[k], self.wp[k + 1]
        seg = self.cum[k + 1] - self.cum[k]
        t = (s - self.cum[k]) / seg if seg > 1e-12 else 0.0
        return ax + (bx - ax) * t, ay + (by - ay) * t

    def _place(self):
        self.x, self.y = self._point(self.s)
        k = len(self.head) - 1 if self.s >= self.cum[-1] else self._seg(self.s)
        self.th = self.head[k]

    def _project(self):
        """把当前位置投影到新路线上；距离差不多（5 cm 内）时取最靠前的（路线可能经过同一处两次）。"""
        n = len(self.wp)
        if n == 1:
            return 0.0
        cands = []
        for i in range(n - 1):
            (ax, ay), (bx, by) = self.wp[i], self.wp[i + 1]
            L = self.cum[i + 1] - self.cum[i]
            if L < 1e-12:
                t = 0.0
            else:
                t = ((self.x - ax) * (bx - ax) + (self.y - ay) * (by - ay)) / (L * L)
                t = clamp(t, -self.BEFORE_START / L if i == 0 else 0.0, 1.0)
            px, py = ax + (bx - ax) * t, ay + (by - ay) * t
            cands.append((math.hypot(self.x - px, self.y - py), self.cum[i] + t * L))
        best = min(d for d, _ in cands)
        return next(s for d, s in cands if d <= best + 0.05)

    def _turn_limit(self):
        """前方第一个转弯段要在到达前减到 vTurn，转弯段里不超过 vTurn。"""
        look = self.vmax ** 2 / (2 * self.a_dec) + 0.5
        k = self._seg(self.s)
        while k < len(self.turn) and self.cum[k] < self.s + look:
            if self.turn[k]:
                return math.sqrt(self.v_turn ** 2 + 2 * self.a_dec * max(0.0, self.cum[k] - self.s))
            k += 1
        return self.vmax

    # ---------------- 人走路 + 车沿路线推进
    def step(self, dt):
        self.phase_left -= dt
        if self.phase_left <= 0:
            self.walking = not self.walking
            self.phase_left = random.uniform(15, 40) if self.walking else random.uniform(2, 6)
            self.walk_v = random.uniform(1.0, 1.4) if self.walking else 0.0
        if not self.ready:
            return
        old_th = self.th
        if not self.wp:
            self.v = 0.0
        else:
            room = min(self.stop_s, self.cum[-1]) - self.s
            cap = 0.0 if room <= 1e-6 else min(self.vmax, math.sqrt(2 * self.a_dec * room), self._turn_limit())
            target = min(self.walk_v * self.scale, cap)
            if target > self.v:
                nv = min(target, self.v + self.a_acc * dt)
            else:
                nv = max(target, self.v - self.a_dec * dt)
            ds = min(min(nv, cap) * dt, max(0.0, room))
            self.s += ds
            self.v = ds / dt if dt > 0 else 0.0
            self.dist += ds
            self._place()
        self.w = wrap(self.th - old_th) / dt if dt > 0 else 0.0

    def state_msg(self, seq):
        return {'type': 'state', 'agentId': self.agent_id, 'seq': seq, 't': int(time.time() * 1000),
                'x': round(self.x, 4), 'y': round(self.y, 4), 'theta': round(self.th, 4),
                'v': round(self.v, 4), 'w': round(self.w, 4)}


# ============================================================ 主程序

def main():
    ap = argparse.ArgumentParser(description='MiniDiDi 模拟设备')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=9000, help='服务端 TCP 端口')
    ap.add_argument('--udp-port', type=int, default=9001)
    ap.add_argument('--cars', type=int, default=5, help='模拟车数量')
    ap.add_argument('--start', type=int, default=1, help='绑定的第一辆数字车编号')
    ap.add_argument('--kind', default='generic', help='注册时的设备类型：generic / matlab / gazebo')
    ap.add_argument('--phones', type=int, default=0, help='模拟手机车数量（人拿着手机走路驾驶）')
    ap.add_argument('--phone-start', type=int, default=6, help='手机车绑定的第一辆数字车编号')
    ap.add_argument('--drive-scale', type=float, default=1.0, help='手机车的驾驶比例：人走 1 m，车开几 m')
    ap.add_argument('--hz', type=float, default=20.0, help='控制频率')
    ap.add_argument('--state-hz', type=float, default=10.0, help='状态上报频率')
    ap.add_argument('--udp', action='store_true', help='状态上报走 UDP')
    ap.add_argument('--noise', type=float, default=0.0, help='位置噪声标准差（m/s）')
    ap.add_argument('--duration', type=float, default=0, help='运行秒数，0 表示一直运行')
    ap.add_argument('--trace', default='', help='把每个控制周期的状态写入 CSV 文件（调试用）')
    args = ap.parse_args()

    devices = []   # (link, model, register_msg)
    for i in range(args.cars):
        aid = f'car-{args.start + i}'
        reg = {'type': 'register', 'kind': args.kind, 'agentId': aid, 'name': f'mock-{aid}'}
        devices.append([Link(args.host, args.port, aid), SimCar(aid, args.noise), reg])
    for i in range(args.phones):
        aid = f'car-{args.phone_start + i}'
        reg = {'type': 'register', 'kind': 'phone', 'agentId': aid, 'name': f'mock-phone-{i + 1}'}
        devices.append([Link(args.host, args.port, aid), PhoneCar(aid, args.drive_scale), reg])

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM) if args.udp else None

    def ensure_connected(dev):
        link, model, reg = dev
        if link.alive:
            return True
        try:
            link.connect()
            link.send(reg)
            print(f'[{reg["agentId"]}] 已连接 {args.host}:{args.port}')
            return True
        except OSError as e:
            print(f'[{reg["agentId"]}] 连接失败（{e}），2 秒后重试', file=sys.stderr)
            return False

    trace = open(args.trace, 'w', encoding='utf-8') if args.trace else None
    if trace:
        trace.write('wall,id,x,y,th,v,w,s,stop_s,route_id,n_wp,seg,alpha\n')
    dt = 1.0 / args.hz
    state_every = max(1, round(args.hz / args.state_hz))
    t_start = time.monotonic()
    next_tick = t_start
    tick = 0
    retry_at = 0.0
    try:
        while True:
            now = time.monotonic()
            if args.duration and now - t_start >= args.duration:
                break
            if now >= retry_at:
                if not all(ensure_connected(d) for d in devices):
                    retry_at = now + 2.0
            for link, model, reg in devices:
                for m in link.poll():
                    model.on_message(m)
                model.step(dt)
                if trace and isinstance(model, SimCar) and model.ready:
                    trace.write(f'{time.time():.3f},{model.agent_id},{model.x:.4f},{model.y:.4f},{model.th:.4f},{model.v:.4f},'
                                f'{model.w:.4f},{getattr(model, "last_s", 0):.4f},{model.stop_s:.4f},{model.route_id},'
                                f'{len(model.wp)},{model.seg},{getattr(model, "last_alpha", 0):.4f}\n')
                if tick % state_every == 0 and link.alive and getattr(model, 'ready', True):
                    msg = model.state_msg(tick)
                    if udp is not None:
                        udp.sendto(json.dumps(msg).encode('utf-8'), (args.host, args.udp_port))
                    else:
                        link.send(msg)
            tick += 1
            next_tick += dt
            sleep = next_tick - time.monotonic()
            if sleep > 0:
                time.sleep(sleep)
            else:
                next_tick = time.monotonic()
    except KeyboardInterrupt:
        pass
    finally:
        print('\n设备        路线数  授权数  行驶距离(m)')
        for link, model, reg in devices:
            print(f'{model.agent_id:<10} {model.routes:6d}  {model.permits:6d}  {model.dist:10.1f}')
            link.close()


if __name__ == '__main__':
    main()
