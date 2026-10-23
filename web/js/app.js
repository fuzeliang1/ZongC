// MiniDiDi 数字世界前端：订阅服务端快照（SSE），驱动地图渲染和右侧面板。
import { MapView, carColor, driverLetter } from './map.js';

const STATUS = {
  CRUISING: '空车巡游', TO_PICKUP: '前往接客', BOARDING: '乘客上车',
  TO_DROPOFF: '送客中', ALIGHTING: '乘客下车', FREE: '自由移动',
};
const DRIVER = { INTERNAL: '内部仿真', MATLAB: 'Matlab', GAZEBO: 'Gazebo', PHONE: '手机', GENERIC: '外部设备' };
const TASK_STATE = { WAITING: '等待接驾', ASSIGNED: '已派车', PICKED: '行程中' };

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let latest = null;
let selected = null;
let taskMode = null;          // null | 'pickup' | 'dropoff'
let panelTimer = 0;

// ------------------------------------------------------------ 地图

const view = new MapView($('map'), {
  onSelect(id) {
    selected = id;
    renderPanels();
  },
  onHover(agent, px, py) {
    const tip = $('tooltip');
    if (!agent) { tip.hidden = true; return; }
    tip.innerHTML = tooltipHtml(agent);
    tip.hidden = false;
    const stage = $('map').getBoundingClientRect();
    const left = Math.min(px + 16, stage.width - tip.offsetWidth - 8);
    const top = Math.min(py + 16, stage.height - tip.offsetHeight - 8);
    tip.style.left = left + 'px';
    tip.style.top = top + 'px';
  },
  onCellClick(cell, e) {
    if (taskMode) {
      handleTaskClick(cell);
      return true;
    }
    if (e.shiftKey) {
      if (cell.ch === '#') return true;
      const blocked = isBlocked(cell.x, cell.y);
      post('/api/roadblocks', { cell: [cell.x, cell.y], blocked: !blocked });
      return true;
    }
    return false;
  },
});

function isBlocked(x, y) {
  const b = latest?.blocked || [];
  for (let i = 0; i < b.length; i += 2) if (b[i] === x && b[i + 1] === y) return true;
  return false;
}

function tooltipHtml(a) {
  if (!a.taxi) {
    return `<b>${esc(a.id)}</b><br>自由智能体（${esc(DRIVER[a.driver] || a.driver)}）<br>`
      + `位置 (${a.x.toFixed(1)}, ${a.y.toFixed(1)})` + (a.device ? `<br>设备 ${esc(a.device)}` : '');
  }
  const lines = [
    `<b>${esc(a.id)}</b>　${esc(STATUS[a.status] || a.status)}`,
    `驱动：${esc(DRIVER[a.driver] || a.driver)}${a.device ? '（' + esc(a.device) + '）' : ''}`,
    `速度 ${a.v.toFixed(2)} m/s，位置 (${a.x.toFixed(1)}, ${a.y.toFixed(1)})`,
  ];
  if (a.taskId) lines.push(`订单 ${esc(a.taskId)}`);
  if (a.zone) lines.push(`空车调度：在区域 ${esc(a.zone)} 内待命`);
  if (a.seen) lines.push(`雷达看到：车 ${a.seen[0]}，行人 ${a.seen[1]}${a.seen[2] ? `，<b style="color:var(--sensed)">未知障碍 ${a.seen[2]} 处</b>` : ''}`);
  if (a.blocked) lines.push('<span style="color:var(--danger)">前方受阻，等待通行</span>');
  return lines.join('<br>');
}

// ------------------------------------------------------------ 手动下单

function setTaskMode(mode) {
  taskMode = mode;
  $('taskBtn').setAttribute('aria-pressed', mode ? 'true' : 'false');
  const banner = $('modeBanner');
  if (!mode) {
    banner.hidden = true;
    view.pendingCell = null;
    return;
  }
  banner.hidden = false;
  banner.textContent = mode === 'pickup' ? '点击车道选择上车点（Esc 取消）' : '点击车道选择目的地（Esc 取消）';
}

async function handleTaskClick(cell) {
  if (!'<>^v'.includes(cell.ch)) {
    $('modeBanner').textContent = '请点击车道（带箭头的道路），路口和建筑不能作为上下车点';
    return;
  }
  if (taskMode === 'pickup') {
    view.pendingCell = cell;
    setTaskMode('dropoff');
    return;
  }
  const from = view.pendingCell;
  const res = await post('/api/tasks', { from: [from.x, from.y], to: [cell.x, cell.y] });
  setTaskMode(null);
  if (res && !res.ok) alertBanner(res.error || '下单失败');
}

function alertBanner(text) {
  const banner = $('modeBanner');
  banner.hidden = false;
  banner.textContent = text;
  setTimeout(() => { if (!taskMode) banner.hidden = true; }, 3000);
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && taskMode) setTaskMode(null);
});

// ------------------------------------------------------------ 面板

function fmtClock(t) {
  const s = Math.floor(t);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const mm = String(m).padStart(2, '0'), ss = String(sec).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function metric(label, value, unit = '') {
  return `<div><dt>${label}</dt><dd>${value}${unit ? `<small>${unit}</small>` : ''}</dd></div>`;
}

function renderPanels() {
  const s = latest;
  if (!s) return;
  const m = s.metrics;
  $('simClock').textContent = fmtClock(s.t);

  $('metrics').innerHTML = [
    metric('已送达', `${m.delivered}<small>/${m.created}</small>`),
    metric('等待接驾', m.waiting),
    metric('送达效率', m.deliveredPerMin.toFixed(1), '单/分'),
    metric('平均等待', m.avgWait.toFixed(1), 's'),
    metric('平均乘车', m.avgRide.toFixed(1), 's'),
    metric('空驶率', Math.round(m.emptyRatio * 100), '%'),
    metric('数字车', m.taxis),
    metric('外部接入', m.externalTaxis + m.freeAgents),
    metric('绕行 / 掉头', `${m.reroutes}<small>/${m.uturns}</small>`),
    metric('感知发现障碍', m.discoveries, m.obstacleClears ? `撤除 ${m.obstacleClears}` : ''),
    metric('地图核实', Math.round(m.coverage * 100), '%'),
    metric('空车调度', m.rebalanceMoves, '次'),
  ].join('');

  const crashes = m.collisions + m.obstacleHits + m.barrierHits;
  const safety = $('safety');
  safety.dataset.bad = crashes > 0 ? 'true' : 'false';
  const gaps = [];
  if (m.minCarGap != null) gaps.push(`最小车距 ${m.minCarGap.toFixed(2)} m`);
  if (m.minWallGap != null) gaps.push(`最小离墙 ${m.minWallGap.toFixed(2)} m`);
  if (m.minBarrierGap != null) gaps.push(`离障碍 ${m.minBarrierGap.toFixed(2)} m`);
  safety.innerHTML = `<strong>${crashes === 0 ? '零碰撞' : `碰撞 ${m.collisions} 次，撞墙 ${m.obstacleHits} 次，撞障碍 ${m.barrierHits} 次`}</strong>`
    + `　${gaps.join('，')}${m.authorityViolations ? `，越权行驶 ${m.authorityViolations} 次` : ''}`;

  // 车辆表
  const agents = [...s.agents].sort((a, b) => (a.taxi === b.taxi ? a.num - b.num : a.taxi ? -1 : 1));
  $('carCount').textContent = `${s.agents.filter((a) => a.taxi).length} 辆`;
  $('carRows').innerHTML = agents.map((a) => {
    const color = a.taxi ? carColor(a.num) : '#7b5cd6';
    const ext = a.driver !== 'INTERNAL';
    return `<tr data-id="${esc(a.id)}" class="${a.id === selected ? 'selected' : ''}">`
      + `<td><span class="swatch" style="background:${color}"></span>${esc(a.id)}</td>`
      + `<td class="${ext ? 'ext' : ''}">${esc(DRIVER[a.driver] || a.driver)}${ext ? ' ' + driverLetter(a.driver) : ''}</td>`
      + `<td>${esc(STATUS[a.status] || a.status)}${a.blocked ? '<span class="blocked-tag">受阻</span>' : ''}`
      + `${a.zone ? '<span class="zone-tag" title="空车调度：在该区域待命">调度</span>' : ''}</td>`
      + `<td class="num">${a.v.toFixed(2)}</td></tr>`;
  }).join('');

  // 订单
  const order = { WAITING: 0, ASSIGNED: 1, PICKED: 2 };
  const tasks = [...s.tasks].sort((a, b) => order[a.state] - order[b.state] || b.age - a.age);
  $('taskCount').textContent = tasks.length ? `${tasks.length} 单进行中` : '';
  $('taskList').innerHTML = tasks.length
    ? tasks.slice(0, 14).map((t) => `<li><span>${esc(t.id)}</span>`
      + `<span class="state" data-s="${t.state}">${TASK_STATE[t.state] || t.state}</span>`
      + `<span class="route">(${t.from}) 到 (${t.to})${t.agent ? '，' + esc(t.agent) : ''}</span>`
      + `<span class="age">${Math.round(t.age)} s</span></li>`).join('')
    : '<li class="empty">暂无进行中的订单</li>';

  // 事件（新的在上）
  const ev = [...(s.events || [])].reverse();
  $('events').innerHTML = ev.length
    ? ev.map((e) => `<li data-level="${e.level}"><time>${fmtClock(e.t)}</time><span>${esc(e.msg)}</span></li>`).join('')
    : '<li class="empty">暂无事件</li>';

  renderScenario(s);
  renderVision(s);
  syncControls(s);
}

// ------------------------------------------------------------ 图像识别

let visionSeq = 0;

async function renderVision(s) {
  const v = s.vision;
  if (!v || v.seq === visionSeq) return;
  visionSeq = v.seq;
  $('visionImg').src = `/api/vision/last.jpg?seq=${v.seq}`;
  $('visionFig').hidden = false;
  $('visionEmpty').hidden = true;
  let detail = '';
  try {
    const r = await (await fetch('/api/vision/last')).json();
    detail = `${r.width}×${r.height} 像素，缩到 ${r.procWidth}×${r.procHeight} 处理；解码 ${r.decodeMs} ms，识别 ${r.detectMs} ms`
      + `（${r.threads} 线程并行${r.speedup ? `，比单线程快 ${r.speedup} 倍` : ''}）`;
  } catch (err) { /* 忽略 */ }
  $('visionCap').innerHTML = `<b>${esc(v.source === 'web' ? '网页上传' : v.source)}</b>：${esc(v.summary)}`
    + (v.mapped ? `<small>已写入地图：${esc(cellsText(v.mapped))}</small>` : '') + (detail ? `<small>${esc(detail)}</small>` : '');
}

function cellsText(flat) {
  const out = [];
  for (let i = 0; i < flat.length; i += 2) out.push(`(${flat[i]},${flat[i + 1]})`);
  return out.join('、');
}

async function uploadImage(blob) {
  try {
    const r = await fetch('/api/vision/detect?source=web&benchmark=1', { method: 'POST', body: blob });
    const j = await r.json();
    if (!j.ok) alertBanner(j.error || '识别失败');
  } catch (err) {
    alertBanner('上传失败：' + err.message);
  }
}

$('visionFile').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (f) uploadImage(f);
  e.target.value = '';
});
$('visionSample').addEventListener('click', async () => {
  const blob = await (await fetch('/api/vision/sample.jpg')).blob();
  uploadImage(blob);
});

// ------------------------------------------------------------ 场景脚本

let bannerText = null;

function renderScenario(s) {
  const sc = s.scenario;
  const strip = $('scenarioStrip');
  strip.hidden = !sc;
  const banner = $('scBanner');
  if (!sc) {
    banner.hidden = true;
    bannerText = null;
    return;
  }
  $('scName').textContent = `场景：${sc.name}`;
  $('scLoop').textContent = `第 ${sc.loop} 轮 · ${fmtClock(sc.elapsed)}`;
  $('scNext').textContent = sc.next != null ? `下一步（${Math.ceil(sc.nextIn)} s 后）：${sc.next}` : '本轮事件已全部执行';
  const total = sc.elapsed + (sc.nextIn ?? 0);
  $('scBar').style.width = `${Math.round((sc.steps ? sc.step / sc.steps : 1) * 100)}%`;
  $('scBar').title = `已执行 ${sc.step} / ${sc.steps} 个事件，总长约 ${Math.round(total)} s`;
  if (sc.banner) {
    if (sc.banner !== bannerText) {
      banner.innerHTML = `<small>场景事件 · 自动触发</small>${esc(sc.banner)}`;
      banner.hidden = false;
      bannerText = sc.banner;
    }
  } else if (bannerText) {
    banner.hidden = true;
    bannerText = null;
  }
}

async function loadScenarios() {
  try {
    const r = await (await fetch('/api/scenarios')).json();
    const sel = $('scenarioSel');
    sel.innerHTML = '<option value="">不运行脚本</option>' + r.scenarios.map((x) => `<option value="${esc(x.file)}"`
      + `${x.error ? ' disabled' : ''} title="${esc(x.description || x.error || '')}">${esc(x.name || x.file)}`
      + `${x.loop ? '（循环）' : ''}</option>`).join('');
    sel.value = r.current || '';
  } catch (err) {
    // 服务端还没启动时忽略，稍后 start() 会重试
  }
}

$('carRows').addEventListener('click', (e) => {
  const tr = e.target.closest('tr');
  if (!tr) return;
  selected = tr.dataset.id === selected ? null : tr.dataset.id;
  view.select(selected);
  renderPanels();
});

// ------------------------------------------------------------ 控制

function syncControls(s) {
  const strategy = $('strategy');
  if (document.activeElement !== strategy) strategy.value = s.strategy;
  const rate = $('rate');
  if (document.activeElement !== rate) {
    rate.value = s.ratePerMin;
    $('rateOut').textContent = s.ratePerMin;
  }
  const ts = $('timeScale');
  if (document.activeElement !== ts && [...ts.options].some((o) => Number(o.value) === s.timeScale)) ts.value = String(s.timeScale);
  const reb = $('rebalance');
  if (document.activeElement !== reb && s.zones) reb.checked = !!s.zones.on;
  $('pauseBtn').textContent = s.paused ? '继续' : '暂停';
  $('pauseBtn').setAttribute('aria-pressed', s.paused ? 'true' : 'false');
}

async function post(url, body) {
  try {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    return await r.json();
  } catch (err) {
    alertBanner('请求失败：' + err.message);
    return null;
  }
}

$('strategy').addEventListener('change', (e) => post('/api/config', { strategy: e.target.value }));
$('rate').addEventListener('input', (e) => { $('rateOut').textContent = e.target.value; });
$('rate').addEventListener('change', (e) => post('/api/config', { ratePerMin: Number(e.target.value) }));
$('timeScale').addEventListener('change', (e) => post('/api/config', { timeScale: Number(e.target.value) }));
$('pauseBtn').addEventListener('click', () => post(latest?.paused ? '/api/sim/resume' : '/api/sim/pause'));
$('taskBtn').addEventListener('click', () => setTaskMode(taskMode ? null : 'pickup'));
$('rebalance').addEventListener('change', (e) => post('/api/config', { rebalance: e.target.checked }));
$('scenarioBtn').addEventListener('click', async () => {
  const file = $('scenarioSel').value;
  const name = $('scenarioSel').selectedOptions[0]?.textContent || '';
  if (!confirm(file ? `切换到场景"${name}"？仿真会重置，脚本从头开始。` : '停止场景脚本？仿真会重置。')) return;
  const r = await post('/api/scenario', { file: file || null });
  if (r && !r.ok) alertBanner(r.error || '切换失败');
  setTimeout(loadScenarios, 500);
});

// 图层开关（只记在本机浏览器里）
const LAYER_KEY = 'minididi.layers';
try {
  const saved = JSON.parse(localStorage.getItem(LAYER_KEY) || 'null');
  if (saved) Object.assign(view.layers, saved);
} catch (err) { /* 隐私模式等情况下不可用 */ }
for (const [id, key] of [['lyScan', 'scan'], ['lyTruth', 'truth'], ['lyDemand', 'demand']]) {
  const box = $(id);
  box.checked = view.layers[key];
  box.addEventListener('change', () => {
    view.layers[key] = box.checked;
    try { localStorage.setItem(LAYER_KEY, JSON.stringify(view.layers)); } catch (err) { /* 忽略 */ }
  });
}

$('resetBtn').addEventListener('click', () => {
  if (confirm('重置仿真？订单和统计数据会清零，已连接的设备会自动重新绑定。')) post('/api/sim/reset', {});
});

// ------------------------------------------------------------ 数据流

async function start() {
  try {
    const m = await (await fetch('/api/map')).json();
    $('mapName').textContent = `${m.name}（${m.width}×${m.height} 格，每格 ${m.resolution} 米）`;
    view.setMap(m);
    loadScenarios();
  } catch (err) {
    $('mapName').textContent = '地图加载失败，请确认服务端已启动';
    setTimeout(start, 2000);
    return;
  }
  const es = new EventSource('/api/stream');
  const conn = $('conn');
  es.onopen = () => { conn.dataset.state = 'live'; conn.textContent = '实时'; };
  es.onerror = () => { conn.dataset.state = 'lost'; conn.textContent = '连接中断，正在重连'; };
  es.onmessage = (e) => {
    const s = JSON.parse(e.data);
    if (s.type !== 'snapshot') return;
    latest = s;
    view.setSnapshot(s);
    conn.dataset.state = 'live';
    conn.textContent = s.paused ? '已暂停' : '实时';
    const now = performance.now();
    if (now - panelTimer > 250) {
      panelTimer = now;
      renderPanels();
    }
  };
}

start();
