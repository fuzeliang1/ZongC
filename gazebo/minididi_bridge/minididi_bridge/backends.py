"""车辆后端：车到底在哪里、怎么动。桥接程序的其余部分只通过这三个方法和车打交道。

  reset(x, y, θ)      把车放到这个位姿（收到 welcome 时调用：设备要从数字孪生的当前位姿开始）
  read()              当前位姿与速度 (x, y, θ, v, ω)，还没有数据时返回 None
  command(v, ω, dt)   下发速度指令

有两种实现：
  KinematicBackend   纯 Python 独轮车模型（本文件）。不用装 ROS / Gazebo，直接接到服务端验证协议和控制逻辑。
  RosGazeboBackend   ROS 2 + Gazebo 里的差速小车（ros_backend.py）：订阅里程计、发布 cmd_vel、用 gz service 传送。
"""
import math

from .follower import wrap


class VehicleBackend:
    name = 'base'
    lead = 0.0          # 执行滞后（秒），控制器据此提前刹车；理想的运动学模型为 0

    @property
    def busy(self):
        """True 表示车还没就位（例如正在传送），这时只上报位姿、不行驶。"""
        return False

    def reset(self, x, y, theta):
        raise NotImplementedError

    def read(self):
        raise NotImplementedError

    def command(self, v, w, dt):
        raise NotImplementedError


class KinematicBackend(VehicleBackend):
    """独轮车（差速）运动学：ẋ = v·cosθ，ẏ = v·sinθ，θ̇ = ω。与 mock_devices.py 的 SimCar 相同。"""

    name = 'kinematic'

    def __init__(self, model_name):
        self.model_name = model_name
        self.x = self.y = self.th = 0.0
        self.v = self.w = 0.0
        self.has_pose = False
        self.dist = 0.0

    def reset(self, x, y, theta):
        self.x, self.y, self.th = x, y, theta
        self.v = self.w = 0.0
        self.has_pose = True

    def read(self):
        if not self.has_pose:
            return None
        return self.x, self.y, self.th, self.v, self.w

    def command(self, v, w, dt):
        if not self.has_pose:
            return
        self.v, self.w = v, w
        nx = self.x + v * math.cos(self.th) * dt
        ny = self.y + v * math.sin(self.th) * dt
        self.dist += math.hypot(nx - self.x, ny - self.y)
        self.x, self.y = nx, ny
        self.th = wrap(self.th + w * dt)
