// MiniDiDi 数字世界的 3D 视图（WebGL，不依赖第三方库）。
// 数据与 2D 视图完全相同：GET /api/map 建城市模型，订阅 /api/stream（SSE）的快照驱动车辆、乘客、障碍。
// 坐标：地图 (x 东, y 北) → 三维 (x, 高度, −y)；地图航向 θ 就是模型绕竖直轴的转角。
import { Renderer } from './gl/renderer.js';
import { MeshBuilder } from './gl/geometry.js';
import { identity, perspective, lookAt, trs, project, rgb, mix } from './gl/math.js';
import { carColor } from './map.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const STATUS = {
  CRUISING: '空车巡游', TO_PICKUP: '前往接客', BOARDING: '乘客上车',
  TO_DROPOFF: '送客中', ALIGHTING: '乘客下车',
};
const DRIVER = { INTERNAL: '内部仿真', MATLAB: 'Matlab', GAZEBO: 'Gazebo', PHONE: '手机', GENERIC: '外部设备' };

const ENV = {
  sky: rgb('#f4f8fc'), ground: rgb('#9aa6b2'), fog: rgb('#cfdbe6'), light: [0.45, 0.85, 0.35],
  fogNear: 45, fogFar: 140,
};
const C = {
  plaza: rgb('#dfe5e9'), asphalt: rgb('#5d646c'), cross: rgb('#666d75'), mark: rgb('#e9edf0'),
  curb: rgb('#c8cfd5'), glass: rgb('#55697f'), roofTrim: rgb('#e7eaee'),
  facades: ['#b9c0ca', '#cdbda6', '#a9b9c8', '#c9b3ab', '#adc0b1', '#bfc3cf', '#bdb1c6'].map(rgb),
  amber: rgb('#e09b10'), sensed: rgb('#dd6f12'), danger: rgb('#cf3f36'),
  signFree: rgb('#2fbf5b'), signPickup: rgb('#f0a020'), signBusy: rgb('#e5483d'),
};

let renderer, meshes = {}, map = null, latest = null;
let cityMeshes = [];
const dyn = {};                 // 每帧重建的网格：路线、授权格、雷达扇形、雷达射线
const prev = new Map();         // 平滑插值：上一帧显示的位姿
let cur = new Map(), recvAt = 0, interval = 100;
let selected = null;
const cam = { mode: 'orbit', target: [26, 0, -17], dist: 66, az: -0.55, el: 0.95, lastT: 0 };

// ================================================================ 城市模型

function hash(x, y) {
  let h = (x * 374761393 + y * 668265263) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

/** 由地图建静态模型：地面、道路（车道 / 路口）、标线与箭头、人行道、楼房。 */
function buildCity(m) {
  const W = m.width, H = m.height, R = m.resolution;
  const grid = [];
  for (let y = 0; y < H; y++) grid.push(m.rows[H - 1 - y].split(''));
  const at = (x, y) => (x < 0 || y < 0 || x >= W || y >= H ? '#' : grid[y][x]);
  const X = (x) => x * R, Z = (y) => -y * R;

  const ground = new MeshBuilder();
  ground.flat(X(-12), Z(H + 12), X(W + 12), Z(-12), -0.01, C.plaza);

  const road = new MeshBuilder();
  const marks = new MeshBuilder();
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const ch = grid[y][x];
      if (ch === '#') continue;
      road.flat(X(x), Z(y + 1), X(x + 1), Z(y), 0, ch === '+' ? C.cross : C.asphalt);
      // 对向车道之间的虚线（与 2D 视图相同的规则）
      if (ch === '>' && at(x, y + 1) === '<') marks.flat(X(x + 0.15), Z(y + 1 + 0.025), X(x + 0.65), Z(y + 1 - 0.025), 0.004, C.mark);
      if (ch === '^' && at(x - 1, y) === 'v') marks.flat(X(x - 0.025), Z(y + 0.65), X(x + 0.025), Z(y + 0.15), 0.004, C.mark);
      // 行驶方向箭头
      if ('<>^v'.includes(ch) && (x + y) % 4 === 0) arrow(marks, x + 0.5, y + 0.5, { '>': 0, '^': Math.PI / 2, '<': Math.PI, 'v': -Math.PI / 2 }[ch], R);
    }
  }

  const blocks = new MeshBuilder();
  const seen = Array.from({ length: H }, () => new Array(W).fill(false));
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      if (seen[y][x] || grid[y][x] !== '#') continue;
      // 找出这个连通块的外接矩形（默认地图的建筑块都是矩形）
      let x1 = x, y1 = y;
      while (x1 + 1 < W && grid[y][x1 + 1] === '#' && !seen[y][x1 + 1]) x1++;
      while (y1 + 1 < H && grid[y1 + 1].slice(x, x1 + 1).every((c) => c === '#')) y1++;
      for (let yy = y; yy <= y1; yy++) for (let xx = x; xx <= x1; xx++) seen[yy][xx] = true;
      building(blocks, x, y, x1 + 1, y1 + 1, R);
    }
  }
  return [ground, road, marks, blocks].map((b) => renderer.upload(b.build()));
}

function arrow(b, cx, cy, ang, R) {
  const c = Math.cos(ang), s = Math.sin(ang);
  const P = (u, v) => [(cx + c * u - s * v) * R, -(cy + s * u + c * v) * R];   // 局部 (u 前, v 左) → GL (x, z)
  const pts = [P(0.22, 0), P(-0.14, 0.17), P(-0.06, 0), P(-0.14, -0.17)];
  const y = 0.005, n = [0, 1, 0], col = C.mark;
  const i = b.vertex(pts[0][0], y, pts[0][1], ...n, col);
  for (let k = 1; k < 4; k++) b.vertex(pts[k][0], y, pts[k][1], ...n, col);
  // 箭头是凹四边形：拆成两个三角形，两面都画（法线朝上即可，绕序不同的那个会被剔除，所以按两种绕序各加一次）
  b.idx.push(i, i + 1, i + 2, i, i + 2, i + 3, i, i + 2, i + 1, i, i + 3, i + 2);
}

/** 一栋楼：人行道底座 + 按层叠起来的墙面带和玻璃窗带 + 屋顶。 */
function building(b, gx0, gy0, gx1, gy1, R) {
  const x0 = gx0 * R, x1 = gx1 * R, z0 = -gy1 * R, z1 = -gy0 * R;
  b.box(x0 + 0.03, 0, z0 + 0.03, x1 - 0.03, 0.06, z1 - 0.03, C.curb, C.curb);          // 人行道
  const r = hash(gx0, gy0);
  const facade = C.facades[Math.floor(r * C.facades.length) % C.facades.length];
  const floors = 1 + Math.floor(hash(gx1, gy1) * 4);                                   // 1~4 层，矮一些才看得见街道
  const fh = 0.42, inset = 0.2;
  let y = 0.06;
  for (let f = 0; f < floors; f++) {
    b.box(x0 + inset, y, z0 + inset, x1 - inset, y + 0.27, z1 - inset, facade);        // 墙面带
    b.box(x0 + inset + 0.025, y + 0.27, z0 + inset + 0.025, x1 - inset - 0.025, y + fh, z1 - inset - 0.025,
      mix(C.glass, facade, 0.15));                                                      // 玻璃窗带（略往里缩）
    y += fh;
  }
  b.box(x0 + inset - 0.02, y, z0 + inset - 0.02, x1 - inset + 0.02, y + 0.08, z1 - inset + 0.02, C.roofTrim, mix(facade, [1, 1, 1], 0.45));
  // 屋顶设备
  const w = Math.min(x1 - x0, z1 - z0) * 0.18;
  const cx = (x0 + x1) / 2 + (hash(gx0 + 7, gy0) - 0.5) * (x1 - x0) * 0.4, cz = (z0 + z1) / 2;
  b.box(cx - w, y + 0.08, cz - w * 0.7, cx + w, y + 0.28, cz + w * 0.7, rgb('#aab3bd'), rgb('#c4ccd4'));
}

// ================================================================ 动态物体的单位网格

function buildMeshes() {
  const white = [1, 1, 1], tire = [0.1, 0.1, 0.11], glassC = [0.32, 0.36, 0.42];
  // 出租车：车头朝 +x，车身半径 0.3 m
  const car = new MeshBuilder();
  car.box(-0.27, 0.07, -0.16, 0.27, 0.2, 0.16, white, white);                 // 车身
  car.box(-0.14, 0.2, -0.13, 0.12, 0.31, 0.13, glassC, white);                // 车舱（窗）
  car.box(-0.13, 0.305, -0.12, 0.11, 0.315, 0.12, white, white);              // 车顶
  for (const [wx, wz] of [[0.16, 0.16], [0.16, -0.16], [-0.16, 0.16], [-0.16, -0.16]]) car.wheel(wx, 0.06, wz, 0.06, 0.05, 12, tire);
  meshes.car = renderer.upload(car.build());
  const sign = new MeshBuilder();
  sign.box(-0.05, 0.315, -0.07, 0.03, 0.36, 0.07, white, white);             // 顶灯
  meshes.sign = renderer.upload(sign.build());
  const shadow = new MeshBuilder();
  shadow.disc(0, 0.006, 0, 0.34, 24, [0, 0, 0]);
  meshes.shadow = renderer.upload(shadow.build());

  const person = new MeshBuilder();
  person.cylinder(0, 0, 0, 0.1, 0.08, 0.32, 14, white);
  person.sphere(0, 0.4, 0, 0.075, 12, white);
  meshes.person = renderer.upload(person.build());

  const cone = new MeshBuilder();
  cone.box(-0.24, 0, -0.24, 0.24, 0.04, 0.24, rgb('#2b2f33'));
  cone.cylinder(0, 0.04, 0, 0.19, 0.12, 0.18, 18, C.sensed, false);
  cone.cylinder(0, 0.22, 0, 0.12, 0.095, 0.07, 18, white, false);
  cone.cylinder(0, 0.29, 0, 0.095, 0.0005, 0.26, 18, C.sensed, false);
  meshes.cone = renderer.upload(cone.build());
  const ghost = new MeshBuilder();
  ghost.cylinder(0, 0.02, 0, 0.22, 0.0005, 0.5, 18, white, false);
  meshes.ghost = renderer.upload(ghost.build());

  const barrier = new MeshBuilder();
  for (const px of [-0.36, 0.36]) barrier.box(px - 0.03, 0, -0.03, px + 0.03, 0.34, 0.03, rgb('#3a3f45'));
  for (let i = 0; i < 4; i++) {
    const a = -0.42 + i * 0.21;
    barrier.box(a, 0.18, -0.025, a + 0.21, 0.3, 0.025, i % 2 ? white : C.danger);
  }
  meshes.barrier = renderer.upload(barrier.build());

  const pole = new MeshBuilder();
  pole.cylinder(0, 0, 0, 0.018, 0.018, 0.62, 8, rgb('#30353b'));
  meshes.pole = renderer.upload(pole.build());
  const cloth = new MeshBuilder();
  cloth.quad([0.018, 0.62, 0], [0.3, 0.52, 0], [0.018, 0.42, 0], [0.018, 0.42, 0], [0, 0, 1], white);
  meshes.flag = renderer.upload(cloth.build());

  const ring = new MeshBuilder();
  ring.ring(0, 0.008, 0, 0.9, 1, 48, white);
  meshes.ring = renderer.upload(ring.build());
  const disc = new MeshBuilder();
  disc.disc(0, 0.007, 0, 1, 48, white);
  meshes.disc = renderer.upload(disc.build());

  for (const k of ['routes', 'tiles', 'fans', 'rays']) dyn[k] = renderer.upload(new MeshBuilder().build(), true);
  dyn.rays.mode = renderer.gl.LINES;
}

// ================================================================ 每帧

function displayPose(a, now) {
  const p = prev.get(a.id);
  if (!p) return { x: a.x, y: a.y, theta: a.theta };
  const t = clamp((now - recvAt) / interval, 0, 1);
  if (Math.hypot(a.x - p.x, a.y - p.y) > 3) return { x: a.x, y: a.y, theta: a.theta };
  let d = a.theta - p.theta;
  while (d > Math.PI) d -= 2 * Math.PI;
  while (d < -Math.PI) d += 2 * Math.PI;
  return { x: p.x + (a.x - p.x) * t, y: p.y + (a.y - p.y) * t, theta: p.theta + d * t };
}

function onSnapshot(s) {
  const now = performance.now();
  if (recvAt) interval = clamp(interval * 0.8 + (now - recvAt) * 0.2, 40, 500);
  const shown = new Map();
  for (const a of cur.values()) shown.set(a.id, displayPose(a, now));
  prev.clear();
  for (const [k, v] of shown) prev.set(k, v);
  cur = new Map(s.agents.map((a) => [a.id, a]));
  recvAt = now;
  latest = s;
  if (selected && !cur.has(selected)) selected = null;
}

function updateCamera(dt, poses) {
  if (cam.mode === 'tour') {
    cam.az += dt * 0.07;
    cam.target = lerp3(cam.target, [map.width / 2, 0, -map.height / 2], dt * 1.5);
    cam.dist += (50 - cam.dist) * Math.min(1, dt * 1.5);
    cam.el += (0.7 - cam.el) * Math.min(1, dt * 1.5);
  } else if (cam.mode === 'top') {
    cam.el += (1.48 - cam.el) * Math.min(1, dt * 4);
    cam.az += angleDiff(0, cam.az) * Math.min(1, dt * 4);
  } else if (cam.mode === 'follow') {
    const p = selected && poses.get(selected);
    if (!p) {
      if (latest) setCamMode('orbit');   // 还没收到快照时先等着；车没了才退出跟随
    } else {
      cam.target = lerp3(cam.target, [p.x, 0.25, -p.y], Math.min(1, dt * 6));
      const want = Math.atan2(-Math.cos(p.theta), Math.sin(p.theta));   // 站在车后面看
      cam.az += angleDiff(want, cam.az) * Math.min(1, dt * 2.5);
      // 相机高约 2.7 m，比最高的楼（约 2 m）还高，转弯时也不会钻进楼里
      cam.dist += (4.2 - cam.dist) * Math.min(1, dt * 2);
      cam.el += (0.72 - cam.el) * Math.min(1, dt * 2);
    }
  }
  const ce = Math.cos(cam.el);
  return [cam.target[0] + cam.dist * ce * Math.sin(cam.az), cam.target[1] + cam.dist * Math.sin(cam.el),
    cam.target[2] + cam.dist * ce * Math.cos(cam.az)];
}

function angleDiff(to, from) {
  let d = to - from;
  while (d > Math.PI) d -= 2 * Math.PI;
  while (d < -Math.PI) d += 2 * Math.PI;
  return d;
}

function lerp3(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

const M = identity();
let viewProj = null;

function frame(t) {
  requestAnimationFrame(frame);
  if (!map || !renderer) return;
  const dt = cam.lastT ? Math.min(0.1, (t - cam.lastT) / 1000) : 0.016;
  cam.lastT = t;
  const [w, h] = renderer.resize();
  const now = performance.now();
  const s = latest;
  const poses = new Map();
  if (s) for (const a of s.agents) poses.set(a.id, displayPose(a, now));

  const eye = updateCamera(dt, poses);
  const view = lookAt(eye, cam.target, [0, 1, 0]);
  const proj = perspective(cam.mode === 'follow' ? 0.95 : 0.75, w / Math.max(1, h), 0.1, 400);
  renderer.begin(view, proj, eye, ENV);
  viewProj = renderer.viewProj;
  for (const mesh of cityMeshes) renderer.draw(mesh, identity());
  if (s) drawWorld(s, poses, t);
  renderer.end();
  updateLabels(s, poses, w, h);
}

function drawWorld(s, poses, t) {
  const R = map.resolution;
  // ---- 地面上的标记：需求热点、授权格、路线、雷达
  for (const hs of s.hotspots || []) {
    const x = (hs.x + 0.5) * R, z = -(hs.y + 0.5) * R;
    renderer.draw(meshes.disc, trs(x, 0, z, 0, hs.radius, 1, hs.radius), [...rgb('#8a4fc8'), 0.12], { unlit: true, noDepthWrite: true });
    renderer.draw(meshes.ring, trs(x, 0.001, z, 0, hs.radius, 1, hs.radius), [...rgb('#8a4fc8'), 0.7], { unlit: true, noDepthWrite: true });
  }
  const tiles = new MeshBuilder(), routes = new MeshBuilder(), fans = new MeshBuilder(), rays = new MeshBuilder();
  for (const a of s.agents) {
    const col = rgb(carColor(a.num));
    const p = poses.get(a.id);
    if (a.held) {
      for (let i = 0; i < a.held.length; i += 2) {
        const x = a.held[i] * R, y = a.held[i + 1] * R;
        tiles.flat(x + 0.06, -(y + R) + 0.06, x + R - 0.06, -y - 0.06, 0.01, col);
      }
    }
    if (a.route && a.route.length >= 4) {
      const pts = [[p.x, -p.y]];
      for (let i = 2; i < a.route.length; i += 2) pts.push([a.route[i], -a.route[i + 1]]);
      routes.ribbon(pts, 0.014, a.status === 'TO_DROPOFF' ? 0.09 : 0.05, a.status === 'TO_DROPOFF' ? col : mix(col, [1, 1, 1], 0.35));
    }
    if (a.scan) {
      const ring = [];
      const n = a.scan.length;
      for (let k = 0; k < n; k++) {
        const ang = p.theta - Math.PI + (k * 2 * Math.PI) / n, r = a.scan[k] / 10;
        ring.push([p.x + Math.cos(ang) * r, -(p.y + Math.sin(ang) * r)]);
      }
      fans.fan(p.x, 0.02, -p.y, ring, mix(col, [1, 1, 1], 0.25));
      if (a.id === selected) {
        for (const [x, z] of ring) {
          rays.vertex(p.x, 0.12, -p.y, 0, 1, 0, col);
          const i = rays.vertex(x, 0.12, z, 0, 1, 0, col);
          rays.idx.push(i - 1, i);
        }
      }
    }
  }
  renderer.update(dyn.tiles, tiles.build());
  renderer.update(dyn.routes, routes.build());
  renderer.update(dyn.fans, fans.build());
  renderer.update(dyn.rays, rays.build());
  renderer.draw(dyn.routes, identity(), [1, 1, 1, 1], { unlit: true });
  renderer.draw(dyn.tiles, identity(), [1, 1, 1, 0.3], { unlit: true, noDepthWrite: true });
  renderer.draw(dyn.fans, identity(), [1, 1, 1, 0.16], { unlit: true, noDepthWrite: true });
  renderer.draw(dyn.rays, identity(), [1, 1, 1, 0.55], { unlit: true, noDepthWrite: true });

  // ---- 障碍
  const blocked = s.blocked || [];
  for (let i = 0; i < blocked.length; i += 2) {
    renderer.draw(meshes.barrier, trs((blocked[i] + 0.5) * R, 0, -(blocked[i + 1] + 0.5) * R, blockYaw(blocked[i], blocked[i + 1])));
  }
  const disc = s.discovered || [];
  for (let i = 0; i < disc.length; i += 2) renderer.draw(meshes.cone, trs((disc[i] + 0.5) * R, 0, -(disc[i + 1] + 0.5) * R));
  const hid = s.hidden || [];
  const pulse = 0.3 + 0.15 * Math.sin(t / 300);
  for (let i = 0; i < hid.length; i += 2) {
    renderer.draw(meshes.ghost, trs((hid[i] + 0.5) * R, 0, -(hid[i + 1] + 0.5) * R), [0.35, 0.38, 0.42, pulse], { noDepthWrite: true });
  }

  // ---- 乘客与目的地
  const num = new Map(s.agents.map((a) => [a.id, a.num]));
  for (const task of s.tasks) {
    if (task.state === 'PICKED') {
      const x = (task.to[0] + 0.5) * R, z = -(task.to[1] + 0.5) * R;
      renderer.draw(meshes.pole, trs(x, 0, z));
      renderer.draw(meshes.flag, trs(x, 0, z), [...rgb(carColor(num.get(task.agent) || 1)), 1], { doubleSided: true });
      continue;
    }
    const x = (task.from[0] + 0.5) * R, z = -(task.from[1] + 0.5) * R;
    renderer.draw(meshes.person, trs(x, 0, z, 0, 0.8, 0.8, 0.8), [...C.amber, 1]);
    if (task.state === 'WAITING') {
      const k = (t / 1400) % 1;
      renderer.draw(meshes.ring, trs(x, 0.002, z, 0, 0.25 + k * 0.35, 1, 0.25 + k * 0.35), [...C.amber, 1 - k], { unlit: true, noDepthWrite: true });
    }
  }

  // ---- 车辆
  for (const a of s.agents) {
    const p = poses.get(a.id);
    const col = rgb(carColor(a.num));
    const m = trs(p.x, 0, -p.y, p.theta);
    renderer.draw(meshes.shadow, m, [0, 0, 0, 0.22], { unlit: true, noDepthWrite: true });
    renderer.draw(meshes.car, m, [...col, 1]);
    const sign = a.status === 'TO_DROPOFF' || a.status === 'BOARDING' ? C.signBusy : a.status === 'TO_PICKUP' ? C.signPickup : C.signFree;
    renderer.draw(meshes.sign, m, [...sign, 1], { unlit: true });
    if (a.id === selected) renderer.draw(meshes.ring, trs(p.x, 0.004, -p.y, 0, 0.45, 1, 0.45), [...col, 0.9], { unlit: true, noDepthWrite: true });
  }
}

/** 路障横在路上：东西向车道上南北向摆放，反之亦然。 */
function blockYaw(x, y) {
  const ch = map.grid[y]?.[x];
  return ch === '>' || ch === '<' ? Math.PI / 2 : 0;
}

// ================================================================ 文字标签与信息卡

const labelEls = new Map();

function updateLabels(s, poses, w, h) {
  const box = $('labels');
  const live = new Set();
  if (s) {
    const dpr = renderer.canvas.width / Math.max(1, renderer.canvas.clientWidth);
    for (const a of s.agents) {
      const p = poses.get(a.id);
      const sp = project(viewProj, p.x, 0.55, -p.y, w, h);
      let el = labelEls.get(a.id);
      if (!sp || sp[0] < -40 || sp[1] < -40 || sp[0] > w + 40 || sp[1] > h + 40) {
        if (el) el.style.display = 'none';
        live.add(a.id);
        continue;
      }
      if (!el) {
        el = document.createElement('div');
        el.className = 'lb';
        box.appendChild(el);
        labelEls.set(a.id, el);
      }
      el.style.display = '';
      const text = String(a.num) + (a.driver !== 'INTERNAL' ? ' ' + ({ MATLAB: 'M', GAZEBO: 'G', PHONE: 'P', GENERIC: 'E' }[a.driver] || '') : '');
      if (el.textContent !== text) el.textContent = text;
      el.style.background = carColor(a.num);
      el.style.left = sp[0] / dpr + 'px';
      el.style.top = sp[1] / dpr + 'px';
      live.add(a.id);
    }
  }
  for (const [id, el] of labelEls) {
    if (!live.has(id)) {
      el.remove();
      labelEls.delete(id);
    }
  }
  renderCard(s);
}

let cardTimer = 0;

function renderCard(s) {
  const now = performance.now();
  if (now - cardTimer < 250) return;
  cardTimer = now;
  const card = $('carCard');
  const a = s && selected ? s.agents.find((x) => x.id === selected) : null;
  if (!a) {
    card.hidden = true;
    return;
  }
  card.hidden = false;
  const lines = [`<span class="swatch" style="background:${carColor(a.num)}"></span><b>${esc(a.id)}</b>　${esc(STATUS[a.status] || a.status)}`,
    `驱动：${esc(DRIVER[a.driver] || a.driver)}${a.device ? '（' + esc(a.device) + '）' : ''}`,
    `速度 ${a.v.toFixed(2)} m/s，位置 (${a.x.toFixed(1)}, ${a.y.toFixed(1)})`];
  if (a.taskId) lines.push(`订单 ${esc(a.taskId)}`);
  if (a.seen) lines.push(`雷达看到：车 ${a.seen[0]}${a.seen[1] ? `，未知障碍 ${a.seen[1]} 处` : ''}`);
  if (a.zone) lines.push(`空车调度：在区域 ${esc(a.zone)} 待命`);
  if (a.blocked) lines.push('<span style="color:var(--danger)">前方受阻，等待通行</span>');
  card.innerHTML = lines.join('<br>');
}

// ================================================================ 场景脚本横幅（与 2D 相同）

let bannerText = null;

function renderTop(s) {
  const t = Math.floor(s.t), m = Math.floor(t / 60), sec = t % 60;
  $('simClock').textContent = `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  const sc = s.scenario;
  $('scenarioStrip').hidden = !sc;
  const banner = $('scBanner');
  if (!sc) {
    banner.hidden = true;
    bannerText = null;
    return;
  }
  $('scName').textContent = `场景：${sc.name}`;
  $('scLoop').textContent = `第 ${sc.loop} 轮`;
  $('scNext').textContent = sc.next != null ? `下一步（${Math.ceil(sc.nextIn)} s 后）：${sc.next}` : '';
  $('scBar').style.width = `${Math.round((sc.steps ? sc.step / sc.steps : 1) * 100)}%`;
  if (sc.banner && sc.banner !== bannerText) {
    banner.innerHTML = `<small>场景事件 · 自动触发</small>${esc(sc.banner)}`;
    banner.hidden = false;
    bannerText = sc.banner;
  } else if (!sc.banner && bannerText) {
    banner.hidden = true;
    bannerText = null;
  }
}

// ================================================================ 交互：旋转 / 平移 / 缩放 / 选车

function setCamMode(mode) {
  if (mode === 'follow' && !selected) {
    const first = latest?.agents[0];
    if (first) selected = first.id;
  }
  cam.mode = mode;
  for (const b of document.querySelectorAll('.camera button')) b.setAttribute('aria-pressed', b.dataset.cam === mode ? 'true' : 'false');
}

function initControls() {
  const canvas = $('gl');
  let drag = null;
  canvas.addEventListener('contextmenu', (e) => e.preventDefault());
  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    drag = { x: e.clientX, y: e.clientY, moved: 0, pan: e.button === 2 || e.shiftKey };
    canvas.classList.add('dragging');
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    drag.x = e.clientX;
    drag.y = e.clientY;
    drag.moved += Math.abs(dx) + Math.abs(dy);
    if (drag.moved < 4) return;
    if (cam.mode === 'tour' || cam.mode === 'top' || cam.mode === 'follow') setCamMode('orbit');
    if (drag.pan) {
      const k = cam.dist * 0.0016;
      const sa = Math.sin(cam.az), ca = Math.cos(cam.az);
      // 屏幕右方 = (cos az, −sin az)，屏幕上方对应地面上的"前方" = (−sin az, −cos az)
      cam.target[0] -= (dx * ca + dy * sa) * k;
      cam.target[2] -= (-dx * sa + dy * ca) * k;
    } else {
      cam.az -= dx * 0.006;
      cam.el = clamp(cam.el + dy * 0.005, 0.12, 1.52);
    }
  });
  canvas.addEventListener('pointerup', (e) => {
    canvas.classList.remove('dragging');
    if (drag && drag.moved < 4) pick(e);
    drag = null;
  });
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    cam.dist = clamp(cam.dist * Math.pow(1.0015, e.deltaY), 2.5, 120);
    if (cam.mode === 'tour') setCamMode('orbit');
  }, { passive: false });
  for (const b of document.querySelectorAll('.camera button')) b.addEventListener('click', () => setCamMode(b.dataset.cam));
}

function pick(e) {
  if (!latest || !viewProj) return;
  const r = renderer.canvas.getBoundingClientRect();
  const dpr = renderer.canvas.width / Math.max(1, r.width);
  const px = (e.clientX - r.left) * dpr, py = (e.clientY - r.top) * dpr;
  const now = performance.now();
  let best = null, bestD = 30 * dpr;
  for (const a of latest.agents) {
    const p = displayPose(a, now);
    const sp = project(viewProj, p.x, 0.2, -p.y, renderer.canvas.width, renderer.canvas.height);
    if (!sp) continue;
    const d = Math.hypot(sp[0] - px, sp[1] - py);
    if (d < bestD) {
      bestD = d;
      best = a;
    }
  }
  selected = best ? best.id : null;
  if (!best && cam.mode === 'follow') setCamMode('orbit');
}

// ================================================================ 启动

async function start() {
  try {
    renderer = new Renderer($('gl'));
  } catch (err) {
    $('glError').hidden = false;
    $('glError').textContent = '无法启动 3D 视图：' + err.message + '。请使用最新版的 Chrome / Edge / Firefox，或打开 2D 视图。';
    return;
  }
  buildMeshes();
  initControls();
  let m;
  try {
    m = await (await fetch('/api/map')).json();
  } catch (err) {
    setTimeout(start, 2000);
    return;
  }
  map = m;
  map.grid = [];
  for (let y = 0; y < m.height; y++) map.grid.push(m.rows[m.height - 1 - y].split(''));
  cam.target = [m.width * m.resolution / 2, 0, -m.height * m.resolution / 2 + 3];
  cityMeshes = buildCity(m);
  const q = new URLSearchParams(location.search);
  if (q.get('car')) selected = q.get('car');
  if (q.get('mode')) setCamMode(q.get('mode'));
  requestAnimationFrame(frame);

  const es = new EventSource('/api/stream');
  const conn = $('conn');
  es.onopen = () => { conn.dataset.state = 'live'; conn.textContent = '实时'; };
  es.onerror = () => { conn.dataset.state = 'lost'; conn.textContent = '连接中断，正在重连'; };
  let topTimer = 0;
  es.onmessage = (e) => {
    const s = JSON.parse(e.data);
    if (s.type !== 'snapshot') return;
    onSnapshot(s);
    conn.dataset.state = 'live';
    conn.textContent = s.paused ? '已暂停' : '实时';
    const now = performance.now();
    if (now - topTimer > 250) {
      topTimer = now;
      renderTop(s);
    }
  };
}

start();
