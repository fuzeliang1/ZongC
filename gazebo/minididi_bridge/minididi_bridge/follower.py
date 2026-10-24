"""路径跟踪控制器：纯追踪（Pure Pursuit）转向 + 速度控制，严格遵守服务端的行驶授权。

逻辑与 tools/mock_devices.py 的 SimCar、matlab/SimCar.m 完全相同，只是把"控制"和"车辆运动"拆开了：
这里只根据车辆位姿算出速度指令 (v, ω)；车怎么动由后端决定（Gazebo 里的差速小车，或纯 Python 运动学模型）。

关键约束（协议 2.5 节）：车辆沿路线的投影长度 s 任何时刻都不能超过授权停车位置 stopS。
  - 速度上限 v ≤ √(2·a·(stopS − s))，保证总能在停车点前刹住；
  - 收到新 path 之后、收到同一 routeId 的 permit 之前不得前进（stopS 视为 0）；
  - stopS 变小时（行人闯入、出现障碍）直接采用新值，不能取历史最大值。
"""
import math
import sys


def wrap(a):
    while a > math.pi:
        a -= 2 * math.pi
    while a <= -math.pi:
        a += 2 * math.pi
    return a


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class PathFollower:
    def __init__(self, agent_id):
        self.agent_id = agent_id
        self.ready = False
        self.welcome_pose = None
        # 车辆参数（welcome 消息会覆盖）
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
        # 执行滞后（秒）：指令发出到车真正减速的时间。物理仿真 / 实体车要按 v × lead 提前刹车，运动学模型为 0
        self.lead = 0.0
        # 控制器内部状态：指令速度（加速度限幅以它为基准，不受里程计噪声影响）
        self.v = 0.0
        self.w = 0.0
        self.last_s = 0.0
        # 统计
        self.routes = self.permits = 0
        self.max_overrun = 0.0     # 实际投影超过 stopS 的最大值（米），用来检查车辆执行得好不好
        self.max_lateral = 0.0     # 离路线的最大横向偏差（米）
        self.last_d = 0.0

    # ------------------------------------------------------------ 消息
    def on_message(self, m):
        t = m.get('type')
        if t == 'welcome':
            self.agent_id = m['agentId']
            p = m['pose']
            self.welcome_pose = (p['x'], p['y'], p['theta'])
            self.v = 0.0
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

    # ------------------------------------------------------------ 几何
    def project(self, x, y):
        """把车辆位置投影到路线折线上，返回弧长 s（只在当前段附近搜索，保证单调前进）。"""
        n = len(self.wp)
        if n == 1:
            return -math.dist((x, y), self.wp[0])
        best_s, best_d, best_i = 0.0, float('inf'), self.seg
        if self.global_search:
            lo_i, hi_i = 0, n - 1
            self.global_search = False
        else:
            lo_i, hi_i = max(0, self.seg - 2), min(n - 1, self.seg + 8)
        for i in range(lo_i, hi_i):
            (ax, ay), (bx, by) = self.wp[i], self.wp[i + 1]
            seg_len = self.cum[i + 1] - self.cum[i]
            t = ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (seg_len * seg_len)
            lo = -10.0 if i == 0 else 0.0               # 允许落在第一个路点之前
            hi = 10.0 if i == n - 2 else 1.0            # 允许落在最后一个路点之后
            t = clamp(t, lo, hi)
            px, py = ax + (bx - ax) * t, ay + (by - ay) * t
            d = math.hypot(x - px, y - py)
            if d < best_d - 1e-9:
                best_d, best_s, best_i = d, self.cum[i] + t * seg_len, i
        self.seg = best_i
        self.last_d = best_d
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

    # ------------------------------------------------------------ 控制
    def control(self, x, y, th, dt):
        """根据当前位姿算出速度指令 (v, ω)。没有路线或还没注册成功时返回 (0, 0)。"""
        if not self.ready or not self.wp:
            self.v = self.w = 0.0
            return 0.0, 0.0
        s_now = self.project(x, y)
        self.last_s = s_now
        self.max_overrun = max(self.max_overrun, s_now - self.stop_s)
        if self.stop_s > 0.05:                      # 停在起点等授权时（如刚传送过来）不计
            self.max_lateral = max(self.max_lateral, self.last_d)
        # 纯追踪：在前视距离处取目标点
        ld = clamp(0.3 + 0.3 * self.v, 0.35, 0.6)
        tx, ty = self.point_at(s_now + ld)
        dx, dy = tx - x, ty - y
        dist = math.hypot(dx, dy)
        kappa = alpha = 0.0
        if dist > 0.05:
            alpha = wrap(math.atan2(dy, dx) - th)
            kappa = 2 * math.sin(alpha) / dist
        # 速度参考：授权内刹得住 + 转弯限速（物理车再扣掉执行滞后期间会多走的距离）
        d_stop = self.stop_s - s_now - self.v * self.lead
        v_ref = min(self.vmax, math.sqrt(2 * self.a_dec * max(0.0, d_stop)))
        if abs(kappa) > 1e-3:
            v_ref = min(v_ref, max(self.v_turn, math.sqrt(self.a_lat / abs(kappa))))
        if d_stop < 0.01:
            v_ref = 0.0
        if abs(alpha) > 1.0:                        # 方向偏差太大：先原地转向
            v_ref = min(v_ref, 0.05)
        # 本地安全兜底：正前方很近处有其他车则急停（正常情况下服务端授权已保证安全）
        c, s = math.cos(th), math.sin(th)
        for o in self.others:
            rx, ry = o['x'] - x, o['y'] - y
            fwd, lat = rx * c + ry * s, -rx * s + ry * c
            if 0 < fwd < 2 * self.r + 0.12 + 0.3 * self.v and abs(lat) < (self.r + o.get('r', self.r)) * 0.9:
                v_ref = 0.0
                break
        # 速度 P 控制 + 加速度限幅
        a = clamp(self.kp * (v_ref - self.v), -self.a_brake, self.a_acc)
        v_new = max(0.0, self.v + a * dt)
        if s_now + v_new * (dt + self.lead) > self.stop_s:   # 硬约束：绝不越过授权停车点
            v_new = max(0.0, (self.stop_s - s_now) / (dt + self.lead))
        self.v = v_new
        if abs(alpha) > 1.0 and self.v < 0.1:
            self.w = math.copysign(self.w_max * 0.6, alpha)
        else:
            self.w = clamp(self.v * kappa, -self.w_max, self.w_max)
        return self.v, self.w
