"""世界生成器测试：矩形合并不重不漏、SDF 结构正确、每辆车的话题互不冲突。"""
import os
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
GAZEBO = os.path.dirname(os.path.dirname(HERE))
ROOT = os.path.dirname(GAZEBO)
sys.path.insert(0, GAZEBO)

import gen_world  # noqa: E402

MAP = os.path.join(ROOT, 'server', 'maps', 'city-5x4.json')


class GenWorldTest(unittest.TestCase):
    def test_rectangles_cover_buildings_exactly(self):
        _, w, h, _, grid = gen_world.load_map(MAP)
        rects = gen_world.rectangles(w, h, lambda x, y: grid[y][x] == '#')
        covered = set()
        for x0, y0, x1, y1 in rects:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    self.assertNotIn((x, y), covered, '矩形不能重叠')
                    self.assertEqual(grid[y][x], '#')
                    covered.add((x, y))
        n = sum(row.count('#') for row in grid)
        self.assertEqual(len(covered), n, '所有楼房格都要被覆盖')
        self.assertLess(len(rects), n / 10, '合并后的块数应远少于格子数')

    def test_world_and_bridge_config(self):
        _, w, h, res, grid = gen_world.load_map(MAP)
        sdf, _ = gen_world.world_sdf('test', w, h, res, grid, 5)
        with tempfile.NamedTemporaryFile('w', suffix='.sdf', delete=False, encoding='utf-8') as f:
            f.write(sdf)
        try:
            world = ET.parse(f.name).getroot().find('world')
        finally:
            os.unlink(f.name)
        self.assertEqual(world.get('name'), 'minicity')
        names = [m.get('name') for m in world.findall('model')]
        self.assertEqual(names, ['minicity', 'car_1', 'car_2', 'car_3', 'car_4', 'car_5'])
        topics = [t.text for t in world.iter('topic')] + [t.text for t in world.iter('odom_topic')]
        self.assertEqual(len(topics), len(set(topics)), '每辆车的话题必须互不相同')
        plugins = [p.get('filename') for p in world.findall('plugin')]
        self.assertIn('gz-sim-user-commands-system', plugins, '传送服务 set_pose 需要 UserCommands 插件')
        yaml = gen_world.bridge_yaml(5)
        self.assertEqual(yaml.count('ros_topic_name'), 1 + 2 * 5)
        self.assertIn('/model/car_5/odometry', yaml)

    def test_center_lines_lie_between_opposite_lanes(self):
        _, w, h, _, grid = gen_world.load_map(MAP)
        segs = gen_world.center_lines(w, h, grid)
        self.assertGreater(len(segs), 0)
        for cx, cy, sx, sy in segs:
            self.assertTrue(0 <= cx <= w and 0 <= cy <= h)
            self.assertTrue(sx == 0.06 or sy == 0.06)


if __name__ == '__main__':
    unittest.main()
