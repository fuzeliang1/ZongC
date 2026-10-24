"""不装 ROS 也能运行的入口：用纯 Python 运动学后端代替 Gazebo，直接接到数字世界服务端，
验证"注册为 gazebo 设备 → 按路线和授权行驶 → 上报状态"整条链路。

用法（仓库根目录，先启动服务端）：
    PYTHONPATH=gazebo/minididi_bridge python3 -m minididi_bridge --cars 5
    PYTHONPATH=gazebo/minididi_bridge python3 -m minididi_bridge --cars 5 --start 6 --duration 120

结束时打印每辆车收到的路线数、授权数、上报数，以及"最大越界"（投影长度超过 stopS 的最大值，应为 0）。
"""
import argparse
import time

from .backends import KinematicBackend
from .fleet import Fleet


def main(argv=None):
    ap = argparse.ArgumentParser(description='MiniDiDi Gazebo 桥接（运动学后端，不需要 ROS）')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=9000)
    ap.add_argument('--cars', type=int, default=5)
    ap.add_argument('--start', type=int, default=1, help='申请绑定的第一辆数字车编号（car-<start>）')
    ap.add_argument('--kind', default='gazebo')
    ap.add_argument('--hz', type=float, default=20.0)
    ap.add_argument('--state-hz', type=float, default=10.0)
    ap.add_argument('--duration', type=float, default=0, help='运行秒数，0 表示一直运行')
    args = ap.parse_args(argv)

    backends = [KinematicBackend(f'car_{i + 1}') for i in range(args.cars)]
    fleet = Fleet(args.host, args.port, backends, args.start, args.kind, args.hz, args.state_hz)
    t0 = time.monotonic()
    next_tick = t0
    try:
        while not args.duration or time.monotonic() - t0 < args.duration:
            fleet.tick()
            next_tick += fleet.dt
            sleep = next_tick - time.monotonic()
            if sleep > 0:
                time.sleep(sleep)
            else:
                next_tick = time.monotonic()
    except KeyboardInterrupt:
        pass
    finally:
        fleet.close()
        print(fleet.summary())
    return fleet


if __name__ == '__main__':
    main()
