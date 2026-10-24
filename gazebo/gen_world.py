#!/usr/bin/env python3
"""由数字世界的地图 JSON 生成 Gazebo（Harmonic）世界：地面、道路、路口、中心黄线、楼房，以及 N 辆差速小车。

用法（仓库根目录）：
    python3 gazebo/gen_world.py                                   # 默认地图、5 辆车
    python3 gazebo/gen_world.py --map server/maps/my.json --cars 8

输出：
    gazebo/worlds/minicity.sdf              Gazebo 世界（SDF 1.9）
    gazebo/config/ros_gz_bridge.yaml        ros_gz_bridge 的话题映射（每辆车 cmd_vel + odometry，外加 /clock）

坐标：Gazebo 世界坐标与数字世界地图坐标完全相同——x 向东、y 向北、单位米，原点在地图西南角，z 向上。
所以桥接程序不需要做任何坐标换算，航向角 θ 就是小车绕 z 轴的偏航角。

为什么要合并格子：地图有 1800 个格子，每格一个模型 Gazebo 会很卡。把同类相邻的格子合并成尽量大的矩形，
楼房只剩二十几个盒子，道路几十块，加载和渲染都快。只用 Python 标准库。
"""
import argparse
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

LANES = set('><^v')
# 小车尺寸（米）：车身外接圆半径约 0.25 m，小于服务端的 bodyRadius = 0.3 m
BODY_L, BODY_W, BODY_H = 0.42, 0.28, 0.10
WHEEL_R, WHEEL_W, WHEEL_SEP = 0.05, 0.03, 0.30
CAR_COLORS = [(0.16, 0.47, 0.84), (0.92, 0.41, 0.20), (0.11, 0.69, 0.48), (0.93, 0.63, 0.00),
              (0.91, 0.48, 0.64), (0.00, 0.51, 0.00), (0.38, 0.31, 0.84), (0.89, 0.29, 0.28)]


def load_map(path):
    with open(path, encoding='utf-8') as f:
        m = json.load(f)
    rows = m['rows']
    h, w = len(rows), len(rows[0])
    if any(len(r) != w for r in rows):
        raise ValueError('地图每一行的长度必须相同')
    res = float(m.get('resolution', 1.0))
    # rows 第一行是最北边；转成 grid[y][x]，y = 0 是最南边
    grid = [rows[h - 1 - y] for y in range(h)]
    return m.get('name', 'map'), w, h, res, grid


def rectangles(w, h, test):
    """把满足 test(x, y) 的格子贪心合并成矩形，返回 [(x0, y0, x1, y1)]（含端点，格子坐标）。"""
    used = [[False] * w for _ in range(h)]
    out = []
    for y in range(h):
        for x in range(w):
            if used[y][x] or not test(x, y):
                continue
            x1 = x
            while x1 + 1 < w and not used[y][x1 + 1] and test(x1 + 1, y):
                x1 += 1
            y1 = y
            while y1 + 1 < h and all(not used[y1 + 1][i] and test(i, y1 + 1) for i in range(x, x1 + 1)):
                y1 += 1
            for yy in range(y, y1 + 1):
                for xx in range(x, x1 + 1):
                    used[yy][xx] = True
            out.append((x, y, x1, y1))
    return out


def center_lines(w, h, grid):
    """对向车道之间的中心黄线：返回 [(cx, cy, sx, sy)]（中心与尺寸，米 = 格），相邻的线段已合并。"""
    segs = []
    # 东西向道路：南侧一行向东（>），北侧一行向西（<），黄线在两行之间
    for y in range(h - 1):
        x = 0
        while x < w:
            if grid[y][x] == '>' and grid[y + 1][x] == '<':
                x0 = x
                while x + 1 < w and grid[y][x + 1] == '>' and grid[y + 1][x + 1] == '<':
                    x += 1
                segs.append(((x0 + x + 1) / 2, y + 1, x - x0 + 1, 0.06))
            x += 1
    # 南北向道路：西侧一列向南（v），东侧一列向北（^）
    for x in range(w - 1):
        y = 0
        while y < h:
            if grid[y][x] == 'v' and grid[y][x + 1] == '^':
                y0 = y
                while y + 1 < h and grid[y + 1][x] == 'v' and grid[y + 1][x + 1] == '^':
                    y += 1
                segs.append((x + 1, (y0 + y + 1) / 2, 0.06, y - y0 + 1))
            y += 1
    return segs


def building_height(x0, y0, x1, y1):
    """楼高 1.2~3.0 m（1~4 层），由位置决定，每次生成都一样。"""
    return 1.2 + 0.6 * ((x0 * 7 + y0 * 13 + x1 * 3 + y1 * 5) % 4)


def box(name, cx, cy, cz, sx, sy, sz, rgb, collision):
    r, g, b = rgb
    col = f'''
        <collision name="{name}_c"><pose>{cx:.3f} {cy:.3f} {cz:.4f} 0 0 0</pose>
          <geometry><box><size>{sx:.3f} {sy:.3f} {sz:.4f}</size></box></geometry></collision>''' if collision else ''
    return f'''
        <visual name="{name}"><pose>{cx:.3f} {cy:.3f} {cz:.4f} 0 0 0</pose>
          <geometry><box><size>{sx:.3f} {sy:.3f} {sz:.4f}</size></box></geometry>
          <material><ambient>{r:.2f} {g:.2f} {b:.2f} 1</ambient><diffuse>{r:.2f} {g:.2f} {b:.2f} 1</diffuse></material>
        </visual>{col}'''


def city_model(w, h, res, grid):
    parts = []
    # 地面（带碰撞，车轮压在它上面）
    gw, gh = w * res + 6, h * res + 8
    parts.append(box('ground', w * res / 2, h * res / 2 - 1, -0.05, gw, gh, 0.1, (0.55, 0.62, 0.50), True))
    kinds = [
        ('lane', lambda x, y: grid[y][x] in LANES, (0.30, 0.31, 0.33), 0.004),
        ('cross', lambda x, y: grid[y][x] == '+', (0.36, 0.37, 0.39), 0.004),
        ('open', lambda x, y: grid[y][x] == '.', (0.70, 0.70, 0.66), 0.004),
    ]
    n_rect = {}
    for name, test, rgb, thick in kinds:
        rects = rectangles(w, h, test)
        n_rect[name] = len(rects)
        for i, (x0, y0, x1, y1) in enumerate(rects):
            sx, sy = (x1 - x0 + 1) * res, (y1 - y0 + 1) * res
            parts.append(box(f'{name}_{i}', (x0 * res + sx / 2), (y0 * res + sy / 2), thick / 2, sx, sy, thick, rgb, False))
    for i, (cx, cy, sx, sy) in enumerate(center_lines(w, h, grid)):
        parts.append(box(f'center_{i}', cx * res, cy * res, 0.005, sx * res, sy * res, 0.002, (0.95, 0.78, 0.10), False))
    rects = rectangles(w, h, lambda x, y: grid[y][x] == '#')
    n_rect['building'] = len(rects)
    for i, (x0, y0, x1, y1) in enumerate(rects):
        # 楼房比所在格子每边缩进 0.1 m，留出人行道
        sx, sy = (x1 - x0 + 1) * res - 0.2, (y1 - y0 + 1) * res - 0.2
        hgt = building_height(x0, y0, x1, y1)
        shade = 0.62 + 0.06 * ((x0 + y0) % 3)
        parts.append(box(f'building_{i}', x0 * res + 0.1 + sx / 2, y0 * res + 0.1 + sy / 2, hgt / 2, sx, sy, hgt,
                         (shade, shade, shade + 0.04), True))
    xml = f'''
    <model name="minicity">
      <static>true</static>
      <link name="city">{''.join(parts)}
      </link>
    </model>'''
    return xml, n_rect


def car_model(name, x, y, yaw, rgb):
    """一辆差速小车：车身 + 两个驱动轮 + 前后万向球。

    DiffDrive 插件订阅 /model/<name>/cmd_vel（gz.msgs.Twist）；OdometryPublisher 插件发布
    /model/<name>/odometry（世界坐标系下的位姿和速度，桥接程序用它上报 state）。
    DiffDrive 自己的里程计是车轮积分出来的、相对出生点，改名为 wheel_odom 避免混淆。
    """
    r, g, b = rgb
    z_body = WHEEL_R + BODY_H / 2
    half = WHEEL_SEP / 2

    def inertia(m, ixx, iyy, izz):
        return f'<inertial><mass>{m}</mass><inertia><ixx>{ixx:.6f}</ixx><iyy>{iyy:.6f}</iyy><izz>{izz:.6f}</izz>' \
               f'<ixy>0</ixy><ixz>0</ixz><iyz>0</iyz></inertia></inertial>'

    m_body, m_wheel, m_caster = 1.0, 0.1, 0.02
    i_body = inertia(m_body, m_body * (BODY_W ** 2 + BODY_H ** 2) / 12, m_body * (BODY_L ** 2 + BODY_H ** 2) / 12,
                     m_body * (BODY_L ** 2 + BODY_W ** 2) / 12)
    i_wheel = inertia(m_wheel, m_wheel * (3 * WHEEL_R ** 2 + WHEEL_W ** 2) / 12, m_wheel * WHEEL_R ** 2 / 2,
                      m_wheel * (3 * WHEEL_R ** 2 + WHEEL_W ** 2) / 12)
    i_caster = inertia(m_caster, 2 * m_caster * 0.025 ** 2 / 5, 2 * m_caster * 0.025 ** 2 / 5, 2 * m_caster * 0.025 ** 2 / 5)
    wheels = ''
    for side, sy in (('left', half), ('right', -half)):
        wheels += f'''
      <link name="{side}_wheel">
        <pose>0 {sy:.3f} {WHEEL_R} -1.5708 0 0</pose>{i_wheel}
        <visual name="v"><geometry><cylinder><radius>{WHEEL_R}</radius><length>{WHEEL_W}</length></cylinder></geometry>
          <material><ambient>0.1 0.1 0.1 1</ambient><diffuse>0.1 0.1 0.1 1</diffuse></material></visual>
        <collision name="c"><geometry><cylinder><radius>{WHEEL_R}</radius><length>{WHEEL_W}</length></cylinder></geometry>
          <surface><friction><ode><mu>1.0</mu><mu2>1.0</mu2></ode></friction></surface></collision>
      </link>
      <joint name="{side}_wheel_joint" type="revolute">
        <parent>chassis</parent><child>{side}_wheel</child>
        <axis><xyz expressed_in="__model__">0 1 0</xyz><limit><lower>-1e16</lower><upper>1e16</upper></limit></axis>
      </joint>'''
    casters = ''
    for tag, cx in (('front', BODY_L / 2 - 0.04), ('rear', -BODY_L / 2 + 0.04)):
        casters += f'''
      <link name="{tag}_caster">
        <pose>{cx:.3f} 0 0.025 0 0 0</pose>{i_caster}
        <visual name="v"><geometry><sphere><radius>0.025</radius></sphere></geometry></visual>
        <collision name="c"><geometry><sphere><radius>0.025</radius></sphere></geometry>
          <surface><friction><ode><mu>0.0</mu><mu2>0.0</mu2></ode></friction></surface></collision>
      </link>
      <joint name="{tag}_caster_joint" type="ball"><parent>chassis</parent><child>{tag}_caster</child></joint>'''
    return f'''
    <model name="{name}">
      <pose>{x:.3f} {y:.3f} 0.002 0 0 {yaw:.4f}</pose>
      <link name="chassis">
        <pose>0 0 {z_body:.3f} 0 0 0</pose>{i_body}
        <visual name="body"><geometry><box><size>{BODY_L} {BODY_W} {BODY_H}</size></box></geometry>
          <material><ambient>{r} {g} {b} 1</ambient><diffuse>{r} {g} {b} 1</diffuse></material></visual>
        <visual name="roof"><pose>-0.03 0 {BODY_H / 2 + 0.02:.3f} 0 0 0</pose>
          <geometry><box><size>0.20 0.22 0.04</size></box></geometry>
          <material><ambient>0.9 0.9 0.9 1</ambient><diffuse>0.9 0.9 0.9 1</diffuse></material></visual>
        <collision name="c"><geometry><box><size>{BODY_L} {BODY_W} {BODY_H}</size></box></geometry></collision>
      </link>{wheels}{casters}
      <plugin filename="gz-sim-diff-drive-system" name="gz::sim::systems::DiffDrive">
        <left_joint>left_wheel_joint</left_joint>
        <right_joint>right_wheel_joint</right_joint>
        <wheel_separation>{WHEEL_SEP}</wheel_separation>
        <wheel_radius>{WHEEL_R}</wheel_radius>
        <max_linear_acceleration>4.0</max_linear_acceleration>
        <min_linear_acceleration>-4.0</min_linear_acceleration>
        <max_angular_acceleration>25.0</max_angular_acceleration>
        <min_angular_acceleration>-25.0</min_angular_acceleration>
        <max_velocity>2.0</max_velocity>
        <topic>/model/{name}/cmd_vel</topic>
        <odom_topic>/model/{name}/wheel_odom</odom_topic>
        <tf_topic>/model/{name}/wheel_tf</tf_topic>
        <frame_id>{name}/odom</frame_id>
        <child_frame_id>{name}/base</child_frame_id>
        <odom_publish_frequency>20</odom_publish_frequency>
      </plugin>
      <plugin filename="gz-sim-odometry-publisher-system" name="gz::sim::systems::OdometryPublisher">
        <odom_frame>map</odom_frame>
        <robot_base_frame>{name}</robot_base_frame>
        <odom_topic>/model/{name}/odometry</odom_topic>
        <tf_topic>/model/{name}/tf</tf_topic>
        <odom_publish_frequency>50</odom_publish_frequency>
        <dimensions>2</dimensions>
      </plugin>
    </model>'''


def world_sdf(map_name, w, h, res, grid, cars):
    city, n_rect = city_model(w, h, res, grid)
    car_xml = ''
    # 小车出生在地图南边外侧的"停车场"里，不挡路；桥接程序收到 welcome 后把它们传送到数字孪生的位置
    for i in range(cars):
        car_xml += car_model(f'car_{i + 1}', 1.5 + i * 1.0, -1.5, math.pi / 2, CAR_COLORS[i % len(CAR_COLORS)])
    cx, cy = w * res / 2, h * res / 2
    sdf = f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- 由 gazebo/gen_world.py 从地图 "{map_name}" 自动生成，请不要手工修改（改了会被下次生成覆盖）。
     坐标与数字世界地图相同：x 向东、y 向北、单位米，原点在地图西南角。 -->
<sdf version="1.9">
  <world name="minicity">
    <physics name="1ms" type="ignored">
      <max_step_size>0.001</max_step_size>
      <real_time_factor>1.0</real_time_factor>
    </physics>
    <plugin filename="gz-sim-physics-system" name="gz::sim::systems::Physics"/>
    <plugin filename="gz-sim-user-commands-system" name="gz::sim::systems::UserCommands"/>
    <plugin filename="gz-sim-scene-broadcaster-system" name="gz::sim::systems::SceneBroadcaster"/>
    <gui fullscreen="0">
      <plugin filename="MinimalScene" name="3D View">
        <gz-gui><title>3D View</title><property type="bool" key="showTitleBar">false</property>
          <property type="string" key="state">docked</property></gz-gui>
        <engine>ogre2</engine>
        <scene>scene</scene>
        <ambient_light>0.5 0.5 0.5</ambient_light>
        <background_color>0.75 0.85 0.95</background_color>
        <camera_pose>{cx:.1f} {-h * 0.55:.1f} {max(w, h) * 0.75:.1f} 0 0.85 1.5708</camera_pose>
      </plugin>
      <plugin filename="GzSceneManager" name="Scene Manager"><gz-gui><property type="bool" key="showTitleBar">false</property>
        <property type="string" key="state">floating</property></gz-gui></plugin>
      <plugin filename="InteractiveViewControl" name="Interactive view control"><gz-gui>
        <property type="bool" key="showTitleBar">false</property><property type="string" key="state">floating</property></gz-gui></plugin>
      <plugin filename="WorldControl" name="World control"><gz-gui><title>World control</title>
        <property type="bool" key="showTitleBar">false</property><property type="string" key="state">floating</property>
        <anchors target="3D View"><line own="left" target="left"/><line own="bottom" target="bottom"/></anchors></gz-gui>
        <play_pause>true</play_pause><step>true</step><start_paused>false</start_paused></plugin>
      <plugin filename="EntityTree" name="Entity tree"/>
    </gui>
    <scene>
      <ambient>0.6 0.6 0.6 1</ambient>
      <background>0.75 0.85 0.95 1</background>
      <shadows>true</shadows>
    </scene>
    <light type="directional" name="sun">
      <cast_shadows>true</cast_shadows>
      <pose>{cx:.1f} {cy:.1f} 30 0 0 0</pose>
      <diffuse>0.9 0.9 0.85 1</diffuse>
      <specular>0.2 0.2 0.2 1</specular>
      <direction>-0.4 0.3 -0.9</direction>
    </light>{city}{car_xml}
  </world>
</sdf>
'''
    return sdf, n_rect


def bridge_yaml(cars):
    lines = ['# 由 gazebo/gen_world.py 自动生成：ros_gz_bridge 的话题映射。',
             '# 每辆车两条：ROS → Gazebo 的速度指令，Gazebo → ROS 的世界坐标里程计；外加仿真时钟。',
             '- ros_topic_name: "/clock"',
             '  gz_topic_name: "/clock"',
             '  ros_type_name: "rosgraph_msgs/msg/Clock"',
             '  gz_type_name: "gz.msgs.Clock"',
             '  direction: GZ_TO_ROS']
    for i in range(cars):
        n = f'car_{i + 1}'
        lines += [f'- ros_topic_name: "/{n}/cmd_vel"',
                  f'  gz_topic_name: "/model/{n}/cmd_vel"',
                  '  ros_type_name: "geometry_msgs/msg/Twist"',
                  '  gz_type_name: "gz.msgs.Twist"',
                  '  direction: ROS_TO_GZ',
                  f'- ros_topic_name: "/{n}/odom"',
                  f'  gz_topic_name: "/model/{n}/odometry"',
                  '  ros_type_name: "nav_msgs/msg/Odometry"',
                  '  gz_type_name: "gz.msgs.Odometry"',
                  '  direction: GZ_TO_ROS']
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser(description='由地图 JSON 生成 Gazebo 世界')
    ap.add_argument('--map', default=os.path.join(ROOT, 'server', 'maps', 'city-5x4.json'))
    ap.add_argument('--cars', type=int, default=5, help='小车数量（PPT 进阶要求至少 5 辆）')
    ap.add_argument('--out', default=os.path.join(HERE, 'worlds', 'minicity.sdf'))
    ap.add_argument('--bridge-config', default=os.path.join(HERE, 'config', 'ros_gz_bridge.yaml'))
    args = ap.parse_args()

    name, w, h, res, grid = load_map(args.map)
    sdf, n_rect = world_sdf(name, w, h, res, grid, args.cars)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as f:
        f.write(sdf)
    os.makedirs(os.path.dirname(args.bridge_config), exist_ok=True)
    with open(args.bridge_config, 'w', encoding='utf-8') as f:
        f.write(bridge_yaml(args.cars))
    print(f'地图 {name}：{w}×{h} 格，{res} m/格')
    print(f'合并后：楼房 {n_rect["building"]} 块，车道 {n_rect["lane"]} 块，路口 {n_rect["cross"]} 块，开放区域 {n_rect["open"]} 块')
    print(f'已生成 {os.path.relpath(args.out, ROOT)}（{args.cars} 辆车）和 {os.path.relpath(args.bridge_config, ROOT)}')


if __name__ == '__main__':
    main()
