// 一个很小的 WebGL 渲染器：一套着色器（半球环境光 + 平行光 + 雾），不透明物体先画，半透明物体后画。
// 只用 WebGL 1，不依赖任何第三方库（Three.js 等），所以整个 3D 视图不需要联网也能打开。
import { multiply, normalMatrix } from './math.js';

const VS = `
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec3 aColor;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat3 uNormalMat;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vWorld;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vWorld = w.xyz;
  vNormal = normalize(uNormalMat * aNormal);
  vColor = aColor;
  gl_Position = uViewProj * w;
}`;

const FS = `
precision mediump float;
uniform vec4 uColor;
uniform vec3 uLightDir;
uniform vec3 uSky;
uniform vec3 uGround;
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;
uniform vec3 uCamPos;
uniform float uUnlit;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vWorld;
void main() {
  vec3 base = vColor * uColor.rgb;
  vec3 n = normalize(vNormal);
  vec3 lit = base * (mix(uGround, uSky, 0.5 + 0.5 * n.y) * 0.5 + max(dot(n, uLightDir), 0.0) * 0.62);
  vec3 c = mix(lit, base, uUnlit);
  float f = clamp((distance(vWorld, uCamPos) - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
  gl_FragColor = vec4(mix(c, uFogColor, f * 0.7), uColor.a);
}`;

export class Renderer {
  constructor(canvas) {
    const gl = canvas.getContext('webgl', { antialias: true, alpha: false, preserveDrawingBuffer: true })
      || canvas.getContext('experimental-webgl');
    if (!gl) throw new Error('浏览器不支持 WebGL');
    this.gl = gl;
    this.canvas = canvas;
    this.uint32 = !!gl.getExtension('OES_element_index_uint');
    this.prog = this.program(VS, FS);
    const p = this.prog;
    this.loc = {
      pos: gl.getAttribLocation(p, 'aPos'),
      nor: gl.getAttribLocation(p, 'aNormal'),
      col: gl.getAttribLocation(p, 'aColor'),
      model: gl.getUniformLocation(p, 'uModel'),
      viewProj: gl.getUniformLocation(p, 'uViewProj'),
      normalMat: gl.getUniformLocation(p, 'uNormalMat'),
      color: gl.getUniformLocation(p, 'uColor'),
      light: gl.getUniformLocation(p, 'uLightDir'),
      sky: gl.getUniformLocation(p, 'uSky'),
      ground: gl.getUniformLocation(p, 'uGround'),
      fogColor: gl.getUniformLocation(p, 'uFogColor'),
      fogNear: gl.getUniformLocation(p, 'uFogNear'),
      fogFar: gl.getUniformLocation(p, 'uFogFar'),
      camPos: gl.getUniformLocation(p, 'uCamPos'),
      unlit: gl.getUniformLocation(p, 'uUnlit'),
    };
    this.transparent = [];
    this.nm = new Float32Array(9);
    this.lineMesh = null;
  }

  program(vsSrc, fsSrc) {
    const gl = this.gl;
    const sh = (type, src) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, sh(gl.VERTEX_SHADER, vsSrc));
    gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fsSrc));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    return p;
  }

  /** 把网格数据上传到显卡。dynamic = true 的网格之后可以用 update() 每帧替换内容。 */
  upload(data, dynamic = false) {
    const gl = this.gl;
    const usage = dynamic ? gl.DYNAMIC_DRAW : gl.STATIC_DRAW;
    const buf = (target, arr) => {
      const b = gl.createBuffer();
      gl.bindBuffer(target, b);
      gl.bufferData(target, arr, usage);
      return b;
    };
    let idx = data.idx;
    if (idx instanceof Uint32Array && !this.uint32) throw new Error('网格太大（显卡不支持 32 位索引）');
    return {
      pos: buf(gl.ARRAY_BUFFER, data.pos),
      nor: buf(gl.ARRAY_BUFFER, data.nor),
      col: buf(gl.ARRAY_BUFFER, data.col),
      idx: buf(gl.ELEMENT_ARRAY_BUFFER, idx),
      count: idx.length,
      type: idx instanceof Uint32Array ? gl.UNSIGNED_INT : gl.UNSIGNED_SHORT,
      mode: data.mode ?? gl.TRIANGLES,
    };
  }

  update(mesh, data) {
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.pos);
    gl.bufferData(gl.ARRAY_BUFFER, data.pos, gl.DYNAMIC_DRAW);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.nor);
    gl.bufferData(gl.ARRAY_BUFFER, data.nor, gl.DYNAMIC_DRAW);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.col);
    gl.bufferData(gl.ARRAY_BUFFER, data.col, gl.DYNAMIC_DRAW);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.idx);
    let idx = data.idx;
    if (idx instanceof Uint32Array && !this.uint32) idx = new Uint16Array(0);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.DYNAMIC_DRAW);
    mesh.count = idx.length;
    mesh.type = idx instanceof Uint32Array ? gl.UNSIGNED_INT : gl.UNSIGNED_SHORT;
  }

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.round(this.canvas.clientWidth * dpr), h = Math.round(this.canvas.clientHeight * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    return [w, h];
  }

  /**
   * 开始一帧。env: {sky, ground, fog: [r,g,b], fogNear, fogFar, light: [x,y,z]}
   */
  begin(view, proj, camPos, env) {
    const gl = this.gl;
    this.viewProj = multiply(proj, view);
    this.camPos = camPos;
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clearColor(env.fog[0], env.fog[1], env.fog[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.CULL_FACE);
    gl.disable(gl.BLEND);
    gl.depthMask(true);
    gl.useProgram(this.prog);
    const L = this.loc;
    gl.uniformMatrix4fv(L.viewProj, false, this.viewProj);
    const l = env.light, n = Math.hypot(l[0], l[1], l[2]);
    gl.uniform3f(L.light, l[0] / n, l[1] / n, l[2] / n);
    gl.uniform3fv(L.sky, env.sky);
    gl.uniform3fv(L.ground, env.ground);
    gl.uniform3fv(L.fogColor, env.fog);
    gl.uniform1f(L.fogNear, env.fogNear);
    gl.uniform1f(L.fogFar, env.fogFar);
    gl.uniform3fv(L.camPos, camPos);
    this.transparent.length = 0;
  }

  /**
   * 画一个网格。color = [r, g, b, a]（与顶点颜色相乘）；a < 1 的放到最后按远近排序再画。
   * opts.unlit：不受光照影响（贴地标记、路线）；opts.noDepthWrite：半透明且不遮挡后面的东西。
   */
  draw(mesh, model, color = [1, 1, 1, 1], opts = {}) {
    if (!mesh || mesh.count === 0) return;
    if (color[3] < 1 && !opts.now) {
      const d = Math.hypot(model[12] - this.camPos[0], model[13] - this.camPos[1], model[14] - this.camPos[2]);
      this.transparent.push({ mesh, model: model.slice ? model.slice() : model, color, opts, d });
      return;
    }
    this.drawNow(mesh, model, color, opts);
  }

  drawNow(mesh, model, color, opts) {
    const gl = this.gl, L = this.loc;
    gl.uniformMatrix4fv(L.model, false, model);
    gl.uniformMatrix3fv(L.normalMat, false, normalMatrix(model, this.nm));
    gl.uniform4fv(L.color, color);
    gl.uniform1f(L.unlit, opts.unlit ? 1 : 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.pos);
    gl.enableVertexAttribArray(L.pos);
    gl.vertexAttribPointer(L.pos, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.nor);
    gl.enableVertexAttribArray(L.nor);
    gl.vertexAttribPointer(L.nor, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.col);
    gl.enableVertexAttribArray(L.col);
    gl.vertexAttribPointer(L.col, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.idx);
    if (opts.doubleSided) gl.disable(gl.CULL_FACE);
    gl.drawElements(mesh.mode, mesh.count, mesh.type, 0);
    if (opts.doubleSided) gl.enable(gl.CULL_FACE);
  }

  /** 画完半透明物体，结束一帧。 */
  end() {
    const gl = this.gl;
    if (!this.transparent.length) return;
    this.transparent.sort((a, b) => b.d - a.d);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    for (const t of this.transparent) {
      gl.depthMask(!t.opts.noDepthWrite);
      this.drawNow(t.mesh, t.model, t.color, t.opts);
    }
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }
}
