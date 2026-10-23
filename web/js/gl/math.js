// 3D 视图用到的最少量矩阵运算（列主序，与 WebGL 一致）。
// 坐标约定：地图坐标 (x 向东, y 向北) → 三维 (x, 高度, −y)，即 GL 的 y 轴朝上、−z 朝北。

export function identity() {
  const m = new Float32Array(16);
  m[0] = m[5] = m[10] = m[15] = 1;
  return m;
}

/** out = a × b */
export function multiply(a, b, out = new Float32Array(16)) {
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    }
  }
  return out;
}

export function perspective(fovy, aspect, near, far) {
  const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
  const m = new Float32Array(16);
  m[0] = f / aspect;
  m[5] = f;
  m[10] = (far + near) * nf;
  m[11] = -1;
  m[14] = 2 * far * near * nf;
  return m;
}

export function lookAt(eye, center, up) {
  let zx = eye[0] - center[0], zy = eye[1] - center[1], zz = eye[2] - center[2];
  let len = Math.hypot(zx, zy, zz) || 1;
  zx /= len; zy /= len; zz /= len;
  let xx = up[1] * zz - up[2] * zy, xy = up[2] * zx - up[0] * zz, xz = up[0] * zy - up[1] * zx;
  len = Math.hypot(xx, xy, xz) || 1;
  xx /= len; xy /= len; xz /= len;
  const yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
  const m = new Float32Array(16);
  m[0] = xx; m[1] = yx; m[2] = zx;
  m[4] = xy; m[5] = yy; m[6] = zy;
  m[8] = xz; m[9] = yz; m[10] = zz;
  m[12] = -(xx * eye[0] + xy * eye[1] + xz * eye[2]);
  m[13] = -(yx * eye[0] + yy * eye[1] + yz * eye[2]);
  m[14] = -(zx * eye[0] + zy * eye[1] + zz * eye[2]);
  m[15] = 1;
  return m;
}

/**
 * 模型矩阵 = 平移 × 绕竖直轴旋转 yaw × 缩放。yaw 与地图航向 θ 相同（从东向北为正），
 * 因为地图的北是 GL 的 −z，绕 y 轴转 +θ 正好把 +x（东）转到 θ 方向。
 */
export function trs(tx, ty, tz, yaw = 0, sx = 1, sy = 1, sz = 1, out = new Float32Array(16)) {
  const c = Math.cos(yaw), s = Math.sin(yaw);
  out[0] = c * sx; out[1] = 0; out[2] = -s * sx; out[3] = 0;
  out[4] = 0; out[5] = sy; out[6] = 0; out[7] = 0;
  out[8] = s * sz; out[9] = 0; out[10] = c * sz; out[11] = 0;
  out[12] = tx; out[13] = ty; out[14] = tz; out[15] = 1;
  return out;
}

/** 法线矩阵：模型矩阵左上 3×3 的逆转置（有非等比缩放时法线方向才正确）。 */
export function normalMatrix(m, out = new Float32Array(9)) {
  const a00 = m[0], a01 = m[1], a02 = m[2], a10 = m[4], a11 = m[5], a12 = m[6], a20 = m[8], a21 = m[9], a22 = m[10];
  const b01 = a22 * a11 - a12 * a21, b11 = -a22 * a10 + a12 * a20, b21 = a21 * a10 - a11 * a20;
  let det = a00 * b01 + a01 * b11 + a02 * b21;
  if (!det) det = 1e-9;
  const id = 1 / det;
  // 逆矩阵的转置
  out[0] = b01 * id; out[3] = (-a22 * a01 + a02 * a21) * id; out[6] = (a12 * a01 - a02 * a11) * id;
  out[1] = b11 * id; out[4] = (a22 * a00 - a02 * a20) * id; out[7] = (-a12 * a00 + a02 * a10) * id;
  out[2] = b21 * id; out[5] = (-a21 * a00 + a01 * a20) * id; out[8] = (a11 * a00 - a01 * a10) * id;
  return out;
}

/** 把世界坐标点投影到屏幕像素（返回 null 表示在相机后面）。 */
export function project(viewProj, x, y, z, width, height) {
  const cx = viewProj[0] * x + viewProj[4] * y + viewProj[8] * z + viewProj[12];
  const cy = viewProj[1] * x + viewProj[5] * y + viewProj[9] * z + viewProj[13];
  const cw = viewProj[3] * x + viewProj[7] * y + viewProj[11] * z + viewProj[15];
  if (cw <= 0.01) return null;
  return [(cx / cw * 0.5 + 0.5) * width, (1 - (cy / cw * 0.5 + 0.5)) * height];
}

/** '#rrggbb' → [r, g, b]（0~1） */
export function rgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16) / 255, parseInt(h.slice(2, 4), 16) / 255, parseInt(h.slice(4, 6), 16) / 255];
}

export function mix(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}
