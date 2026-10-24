"""ROS 2 节点入口：ros2 run minididi_bridge bridge --ros-args -p host:=192.168.1.20 -p cars:=5

参数：
  host / port     数字世界服务端地址（默认 127.0.0.1:9000）
  cars            小车数量（与 gen_world.py 生成的一致，Gazebo 模型名 car_1 … car_N）
  start           申请绑定的第一辆数字车编号：car-<start> … （Matlab 车队占了 car-1…5 时可设为 6）
  kind            注册类型，默认 gazebo
  world           Gazebo 世界名（传送服务 /world/<world>/set_pose 用），默认 minicity
  rate            控制频率（Hz），默认 20；状态上报 10 Hz
  latency         里程计传输延迟估计（秒），默认 0.05；位姿按最新速度外推到"现在"，补偿延迟
  lead            执行滞后（秒），默认 0.08；控制器按 v × lead 提前刹车，保证不越过 stopS

控制循环用墙钟定时器（不用仿真时钟）：服务端按真实时间运行，Gazebo 的实时因子应保持在 1 左右。
"""
import rclpy
import rclpy.executors

from .fleet import Fleet
from .ros_backend import RosGazeboBackend


def main(args=None):
    rclpy.init(args=args)
    node = rclpy.create_node('minididi_bridge')
    p = {
        'host': node.declare_parameter('host', '127.0.0.1').value,
        'port': node.declare_parameter('port', 9000).value,
        'cars': node.declare_parameter('cars', 5).value,
        'start': node.declare_parameter('start', 1).value,
        'kind': node.declare_parameter('kind', 'gazebo').value,
        'world': node.declare_parameter('world', 'minicity').value,
        'rate': node.declare_parameter('rate', 20.0).value,
        'latency': node.declare_parameter('latency', 0.05).value,
        'lead': node.declare_parameter('lead', 0.08).value,
    }
    backends = [RosGazeboBackend(node, f'car_{i + 1}', p['world'], latency=p['latency'], lead=p['lead'])
                for i in range(p['cars'])]
    fleet = Fleet(p['host'], p['port'], backends, p['start'], p['kind'], p['rate'], 10.0)
    node.create_timer(1.0 / p['rate'], fleet.tick)
    node.get_logger().info(f'MiniDiDi 桥接：{p["cars"]} 辆车 → {p["host"]}:{p["port"]}（注册为 {p["kind"]}）')
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, rclpy.executors.ExternalShutdownException):
        pass
    finally:
        print(fleet.summary(), flush=True)
        fleet.close()
        try:                   # ros2 launch 退出时可能再发一次 SIGINT，清理阶段被打断也无妨
            node.destroy_node()
            if rclpy.ok():
                rclpy.shutdown()
        except (KeyboardInterrupt, rclpy.executors.ExternalShutdownException):
            pass


if __name__ == '__main__':
    main()
