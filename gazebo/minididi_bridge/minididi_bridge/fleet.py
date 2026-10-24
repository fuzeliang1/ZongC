"""车队：每辆车 = 一条 TCP 连接 + 一个路径跟踪控制器 + 一个车辆后端。与后端种类无关。

每个控制周期（默认 20 Hz）对每辆车：
  1. 取走服务端发来的消息交给控制器；收到 welcome 时把后端的车放到数字孪生的位姿；
  2. 从后端读当前位姿，控制器算出 (v, ω)，交给后端执行；
  3. 每隔几个周期（默认 10 Hz）上报 state。
断线时每 2 s 重连一次，重连后重新 register（服务端会用 welcome 同步最新位姿）。

调试：设置环境变量 MINIDIDI_TRACE=文件名，会把每个控制周期每辆车的位姿、指令、s、stopS、横向偏差写成 CSV。
"""
import os
import sys
import time

from .follower import PathFollower
from .protocol import DwLink


class CarAgent:
    def __init__(self, host, port, agent_id, backend, kind='gazebo'):
        self.backend = backend
        self.follower = PathFollower(agent_id)
        self.follower.lead = backend.lead
        self.link = DwLink(host, port, agent_id)
        self.register_msg = {'type': 'register', 'kind': kind, 'agentId': agent_id,
                             'name': f'{kind}-{backend.model_name}'}
        self.seq = 0
        self.state_sent = 0
        self.last_pose = None

    @property
    def agent_id(self):
        return self.follower.agent_id

    def ensure_connected(self):
        if self.link.alive:
            return True
        try:
            self.link.connect()
            self.follower.ready = False
            self.link.send(self.register_msg)
            print(f'[{self.backend.model_name}] 已连接 {self.link.host}:{self.link.port}，申请 {self.register_msg["agentId"]}')
            return True
        except OSError as e:
            print(f'[{self.backend.model_name}] 连接失败（{e}），稍后重试', file=sys.stderr)
            return False

    def tick(self, dt, send_state):
        for m in self.link.poll():
            self.follower.on_message(m)
            if m.get('type') == 'welcome':
                x, y, th = self.follower.welcome_pose
                self.backend.reset(x, y, th)
                print(f'[{self.backend.model_name}] 接管 {self.follower.agent_id}，起点 ({x:.2f}, {y:.2f}, {th:.2f} rad)')
        pose = self.backend.read()
        if pose is None or not self.follower.ready:
            self.backend.command(0.0, 0.0, dt)
            return
        self.last_pose = pose
        x, y, th, v, w = pose
        if self.backend.busy:                  # 车还没就位（正在传送）：原地等，控制器的速度状态清零
            self.follower.v = self.follower.w = 0.0
            self.backend.command(0.0, 0.0, dt)
        else:
            v_cmd, w_cmd = self.follower.control(x, y, th, dt)
            self.backend.command(v_cmd, w_cmd, dt)
        if send_state and self.link.alive:
            self.seq += 1
            self.state_sent += 1
            self.link.send({'type': 'state', 'agentId': self.follower.agent_id, 'seq': self.seq,
                            't': int(time.time() * 1000), 'x': round(x, 4), 'y': round(y, 4),
                            'theta': round(th, 4), 'v': round(v, 4), 'w': round(w, 4)})


class Fleet:
    def __init__(self, host, port, backends, start=1, kind='gazebo', hz=20.0, state_hz=10.0):
        self.cars = [CarAgent(host, port, f'car-{start + i}', b, kind) for i, b in enumerate(backends)]
        self.dt = 1.0 / hz
        self.state_every = max(1, round(hz / state_hz))
        self.tick_count = 0
        self.retry_at = 0.0
        path = os.environ.get('MINIDIDI_TRACE')
        self.trace = open(path, 'w', encoding='utf-8') if path else None
        if self.trace:
            self.trace.write('wall,car,x,y,theta,v,v_cmd,w_cmd,s,stop_s,lateral,route_id\n')

    def tick(self):
        now = time.monotonic()
        if now >= self.retry_at:
            if not all([c.ensure_connected() for c in self.cars]):
                self.retry_at = now + 2.0
        send_state = self.tick_count % self.state_every == 0
        for c in self.cars:
            c.tick(self.dt, send_state)
            if self.trace and c.last_pose:
                f = c.follower
                x, y, th, v, _ = c.last_pose
                self.trace.write(f'{time.time():.3f},{c.agent_id},{x:.4f},{y:.4f},{th:.4f},{v:.3f},{f.v:.3f},{f.w:.3f},'
                                 f'{f.last_s:.4f},{f.stop_s:.4f},{f.last_d:.4f},{f.route_id}\n')
        self.tick_count += 1

    def summary(self):
        lines = ['车辆        数字孪生   路线数  授权数  上报数  最大越界(m)  最大横向偏差(m)']
        for c in self.cars:
            f = c.follower
            lines.append(f'{c.backend.model_name:<10}  {f.agent_id:<8}  {f.routes:6d}  {f.permits:6d}  {c.state_sent:6d}  '
                         f'{max(0.0, f.max_overrun):10.3f}  {f.max_lateral:14.3f}')
        return '\n'.join(lines)

    def close(self):
        for c in self.cars:
            c.backend.command(0.0, 0.0, self.dt)
            c.link.close()
        if self.trace:
            self.trace.close()
