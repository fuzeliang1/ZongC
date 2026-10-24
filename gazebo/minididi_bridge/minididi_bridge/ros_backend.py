"""ROS 2 + Gazebo 后端：一辆 Gazebo 差速小车。

  位姿    订阅 /<车名>/odom（nav_msgs/Odometry）。由 gen_world.py 在每辆车上加的 OdometryPublisher 插件发布，
          经 ros_gz_bridge 转成 ROS 消息，是世界坐标系（= 数字世界地图坐标系）下的位姿和速度。
  指令    发布 /<车名>/cmd_vel（geometry_msgs/Twist），经 ros_gz_bridge 交给 DiffDrive 插件。
  传送    收到 welcome 时调用 Gazebo 的 /world/<世界名>/set_pose 服务（通过 gz 命令行，在后台线程里执行，
          不阻塞控制循环），把小车放到数字孪生的当前位姿。

传送完成之前，里程计还是旧位置（停车场），这时 read() 先返回目标位姿、command() 只发零速度，
等里程计到了目标附近再用真实数据，绝不把停车场的位置报给服务端。Gazebo 很忙（实时因子低）时传送服务
可能超时，所以每 3 s 重试一次，直到确认为止。

延迟补偿：里程计从 Gazebo 经 ros_gz_bridge 到这里有几十毫秒延迟，两条消息之间还要再等最多 20 ms。
按 1.5 m/s 算，0.1 s 的延迟就是 0.15 m——控制器会以为车还没开到那么远，把停车余量吃掉。
所以 read() 用最新的速度把位姿外推到"现在"：外推时间 = 收到消息后经过的时间 + 传输延迟估计 latency。
"""
import math
import subprocess
import threading
import time

from geometry_msgs.msg import Twist
from nav_msgs.msg import Odometry

from .backends import VehicleBackend


def yaw_of(q):
    return math.atan2(2.0 * (q.w * q.z + q.x * q.y), 1.0 - 2.0 * (q.y * q.y + q.z * q.z))


class RosGazeboBackend(VehicleBackend):
    name = 'gazebo'

    def __init__(self, node, model_name, world='minicity', latency=0.05, lead=0.08, odom_offset=(0.0, 0.0, 0.0)):
        self.node = node
        self.model_name = model_name
        self.world = world
        self.latency = latency              # 里程计传输延迟估计（秒）
        self.lead = lead                    # 执行滞后（秒）：cmd_vel 经桥接、DiffDrive 加速度限制到车轮真正减速
        self.odom_offset = odom_offset      # 如果里程计不是世界坐标（不应该发生），在这里加上偏移
        self.pose = None                    # (x, y, θ, v, ω)
        self.pose_time = 0.0                # 收到这条里程计的时刻（墙钟）
        self.pending = None                 # 正在传送的目标位姿 (x, y, θ, 下次重试时间)
        self.teleport_tries = 0
        self.lock = threading.Lock()
        self.pub = node.create_publisher(Twist, f'/{model_name}/cmd_vel', 10)
        node.create_subscription(Odometry, f'/{model_name}/odom', self._on_odom, 10)

    @property
    def busy(self):
        return self.pending is not None

    def _on_odom(self, msg):
        ox, oy, oth = self.odom_offset
        p, q, t = msg.pose.pose.position, msg.pose.pose.orientation, msg.twist.twist
        x, y, th = p.x + ox, p.y + oy, yaw_of(q) + oth
        with self.lock:
            self.pose = (x, y, th, math.hypot(t.linear.x, t.linear.y), t.angular.z)   # 小车只前进，用模长（与坐标系无关）
            self.pose_time = time.monotonic()
            if self.pending and math.hypot(x - self.pending[0], y - self.pending[1]) < 0.3:
                self.pending = None

    def _teleport(self, x, y, theta):
        req = (f'name: "{self.model_name}", position: {{x: {x:.4f}, y: {y:.4f}, z: 0.002}}, '
               f'orientation: {{x: 0, y: 0, z: {math.sin(theta / 2):.6f}, w: {math.cos(theta / 2):.6f}}}')
        cmd = ['gz', 'service', '-s', f'/world/{self.world}/set_pose', '--reqtype', 'gz.msgs.Pose',
               '--reptype', 'gz.msgs.Boolean', '--timeout', '3000', '--req', req]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            if 'true' not in r.stdout:
                self.node.get_logger().warn(f'{self.model_name} 传送失败：{r.stdout.strip()} {r.stderr.strip()}')
        except (OSError, subprocess.TimeoutExpired) as e:
            self.node.get_logger().error(f'{self.model_name} 无法调用 gz service：{e}')

    def reset(self, x, y, theta):
        with self.lock:
            self.pending = (x, y, theta, time.monotonic() + 3.0)
            self.teleport_tries = 1
        self.command(0.0, 0.0, 0.0)
        threading.Thread(target=self._teleport, args=(x, y, theta), daemon=True).start()

    def read(self):
        with self.lock:
            if self.pending:
                x, y, th, retry_at = self.pending
                if time.monotonic() >= retry_at:
                    self.teleport_tries += 1
                    self.node.get_logger().warn(f'{self.model_name} 3 s 内没有看到传送结果，第 {self.teleport_tries} 次重试'
                                                '（Gazebo 实时因子太低？UserCommands 插件在不在？）')
                    self.pending = (x, y, th, time.monotonic() + 3.0)
                    threading.Thread(target=self._teleport, args=(x, y, th), daemon=True).start()
                return x, y, th, 0.0, 0.0
            if self.pose is None:
                return None
            x, y, th, v, w = self.pose
            age = min(0.3, time.monotonic() - self.pose_time + self.latency)
        # 独轮车模型外推 age 秒（ω 很小时退化为直线）
        if abs(w) > 1e-6:
            nth = th + w * age
            x += v / w * (math.sin(nth) - math.sin(th))
            y -= v / w * (math.cos(nth) - math.cos(th))
            th = math.atan2(math.sin(nth), math.cos(nth))
        else:
            x += v * math.cos(th) * age
            y += v * math.sin(th) * age
        return x, y, th, v, w

    def command(self, v, w, dt):
        if self.pending:                    # 还没传送到位：原地不动
            v = w = 0.0
        msg = Twist()
        msg.linear.x = float(v)
        msg.angular.z = float(w)
        try:
            self.pub.publish(msg)
        except Exception:      # 按 Ctrl+C 后 ROS 上下文已关闭，发布会失败；这时已经无所谓了
            pass
