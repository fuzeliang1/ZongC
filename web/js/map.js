// 城市地图的 Canvas 渲染：静态层（道路、建筑、车道标线）离屏绘制一次；
// 动态层（行驶授权、路线、乘客、车辆）每帧重绘，车辆位置在两次快照之间插值，动画更平滑。

export const CAR_COLORS = [
  '#2f6bd8', '#e0533d', '#12a189', '#8a4fc8', '#d2418a', '#1e9bd7',
  '#7a9a1c', '#a0652b', '#4c55c9', '#3e9e3e', '#b8323a', '#2c8c9e',
];

export function carColor(num) {
  return CAR_COLORS[(Math.max(1, num) - 1) % CAR_COLORS.length];
}

const PHONE_COLOR = '#7b5cd6';
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

function lerpAngle(a, b, t) {
  let d = b - a;
  while (d > Math.PI) d -= 2 * Math.PI;
  while (d < -Math.PI) d += 2 * Math.PI;
  return a + d * t;
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export class MapView {
  constructor(canvas, handlers = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.handlers = handlers;          // { onSelect(id), onCellClick(x, y, event), onHover(agent, px, py) }
    this.grid = null;                  // grid[y][x]，y 向上
    this.W = 0; this.H = 0; this.res = 1;
    this.staticLayer = document.createElement('canvas');
    this.snapshot = null;
    this.prev = new Map();             // id -> 上一帧显示位姿
    this.cur = new Map();              // id -> 最新快照里的智能体
    this.recvAt = 0;
    this.interval = 100;
    this.selected = null;
    this.hoverId = null;
    this.pendingCell = null;           // 手动下单时已选的上车点
    // 可开关的图层：雷达扫描、真实世界（上帝视角：系统还不知道的障碍）、需求热力图
    this.layers = { scan: true, truth: true, demand: true };

    new ResizeObserver(() => this.layout()).observe(canvas);
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => this.renderStatic());
    canvas.addEventListener('mousemove', (e) => this.onMove(e));
    canvas.addEventListener('mouseleave', () => { this.hoverId = null; this.handlers.onHover?.(null); });
    canvas.addEventListener('click', (e) => this.onClick(e));
    requestAnimationFrame((t) => this.frame(t));
  }

  // ------------------------------------------------------------ 数据

  setMap(m) {
    this.W = m.width; this.H = m.height; this.res = m.resolution;
    this.grid = [];
    for (let y = 0; y < this.H; y++) this.grid.push(m.rows[this.H - 1 - y].split(''));
    this.layout();
  }

  setSnapshot(s) {
    const now = performance.now();
    if (this.recvAt) this.interval = clamp(this.interval * 0.8 + (now - this.recvAt) * 0.2, 40, 500);
    // 以"此刻正在显示的位置"作为新的起点，避免跳变
    const shown = new Map();
    for (const a of this.cur.values()) shown.set(a.id, this.displayPose(a, now));
    this.prev = shown;
    this.cur = new Map(s.agents.map((a) => [a.id, a]));
    this.recvAt = now;
    this.snapshot = s;
    if (this.selected && !this.cur.has(this.selected)) this.selected = null;
  }

  displayPose(a, now) {
    const p = this.prev.get(a.id);
    if (!p) return { x: a.x, y: a.y, theta: a.theta };
    const t = clamp((now - this.recvAt) / this.interval, 0, 1);
    if (Math.hypot(a.x - p.x, a.y - p.y) > 3) return { x: a.x, y: a.y, theta: a.theta };   // 跳变不插值
    return { x: p.x + (a.x - p.x) * t, y: p.y + (a.y - p.y) * t, theta: lerpAngle(p.theta, a.theta, t) };
  }

  select(id) {
    this.selected = id;
  }

  // ------------------------------------------------------------ 坐标

  layout() {
    const dpr = window.devicePixelRatio || 1;
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    if (!w || !h) return;
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.dpr = dpr;
    if (!this.grid) return;
    const padL = 34, padR = 20, padT = 20, padB = 54;
    const worldW = this.W * this.res, worldH = this.H * this.res;
    this.scale = Math.min((w - padL - padR) / worldW, (h - padT - padB) / worldH);
    this.ox = padL + ((w - padL - padR) - worldW * this.scale) / 2;
    this.oy = padT + ((h - padT - padB) - worldH * this.scale) / 2;
    this.renderStatic();
  }

  sx(x) { return this.ox + x * this.scale; }
  sy(y) { return this.oy + (this.H * this.res - y) * this.scale; }
  wx(px) { return (px - this.ox) / this.scale; }
  wy(py) { return this.H * this.res - (py - this.oy) / this.scale; }

  cellAtPx(px, py) {
    const x = Math.floor(this.wx(px) / this.res), y = Math.floor(this.wy(py) / this.res);
    if (x < 0 || y < 0 || x >= this.W || y >= this.H) return null;
    return { x, y, ch: this.grid[y][x] };
  }

  // ------------------------------------------------------------ 静态层

  renderStatic() {
    if (!this.grid || !this.scale) return;
    const c = this.staticLayer;
    c.width = this.canvas.width;
    c.height = this.canvas.height;
    const g = c.getContext('2d');
    g.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    const S = this.scale * this.res;
    const col = this.col = {
      paper: cssVar('--paper'), road: cssVar('--road'), lane: cssVar('--lane-mark'),
      block: cssVar('--block'), top: cssVar('--block-top'), edge: cssVar('--block-edge'), muted: cssVar('--muted'),
      ink: cssVar('--ink'), panel: cssVar('--panel'), signal: cssVar('--signal'), danger: cssVar('--danger'),
      sensed: cssVar('--sensed'), demand: cssVar('--demand'), font: cssVar('--font'),
    };

    g.fillStyle = col.paper;
    g.fillRect(0, 0, c.width, c.height);
    g.fillStyle = col.road;
    g.fillRect(this.sx(0), this.sy(this.H * this.res), this.W * S, this.H * S);

    // 建筑：按连通块画成"缩微模型"——深色侧面 + 浅色顶面
    const seen = Array.from({ length: this.H }, () => new Array(this.W).fill(false));
    for (let y = 0; y < this.H; y++) {
      for (let x = 0; x < this.W; x++) {
        if (seen[y][x] || this.grid[y][x] !== '#') continue;
        const cells = this.flood(x, y, seen);
        let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
        for (const [cx, cy] of cells) { x0 = Math.min(x0, cx); y0 = Math.min(y0, cy); x1 = Math.max(x1, cx); y1 = Math.max(y1, cy); }
        const rect = cells.length === (x1 - x0 + 1) * (y1 - y0 + 1);
        if (rect) this.drawBlock(g, x0, y0, x1, y1, S, col);
        else for (const [cx, cy] of cells) this.drawBlock(g, cx, cy, cx, cy, S, col);
      }
    }

    // 道路区域的轮廓（需求热力图只染在道路上，不盖住建筑）
    this.roadPath = new Path2D();
    for (let y = 0; y < this.H; y++) {
      for (let x = 0; x < this.W; x++) {
        if (this.grid[y][x] !== '#') this.roadPath.rect(this.sx(x * this.res), this.sy((y + 1) * this.res), S, S);
      }
    }

    // 对向车道之间的虚线 + 行驶方向箭头
    g.strokeStyle = col.lane;
    g.lineWidth = Math.max(1, S * 0.05);
    g.setLineDash([S * 0.35, S * 0.3]);
    g.beginPath();
    for (let y = 0; y < this.H; y++) {
      for (let x = 0; x < this.W; x++) {
        const ch = this.grid[y][x];
        if (ch === '>' && y + 1 < this.H && this.grid[y + 1][x] === '<') {
          g.moveTo(this.sx(x * this.res), this.sy((y + 1) * this.res));
          g.lineTo(this.sx((x + 1) * this.res), this.sy((y + 1) * this.res));
        }
        if (ch === '^' && x - 1 >= 0 && this.grid[y][x - 1] === 'v') {
          g.moveTo(this.sx(x * this.res), this.sy(y * this.res));
          g.lineTo(this.sx(x * this.res), this.sy((y + 1) * this.res));
        }
      }
    }
    g.stroke();
    g.setLineDash([]);

    const dirs = { '>': 0, '^': Math.PI / 2, '<': Math.PI, 'v': -Math.PI / 2 };
    g.fillStyle = col.lane;
    for (let y = 0; y < this.H; y++) {
      for (let x = 0; x < this.W; x++) {
        const ch = this.grid[y][x];
        if (!(ch in dirs) || (x + y) % 4 !== 0) continue;
        this.chevron(g, this.sx((x + 0.5) * this.res), this.sy((y + 0.5) * this.res), dirs[ch], S * 0.22);
      }
    }

    // 坐标刻度（每 5 格），方便对照日志里的格子坐标
    g.fillStyle = col.muted;
    g.font = '10px ' + col.font;
    g.textAlign = 'center';
    g.textBaseline = 'top';
    for (let x = 0; x <= this.W; x += 5) g.fillText(String(x), this.sx((x + 0.5) * this.res), this.sy(0) + 4);
    g.textAlign = 'right';
    g.textBaseline = 'middle';
    for (let y = 0; y <= this.H; y += 5) g.fillText(String(y), this.sx(0) - 5, this.sy((y + 0.5) * this.res));
  }

  flood(x, y, seen) {
    const out = [];
    const stack = [[x, y]];
    seen[y][x] = true;
    while (stack.length) {
      const [cx, cy] = stack.pop();
      out.push([cx, cy]);
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const nx = cx + dx, ny = cy + dy;
        if (nx < 0 || ny < 0 || nx >= this.W || ny >= this.H || seen[ny][nx] || this.grid[ny][nx] !== '#') continue;
        seen[ny][nx] = true;
        stack.push([nx, ny]);
      }
    }
    return out;
  }

  drawBlock(g, x0, y0, x1, y1, S, col) {
    const inset = S * 0.12;
    const left = this.sx(x0 * this.res) + inset, top = this.sy((y1 + 1) * this.res) + inset;
    const w = (x1 - x0 + 1) * S - inset * 2, h = (y1 - y0 + 1) * S - inset * 2;
    const depth = Math.min(S * 0.22, 6);
    const r = Math.min(S * 0.25, 6);
    g.fillStyle = col.edge;
    this.roundRect(g, left, top + depth, w, h - depth, r);
    g.fill();
    g.fillStyle = col.block;
    this.roundRect(g, left, top, w, h - depth, r);
    g.fill();
    g.fillStyle = col.top;
    this.roundRect(g, left + depth * 0.6, top + depth * 0.6, w - depth * 1.2, h - depth * 2.2, Math.max(1, r - 2));
    g.fill();
  }

  roundRect(g, x, y, w, h, r) {
    g.beginPath();
    if (w <= 0 || h <= 0) return;
    r = Math.min(r, w / 2, h / 2);
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  }

  chevron(g, cx, cy, ang, size) {
    // 屏幕坐标 y 向下，所以角度取反
    const a = -ang;
    g.save();
    g.translate(cx, cy);
    g.rotate(a);
    g.beginPath();
    g.moveTo(size * 0.6, 0);
    g.lineTo(-size * 0.4, -size * 0.55);
    g.lineTo(-size * 0.1, 0);
    g.lineTo(-size * 0.4, size * 0.55);
    g.closePath();
    g.fill();
    g.restore();
  }

  // ------------------------------------------------------------ 动态层

  frame(t) {
    requestAnimationFrame((tt) => this.frame(tt));
    const g = this.ctx;
    if (!this.grid || !this.scale) return;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.drawImage(this.staticLayer, 0, 0);
    g.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    const s = this.snapshot;
    if (!s) return;
    const now = performance.now();
    const S = this.scale * this.res;
    const poses = new Map();
    for (const a of s.agents) poses.set(a.id, this.displayPose(a, now));

    if (this.layers.demand && (s.hotspots || s.zones?.on)) {
      this.drawZones(g, s.zones, S);
      this.drawHotspots(g, s.hotspots || [], S);
    }
    this.drawBlocked(g, s.blocked || [], S);
    this.drawPerception(g, s, S);
    for (const a of s.agents) if (a.taxi && a.held) this.drawCarpet(g, a, S);
    for (const a of s.agents) if (a.taxi && a.route) this.drawRoute(g, a, poses.get(a.id), S);
    if (this.layers.scan) for (const a of s.agents) if (a.scan) this.drawScan(g, a, poses.get(a.id), S);
    this.drawTasks(g, s, S, t);
    if (this.pendingCell) this.drawPending(g, S);
    for (const a of s.agents) {
      const p = poses.get(a.id);
      if (a.taxi) this.drawCar(g, a, p, S);
      else this.drawPhone(g, a, p, S);
    }
  }

  drawBlocked(g, cells, S) {
    const danger = this.col.danger;
    for (let i = 0; i < cells.length; i += 2) {
      const x = this.sx(cells[i] * this.res), y = this.sy((cells[i + 1] + 1) * this.res);
      g.fillStyle = danger + '33';
      g.fillRect(x, y, S, S);
      g.strokeStyle = danger;
      g.lineWidth = Math.max(1.5, S * 0.08);
      g.beginPath();
      g.moveTo(x + S * 0.25, y + S * 0.25); g.lineTo(x + S * 0.75, y + S * 0.75);
      g.moveTo(x + S * 0.75, y + S * 0.25); g.lineTo(x + S * 0.25, y + S * 0.75);
      g.stroke();
    }
  }

  // ------------------------------------------------------------ 感知与建图

  /** 感知图层：车辆发现并写进地图的障碍（橙色锥桶）、疑似障碍（淡橙）、真实存在但系统还不知道的障碍（虚线问号）。 */
  drawPerception(g, s, S) {
    const sensed = this.col.sensed;
    const sus = s.suspect || [];
    for (let i = 0; i < sus.length; i += 2) {
      const x = this.sx(sus[i] * this.res), y = this.sy((sus[i + 1] + 1) * this.res);
      g.fillStyle = sensed + '26';
      g.fillRect(x + 1, y + 1, S - 2, S - 2);
      g.strokeStyle = sensed + 'aa';
      g.lineWidth = 1;
      g.setLineDash([2, 2]);
      g.strokeRect(x + 1.5, y + 1.5, S - 3, S - 3);
      g.setLineDash([]);
    }
    const dis = s.discovered || [];
    for (let i = 0; i < dis.length; i += 2) {
      const x = this.sx(dis[i] * this.res), y = this.sy((dis[i + 1] + 1) * this.res);
      g.fillStyle = sensed + '30';
      g.fillRect(x, y, S, S);
      this.cone(g, x + S / 2, y + S / 2, S, sensed, false);
    }
    if (!this.layers.truth) return;
    const hid = s.hidden || [];
    for (let i = 0; i < hid.length; i += 2) {
      const x = this.sx(hid[i] * this.res), y = this.sy((hid[i + 1] + 1) * this.res);
      g.strokeStyle = this.col.ink + '99';
      g.lineWidth = 1.2;
      g.setLineDash([3, 2]);
      g.strokeRect(x + 1.5, y + 1.5, S - 3, S - 3);
      g.setLineDash([]);
      this.cone(g, x + S / 2, y + S / 2, S, this.col.ink + '88', true);
    }
  }

  /** 锥桶图标；ghost = true 时只画轮廓并标"?"（系统还不知道）。 */
  cone(g, cx, cy, S, color, ghost) {
    const h = S * 0.62, w = S * 0.52;
    g.beginPath();
    g.moveTo(cx, cy - h / 2);
    g.lineTo(cx + w / 2, cy + h / 2);
    g.lineTo(cx - w / 2, cy + h / 2);
    g.closePath();
    if (ghost) {
      g.strokeStyle = color;
      g.lineWidth = 1.2;
      g.stroke();
      g.fillStyle = color;
      g.font = `700 ${Math.max(8, S * 0.36)}px ${this.col.font}`;
      g.textAlign = 'center';
      g.textBaseline = 'middle';
      g.fillText('?', cx, cy + h * 0.12);
      return;
    }
    g.fillStyle = color;
    g.fill();
    g.fillStyle = 'rgba(255,255,255,0.9)';
    g.fillRect(cx - w * 0.22, cy + h * 0.02, w * 0.44, Math.max(1.5, h * 0.12));
  }

  /**
   * 激光雷达扫描：48 束射线的端点连成"可见区域"，所有车都画淡淡的一层；
   * 选中的车再画出每一束射线和命中点。射线 k 的角度 = θ − π + k·2π/48，距离单位分米。
   */
  drawScan(g, a, p, S) {
    const n = a.scan.length;
    if (!n) return;
    const color = carColor(a.num);
    const selected = this.selected === a.id;
    const pts = [];
    for (let k = 0; k < n; k++) {
      const ang = p.theta - Math.PI + (k * 2 * Math.PI) / n;
      const r = a.scan[k] / 10;
      pts.push([p.x + Math.cos(ang) * r, p.y + Math.sin(ang) * r, r]);
    }
    g.fillStyle = color + (selected ? '22' : '12');
    g.beginPath();
    pts.forEach(([x, y], i) => (i ? g.lineTo(this.sx(x), this.sy(y)) : g.moveTo(this.sx(x), this.sy(y))));
    g.closePath();
    g.fill();
    if (!selected) return;
    g.strokeStyle = color + '55';
    g.lineWidth = 1;
    g.beginPath();
    for (const [x, y] of pts) {
      g.moveTo(this.sx(p.x), this.sy(p.y));
      g.lineTo(this.sx(x), this.sy(y));
    }
    g.stroke();
    g.fillStyle = color;
    for (const [x, y, r] of pts) {
      if (r >= 2.95) continue;
      g.beginPath();
      g.arc(this.sx(x), this.sy(y), Math.max(1.6, S * 0.07), 0, Math.PI * 2);
      g.fill();
    }
  }

  // ------------------------------------------------------------ 需求与空车调度

  /**
   * 分区需求热力图：服务端按区域统计的叫车强度（单/分钟，指数衰减），只染在道路上，颜色越深叫车越多。
   * 只在设置了需求热点或开启空车调度时显示；开启调度时标出分区边界和数值。
   */
  drawZones(g, z, S) {
    if (!z || !z.perMin) return;
    const max = Math.max(0.3, ...z.perMin);
    const demand = this.col.demand;
    g.save();
    g.clip(this.roadPath);
    for (let zy = 0; zy < z.rows; zy++) {
      for (let zx = 0; zx < z.cols; zx++) {
        const k = z.perMin[zy * z.cols + zx] / max;
        if (k < 0.08) continue;
        const x0 = zx * z.zoneSize, y0 = zy * z.zoneSize;
        const w = Math.min(z.zoneSize, this.W - x0) * S, h = Math.min(z.zoneSize, this.H - y0) * S;
        g.fillStyle = demand + Math.round(12 + k * 52).toString(16).padStart(2, '0');
        g.fillRect(this.sx(x0 * this.res), this.sy(y0 * this.res) - h, w, h);
      }
    }
    g.restore();
    if (!z.on) return;
    g.font = `600 ${clamp(S * 0.42, 9, 12)}px ${this.col.font}`;
    g.textAlign = 'left';
    g.textBaseline = 'top';
    for (let zy = 0; zy < z.rows; zy++) {
      for (let zx = 0; zx < z.cols; zx++) {
        const v = z.perMin[zy * z.cols + zx];
        const x0 = zx * z.zoneSize, y0 = zy * z.zoneSize;
        const w = Math.min(z.zoneSize, this.W - x0) * S, h = Math.min(z.zoneSize, this.H - y0) * S;
        const left = this.sx(x0 * this.res), top = this.sy(y0 * this.res) - h;
        g.strokeStyle = demand + '50';
        g.lineWidth = 1;
        g.setLineDash([4, 4]);
        g.strokeRect(left + 0.5, top + 0.5, w - 1, h - 1);
        g.setLineDash([]);
        if (v >= 0.1 && v >= max * 0.3) {   // 只标出需求较高的区域，避免满屏数字
          g.fillStyle = demand;
          g.fillText(`${v.toFixed(1)} 单/分`, left + 4, top + 3);
        }
      }
    }
  }

  drawHotspots(g, hs, S) {
    const demand = this.col.demand;
    for (const h of hs) {
      const cx = this.sx((h.x + 0.5) * this.res), cy = this.sy((h.y + 0.5) * this.res);
      g.strokeStyle = demand;
      g.lineWidth = 1.5;
      g.setLineDash([6, 4]);
      g.beginPath();
      g.arc(cx, cy, h.radius * S, 0, Math.PI * 2);
      g.stroke();
      g.setLineDash([]);
      const label = `${h.name} · ${h.origin >= h.dest ? '出发多' : '到达多'}`;
      g.font = `600 ${clamp(S * 0.45, 10, 13)}px ${this.col.font}`;
      g.textAlign = 'center';
      g.textBaseline = 'middle';
      const tw = g.measureText(label).width + 10;
      g.fillStyle = this.col.panel + 'e6';
      this.roundRect(g, cx - tw / 2, cy - 9, tw, 18, 4);
      g.fill();
      g.fillStyle = demand;
      g.fillText(label, cx, cy);
    }
  }

  drawCarpet(g, a, S) {
    const color = carColor(a.num);
    const strong = this.selected === a.id;
    g.fillStyle = color + (strong ? '4d' : '24');
    for (let i = 0; i < a.held.length; i += 2) {
      const x = this.sx(a.held[i] * this.res), y = this.sy((a.held[i + 1] + 1) * this.res);
      g.fillRect(x + 1, y + 1, S - 2, S - 2);
    }
  }

  drawRoute(g, a, p, S) {
    const r = a.route;
    if (r.length < 4) return;
    const selected = this.selected === a.id;
    const loaded = a.status === 'TO_DROPOFF';
    g.strokeStyle = carColor(a.num) + (selected ? 'ee' : loaded ? 'b0' : '70');
    g.lineWidth = selected ? Math.max(2.5, S * 0.12) : Math.max(1.5, S * 0.06);
    g.setLineDash(loaded || selected ? [] : [S * 0.3, S * 0.22]);
    g.lineJoin = 'round';
    g.beginPath();
    g.moveTo(this.sx(p.x), this.sy(p.y));
    const n = r.length / 2;
    const radius = S * 0.5;
    for (let i = 1; i < n - 1; i++) {
      g.arcTo(this.sx(r[i * 2]), this.sy(r[i * 2 + 1]), this.sx(r[(i + 1) * 2]), this.sy(r[(i + 1) * 2 + 1]), radius);
    }
    g.lineTo(this.sx(r[(n - 1) * 2]), this.sy(r[(n - 1) * 2 + 1]));
    g.stroke();
    g.setLineDash([]);
  }

  drawTasks(g, s, S, t) {
    const signal = this.col.signal;
    const ink = this.col.ink;
    const agentNum = new Map(s.agents.map((a) => [a.id, a.num]));
    for (const task of s.tasks) {
      if (task.state === 'PICKED') {
        // 目的地旗帜
        const x = this.sx((task.to[0] + 0.5) * this.res), y = this.sy((task.to[1] + 0.5) * this.res);
        const color = carColor(agentNum.get(task.agent) || 1);
        g.strokeStyle = ink;
        g.lineWidth = Math.max(1.2, S * 0.06);
        g.beginPath();
        g.moveTo(x - S * 0.15, y + S * 0.35);
        g.lineTo(x - S * 0.15, y - S * 0.4);
        g.stroke();
        g.fillStyle = color;
        g.beginPath();
        g.moveTo(x - S * 0.15, y - S * 0.4);
        g.lineTo(x + S * 0.35, y - S * 0.22);
        g.lineTo(x - S * 0.15, y - S * 0.04);
        g.closePath();
        g.fill();
        continue;
      }
      // 等待中的乘客：琥珀色圆点，未派车时有脉冲光圈
      const x = this.sx((task.from[0] + 0.5) * this.res), y = this.sy((task.from[1] + 0.5) * this.res);
      const rr = Math.max(4, S * 0.3);
      if (task.state === 'WAITING') {
        const k = (t / 1400) % 1;
        g.strokeStyle = signal;
        g.globalAlpha = 1 - k;
        g.lineWidth = 1.5;
        g.beginPath();
        g.arc(x, y, rr * (1 + k * 1.2), 0, Math.PI * 2);
        g.stroke();
        g.globalAlpha = 1;
      } else if (task.agent) {
        g.strokeStyle = carColor(agentNum.get(task.agent) || 1);
        g.lineWidth = Math.max(2, S * 0.08);
        g.beginPath();
        g.arc(x, y, rr * 1.35, 0, Math.PI * 2);
        g.stroke();
      }
      g.fillStyle = signal;
      g.beginPath();
      g.arc(x, y, rr, 0, Math.PI * 2);
      g.fill();
      // 简化的人形：头 + 身体
      g.fillStyle = '#ffffff';
      g.beginPath();
      g.arc(x, y - rr * 0.32, rr * 0.22, 0, Math.PI * 2);
      g.fill();
      g.beginPath();
      g.ellipse(x, y + rr * 0.28, rr * 0.36, rr * 0.3, 0, Math.PI, 0);
      g.fill();
    }
  }

  drawPending(g, S) {
    const c = this.pendingCell;
    g.strokeStyle = this.col.signal;
    g.lineWidth = 2;
    g.setLineDash([4, 3]);
    g.strokeRect(this.sx(c.x * this.res) + 1, this.sy((c.y + 1) * this.res) + 1, S - 2, S - 2);
    g.setLineDash([]);
  }

  drawCar(g, a, p, S) {
    const color = carColor(a.num);
    const px = this.sx(p.x), py = this.sy(p.y);
    const len = a.r * 2.6 * this.scale, wid = a.r * 1.8 * this.scale;
    const selected = this.selected === a.id || this.hoverId === a.id;
    g.save();
    g.translate(px, py);
    g.rotate(-p.theta);
    if (selected) {
      g.fillStyle = color + '33';
      g.beginPath();
      g.arc(0, 0, len * 0.95, 0, Math.PI * 2);
      g.fill();
    }
    g.fillStyle = color;
    g.strokeStyle = a.blocked ? this.col.danger : 'rgba(10,20,30,0.55)';
    g.lineWidth = a.blocked ? 2 : 1;
    this.roundRect(g, -len / 2, -wid / 2, len, wid, Math.min(wid * 0.35, 5));
    g.fill();
    g.stroke();
    // 前挡风玻璃，指示车头方向
    g.fillStyle = 'rgba(255,255,255,0.75)';
    this.roundRect(g, len * 0.12, -wid * 0.32, len * 0.2, wid * 0.64, 2);
    g.fill();
    g.restore();

    // 编号与外部驱动标记
    const fontPx = clamp(S * 0.42, 9, 13);
    g.font = `600 ${fontPx}px ${this.col.font}`;
    g.textAlign = 'center';
    g.textBaseline = 'bottom';
    const label = String(a.num) + (a.driver !== 'INTERNAL' ? ' ' + driverLetter(a.driver) : '');
    const ly = py - Math.max(len, wid) * 0.62;
    const tw = g.measureText(label).width + 6;
    g.fillStyle = a.driver !== 'INTERNAL' ? this.col.ink : 'rgba(255,255,255,0.82)';
    this.roundRect(g, px - tw / 2, ly - fontPx - 2, tw, fontPx + 3, 3);
    g.fill();
    g.fillStyle = a.driver !== 'INTERNAL' ? this.col.panel : '#1a2430';
    g.fillText(label, px, ly + 1);
  }

  drawPhone(g, a, p, S) {
    const px = this.sx(p.x), py = this.sy(p.y);
    const rr = a.r * this.scale;
    g.fillStyle = PHONE_COLOR + '26';
    g.strokeStyle = PHONE_COLOR;
    g.lineWidth = 1.5;
    g.setLineDash([3, 3]);
    g.beginPath();
    g.arc(px, py, rr, 0, Math.PI * 2);
    g.fill();
    g.stroke();
    g.setLineDash([]);
    g.save();
    g.translate(px, py);
    g.rotate(-p.theta);
    g.fillStyle = PHONE_COLOR;
    this.roundRect(g, -rr * 0.32, -rr * 0.5, rr * 0.64, rr * 1.0, 2);
    g.fill();
    g.restore();
    g.font = `600 ${clamp(S * 0.38, 9, 12)}px ${this.col.font}`;
    g.textAlign = 'left';
    g.textBaseline = 'middle';
    g.fillStyle = PHONE_COLOR;
    g.fillText(a.id, px + rr + 3, py);
  }

  // ------------------------------------------------------------ 交互

  agentAt(px, py) {
    if (!this.snapshot) return null;
    const now = performance.now();
    let best = null, bestD = 14;
    for (const a of this.snapshot.agents) {
      const p = this.displayPose(a, now);
      const d = Math.hypot(this.sx(p.x) - px, this.sy(p.y) - py);
      if (d < bestD) { bestD = d; best = a; }
    }
    return best;
  }

  onMove(e) {
    const r = this.canvas.getBoundingClientRect();
    const px = e.clientX - r.left, py = e.clientY - r.top;
    const a = this.agentAt(px, py);
    this.hoverId = a ? a.id : null;
    this.handlers.onHover?.(a, px, py);
  }

  onClick(e) {
    const r = this.canvas.getBoundingClientRect();
    const px = e.clientX - r.left, py = e.clientY - r.top;
    const cell = this.cellAtPx(px, py);
    if (cell && this.handlers.onCellClick?.(cell, e)) return;
    const a = this.agentAt(px, py);
    this.selected = a && this.selected !== a.id ? a.id : null;
    this.handlers.onSelect?.(this.selected);
  }
}

export function driverLetter(driver) {
  return { MATLAB: 'M', GAZEBO: 'G', PHONE: 'P', GENERIC: 'E' }[driver] || '';
}
