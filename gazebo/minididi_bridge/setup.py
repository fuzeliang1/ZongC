"""ROS 2（ament_python）包的安装脚本。世界文件和桥接配置由 ../gen_world.py 生成，一起装到 share/ 下，
launch 文件从那里找到它们。"""
import glob
import os

from setuptools import setup

package_name = 'minididi_bridge'
here = os.path.dirname(os.path.realpath(__file__))   # realpath：包被软链接进工作区时也能找到 ../worlds


def rel(pattern):
    return [os.path.relpath(p, here) for p in sorted(glob.glob(os.path.join(here, pattern)))]


setup(
    name=package_name,
    version='0.1.0',
    packages=[package_name],
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
        ('share/' + package_name + '/launch', rel('launch/*.launch.py')),
        ('share/' + package_name + '/worlds', rel('../worlds/*.sdf')),
        ('share/' + package_name + '/config', rel('../config/*.yaml')),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='MiniDiDi 小组',
    maintainer_email='minididi@example.com',
    description='MiniDiDi Gazebo 桥接',
    license='MIT',
    entry_points={
        'console_scripts': [
            'bridge = minididi_bridge.ros_node:main',
            'bridge_kinematic = minididi_bridge.cli:main',
        ],
    },
)
