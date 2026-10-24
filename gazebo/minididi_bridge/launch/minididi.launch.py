"""一键启动 Gazebo 部分：Gazebo 世界 + ros_gz_bridge + MiniDiDi 桥接节点。

    ros2 launch minididi_bridge minididi.launch.py host:=192.168.1.20
    ros2 launch minididi_bridge minididi.launch.py gz_args:="-r -s --headless-rendering"   # 不开界面（服务器模式）
    ros2 launch minididi_bridge minididi.launch.py start:=6                               # 绑定 car-6 … car-10

数字世界服务端要先在 host 上运行（scripts/start_server.sh）。
"""
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration, PathJoinSubstitution
from launch_ros.actions import Node
from launch_ros.substitutions import FindPackageShare


def generate_launch_description():
    share = FindPackageShare('minididi_bridge')
    args = [
        DeclareLaunchArgument('world', default_value=PathJoinSubstitution([share, 'worlds', 'minicity.sdf']),
                              description='Gazebo 世界文件（gazebo/gen_world.py 生成）'),
        DeclareLaunchArgument('bridge_config', default_value=PathJoinSubstitution([share, 'config', 'ros_gz_bridge.yaml']),
                              description='ros_gz_bridge 话题映射（gen_world.py 生成）'),
        DeclareLaunchArgument('gz_args', default_value='-r', description='传给 gz sim 的参数，-r 表示立即开始仿真'),
        DeclareLaunchArgument('host', default_value='127.0.0.1', description='数字世界服务端地址'),
        DeclareLaunchArgument('port', default_value='9000'),
        DeclareLaunchArgument('cars', default_value='5'),
        DeclareLaunchArgument('start', default_value='1', description='绑定的第一辆数字车编号'),
        DeclareLaunchArgument('latency', default_value='0.05', description='里程计传输延迟估计（秒），用于外推补偿'),
    ]
    gz = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(PathJoinSubstitution([FindPackageShare('ros_gz_sim'), 'launch', 'gz_sim.launch.py'])),
        launch_arguments={'gz_args': [LaunchConfiguration('gz_args'), ' ', LaunchConfiguration('world')]}.items())
    ros_gz = Node(package='ros_gz_bridge', executable='parameter_bridge', name='ros_gz_bridge', output='screen',
                  parameters=[{'config_file': LaunchConfiguration('bridge_config')}])
    bridge = Node(package='minididi_bridge', executable='bridge', name='minididi_bridge', output='screen',
                  parameters=[{'host': LaunchConfiguration('host'),
                               'port': LaunchConfiguration('port'),
                               'cars': LaunchConfiguration('cars'),
                               'start': LaunchConfiguration('start'),
                               'latency': LaunchConfiguration('latency')}])
    return LaunchDescription(args + [gz, ros_gz, bridge])
