// 网格构建：把盒子、圆柱、圆锥、球、平面片等拼成一个网格（顶点位置 + 法线 + 顶点颜色 + 索引）。
// 静态的城市（道路、建筑）拼成少数几个大网格，一次绘制；车辆等动态物体用单位网格 + 模型矩阵绘制。

export class MeshBuilder {
  constructor() {
    this.pos = [];
    this.nor = [];
    this.col = [];
    this.idx = [];
  }

  get vertexCount() {
    return this.pos.length / 3;
  }

  vertex(x, y, z, nx, ny, nz, c) {
    this.pos.push(x, y, z);
    this.nor.push(nx, ny, nz);
    this.col.push(c[0], c[1], c[2]);
    return this.vertexCount - 1;
  }

  /** 四边形 a-b-c-d（逆时针为正面），法线 n。 */
  quad(a, b, c, d, n, color) {
    const i = this.vertex(...a, ...n, color);
    this.vertex(...b, ...n, color);
    this.vertex(...c, ...n, color);
    this.vertex(...d, ...n, color);
    this.idx.push(i, i + 1, i + 2, i, i + 2, i + 3);
  }

  /** 水平面片（朝上），覆盖 [x0,x1] × [z0,z1]，高度 y。 */
  flat(x0, z0, x1, z1, y, color) {
    this.quad([x0, y, z1], [x1, y, z1], [x1, y, z0], [x0, y, z0], [0, 1, 0], color);
  }

  /**
   * 轴对齐盒子 [x0,x1]×[y0,y1]×[z0,z1]。sideColor 用于四周，topColor 用于顶面；bottom=false 时不画底面。
   */
  box(x0, y0, z0, x1, y1, z1, sideColor, topColor = sideColor, bottom = false) {
    this.quad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0], [0, 1, 0], topColor);         // 顶
    if (bottom) this.quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], [0, -1, 0], sideColor);
    this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], [0, 0, 1], sideColor);        // +z（南）
    this.quad([x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [0, 0, -1], sideColor);       // −z（北）
    this.quad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [1, 0, 0], sideColor);        // +x（东）
    this.quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], [-1, 0, 0], sideColor);       // −x（西）
  }

  /** 竖直圆柱 / 圆台：底面圆心 (cx, y0, cz)，半径 r0（底）、r1（顶），高 h。 */
  cylinder(cx, y0, cz, r0, r1, h, segs, color, capTop = true) {
    const slope = (r0 - r1) / h;
    const base = this.vertexCount;
    for (let i = 0; i <= segs; i++) {
      const a = (i / segs) * Math.PI * 2, c = Math.cos(a), s = Math.sin(a);
      const n = normalize([c, slope, -s]);
      this.vertex(cx + c * r0, y0, cz - s * r0, ...n, color);
      this.vertex(cx + c * r1, y0 + h, cz - s * r1, ...n, color);
    }
    for (let i = 0; i < segs; i++) {
      const k = base + i * 2;
      this.idx.push(k, k + 2, k + 3, k, k + 3, k + 1);
    }
    if (capTop && r1 > 0) this.disc(cx, y0 + h, cz, r1, segs, color);
  }

  /** 圆锥（尖朝上）。 */
  cone(cx, y0, cz, r, h, segs, color) {
    this.cylinder(cx, y0, cz, r, 0.0001, h, segs, color, false);
  }

  /** 水平圆片（朝上）。 */
  disc(cx, y, cz, r, segs, color) {
    const c0 = this.vertex(cx, y, cz, 0, 1, 0, color);
    for (let i = 0; i <= segs; i++) {
      const a = (i / segs) * Math.PI * 2;
      this.vertex(cx + Math.cos(a) * r, y, cz - Math.sin(a) * r, 0, 1, 0, color);
    }
    for (let i = 0; i < segs; i++) this.idx.push(c0, c0 + 1 + i, c0 + 2 + i);
  }

  /** 水平圆环（朝上），内外半径 r0、r1。 */
  ring(cx, y, cz, r0, r1, segs, color) {
    const base = this.vertexCount;
    for (let i = 0; i <= segs; i++) {
      const a = (i / segs) * Math.PI * 2, c = Math.cos(a), s = Math.sin(a);
      this.vertex(cx + c * r0, y, cz - s * r0, 0, 1, 0, color);
      this.vertex(cx + c * r1, y, cz - s * r1, 0, 1, 0, color);
    }
    for (let i = 0; i < segs; i++) {
      const k = base + i * 2;
      this.idx.push(k, k + 1, k + 3, k, k + 3, k + 2);
    }
  }

  /** 水平横放的圆柱（轮子），轴沿 z，圆心 (cx, cy, cz)，半径 r，宽 w。 */
  wheel(cx, cy, cz, r, w, segs, color) {
    const base = this.vertexCount;
    for (let i = 0; i <= segs; i++) {
      const a = (i / segs) * Math.PI * 2, c = Math.cos(a), s = Math.sin(a);
      this.vertex(cx + c * r, cy + s * r, cz - w / 2, c, s, 0, color);
      this.vertex(cx + c * r, cy + s * r, cz + w / 2, c, s, 0, color);
    }
    for (let i = 0; i < segs; i++) {
      const k = base + i * 2;
      this.idx.push(k, k + 2, k + 3, k, k + 3, k + 1);
    }
    for (const side of [-1, 1]) {
      const c0 = this.vertex(cx, cy, cz + side * w / 2, 0, 0, side, color);
      for (let i = 0; i <= segs; i++) {
        const a = (i / segs) * Math.PI * 2;
        this.vertex(cx + Math.cos(a) * r, cy + Math.sin(a) * r, cz + side * w / 2, 0, 0, side, color);
      }
      for (let i = 0; i < segs; i++) {
        if (side > 0) this.idx.push(c0, c0 + 1 + i, c0 + 2 + i);
        else this.idx.push(c0, c0 + 2 + i, c0 + 1 + i);
      }
    }
  }

  /** 低多边形球。 */
  sphere(cx, cy, cz, r, segs, color) {
    const rings = Math.max(3, segs >> 1);
    const base = this.vertexCount;
    for (let j = 0; j <= rings; j++) {
      const v = (j / rings) * Math.PI, sv = Math.sin(v), cv = Math.cos(v);
      for (let i = 0; i <= segs; i++) {
        const u = (i / segs) * Math.PI * 2;
        const n = [Math.cos(u) * sv, cv, -Math.sin(u) * sv];
        this.vertex(cx + n[0] * r, cy + n[1] * r, cz + n[2] * r, ...n, color);
      }
    }
    for (let j = 0; j < rings; j++) {
      for (let i = 0; i < segs; i++) {
        const a = base + j * (segs + 1) + i, b = a + segs + 1;
        this.idx.push(a, b, a + 1, b, b + 1, a + 1);
      }
    }
  }

  /** 贴地的扇形 / 多边形：中心 (cx, cz)，外圈点 [[x, z], ...] 首尾相连。 */
  fan(cx, y, cz, ring, color) {
    const c0 = this.vertex(cx, y, cz, 0, 1, 0, color);
    for (const [x, z] of ring) this.vertex(x, y, z, 0, 1, 0, color);
    const n = ring.length;
    for (let i = 0; i < n; i++) this.idx.push(c0, c0 + 1 + i, c0 + 1 + ((i + 1) % n));
  }

  /** 贴地的带子（路线）：经过折线各点，宽 w。 */
  ribbon(points, y, w, color) {
    if (points.length < 2) return;
    const base = this.vertexCount;
    for (let i = 0; i < points.length; i++) {
      const p = points[i], a = points[Math.max(0, i - 1)], b = points[Math.min(points.length - 1, i + 1)];
      let dx = b[0] - a[0], dz = b[1] - a[1];
      const len = Math.hypot(dx, dz) || 1;
      dx /= len; dz /= len;
      this.vertex(p[0] - dz * w / 2, y, p[1] + dx * w / 2, 0, 1, 0, color);
      this.vertex(p[0] + dz * w / 2, y, p[1] - dx * w / 2, 0, 1, 0, color);
    }
    for (let i = 0; i < points.length - 1; i++) {
      const k = base + i * 2;
      this.idx.push(k, k + 2, k + 1, k + 1, k + 2, k + 3);
    }
  }

  build() {
    const n = this.vertexCount;
    return {
      pos: new Float32Array(this.pos),
      nor: new Float32Array(this.nor),
      col: new Float32Array(this.col),
      idx: n > 65535 ? new Uint32Array(this.idx) : new Uint16Array(this.idx),
    };
  }
}

function normalize(v) {
  const l = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / l, v[1] / l, v[2] / l];
}
