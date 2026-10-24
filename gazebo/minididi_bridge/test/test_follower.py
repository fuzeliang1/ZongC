"""路径跟踪控制器 + 运动学后端的离线测试（不需要服务端，也不需要 ROS）。

用法：cd gazebo/minididi_bridge && python3 -m unittest discover -s test -v
"""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minididi_bridge.backends import KinematicBackend  # noqa: E402
from minididi_bridge.follower import PathFollower  # noqa: E402


def straight(x0, y0, length, step=0.25):
    return [[x0 + i * step, y0] for i in range(int(length / step) + 1)]


def quarter_turn():
    """沿 x 轴走 2 m，再以半径 0.5 m 左转 90° 向北走 2 m（与服务端路线的转弯形状相同）。"""
    pts = [[i * 0.25, 0.0] for i in range(9)]
    for k in range(1, 9):
        a = -math.pi / 2 + k * (math.pi / 2) / 8
        pts.append([2.0 + 0.5 * math.cos(a), 0.5 + 0.5 * math.sin(a)])
    pts += [[2.5, 0.5 + i * 0.25] for i in range(1, 9)]
    return pts


def dist_to_polyline(x, y, pts):
    best = float('inf')
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        L2 = (bx - ax) ** 2 + (by - ay) ** 2
        t = max(0.0, min(1.0, ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / L2))
        best = min(best, math.hypot(x - ax - t * (bx - ax), y - ay - t * (by - ay)))
    return best


class Sim:
    def __init__(self, waypoints, pose=(0.0, 0.0, 0.0)):
        self.f = PathFollower('car-1')
        self.car = KinematicBackend('car_1')
        self.f.on_message({'type': 'welcome', 'agentId': 'car-1',
                           'pose': {'x': pose[0], 'y': pose[1], 'theta': pose[2], 'v': 0},
                           'params': {'bodyRadius': 0.3, 'vMax': 1.5, 'aAcc': 1.0, 'aDec': 1.5, 'vTurn': 0.6}})
        self.car.reset(*pose)
        self.f.on_message({'type': 'path', 'routeId': 1, 'waypoints': waypoints})
        self.max_s = -1e9

    def permit(self, stop_s, final=False, route_id=1):
        self.f.on_message({'type': 'permit', 'routeId': route_id, 'stopS': stop_s, 'final': final})

    def run(self, seconds, dt=0.05, hook=None):
        for k in range(int(seconds / dt)):
            x, y, th, _, _ = self.car.read()
            v, w = self.f.control(x, y, th, dt)
            self.car.command(v, w, dt)
            self.max_s = max(self.max_s, self.f.last_s)
            if hook:
                hook(k * dt)


class FollowerTest(unittest.TestCase):
    def test_no_motion_before_permit(self):
        sim = Sim(straight(0, 0, 5))
        sim.run(2.0)
        self.assertAlmostEqual(sim.car.x, 0.0, places=6)

    def test_stops_before_stop_s(self):
        sim = Sim(straight(0, 0, 8))
        sim.permit(3.0)
        sim.run(8.0)
        self.assertLessEqual(sim.max_s, 3.0 + 1e-6, '投影长度不能超过 stopS')
        self.assertLess(3.0 - sim.car.x, 0.1, '应开到停车点附近')
        self.assertLess(sim.car.v, 1e-3, '最后应停稳')

    def test_permit_revocation_is_obeyed(self):
        """行驶中 stopS 变小（行人闯入 / 出现障碍）：必须采用新值，在新的停车点前停下。"""
        sim = Sim(straight(0, 0, 12))
        sim.permit(10.0)
        state = {'revoked': None}

        def hook(t):
            if t >= 3.0 and state['revoked'] is None:
                s = sim.f.last_s
                state['revoked'] = s + 0.8          # 以 1.5 m/s 行驶，最大减速 3 m/s² 的刹车距离 0.375 m
                sim.permit(state['revoked'])
        sim.run(8.0, hook=hook)
        self.assertIsNotNone(state['revoked'])
        self.assertLessEqual(sim.f.last_s, state['revoked'] + 1e-6)

    def test_follows_a_turn(self):
        pts = quarter_turn()
        sim = Sim(pts)
        sim.permit(sim.f.cum[-1], final=True)          # 终点授权：stopS = 路线全长（与服务端一致）
        worst = [0.0]

        def hook(t):
            worst[0] = max(worst[0], dist_to_polyline(sim.car.x, sim.car.y, pts))
        sim.run(10.0, hook=hook)
        self.assertLess(math.hypot(sim.car.x - 2.5, sim.car.y - 2.5), 0.15, '应到达终点')
        self.assertLess(worst[0], 0.1, '横向偏差应很小，车身不会越出车道')

    def test_ignores_permit_for_other_route(self):
        sim = Sim(straight(0, 0, 5))
        sim.permit(3.0, route_id=99)
        sim.run(2.0)
        self.assertAlmostEqual(sim.car.x, 0.0, places=6)

    def test_emergency_stop_for_car_ahead(self):
        sim = Sim(straight(0, 0, 10))
        sim.permit(10.0)
        sim.f.on_message({'type': 'world', 'agents': [{'id': 'car-2', 'x': 2.0, 'y': 0.0, 'r': 0.3}]})
        sim.run(6.0)
        self.assertLess(sim.car.x, 2.0 - 0.6 + 0.05, '前方近处有车时应停下，不碰到它')


if __name__ == '__main__':
    unittest.main()
