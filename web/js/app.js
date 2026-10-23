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
    metric('绕行次数', m.reroutes),
  ].join('');

  const crashes = m.collisions + m.obstacleHits;
  const safety = $('safety');
  safety.dataset.bad = crashes > 0 ? 'true' : 'false';
  const gaps = [];
  if (m.minCarGap != null) gaps.push(`最小车距 ${m.minCarGap.toFixed(2)} m`);
  if (m.minWallGap != null) gaps.push(`最小离墙 ${m.minWallGap.toFixed(2)} m`);
  safety.innerHTML = `<strong>${crashes === 0 ? '零碰撞' : `碰撞 ${m.collisions} 次，撞墙 ${m.obstacleHits} 次`}</strong>`
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
      + `<td>${esc(STATUS[a.status] || a.status)}${a.blocked ? '<span class="blocked-tag">受阻</span>' : ''}</td>`
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

  syncControls(s);
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
$('resetBtn').addEventListener('click', () => {
  if (confirm('重置仿真？订单和统计数据会清零，已连接的设备会自动重新绑定。')) post('/api/sim/reset', {});
});

// ------------------------------------------------------------ 数据流

async function start() {
  try {
    const m = await (await fetch('/api/map')).json();
    $('mapName').textContent = `${m.name}（${m.width}×${m.height} 格，每格 ${m.resolution} 米）`;
    view.setMap(m);
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
