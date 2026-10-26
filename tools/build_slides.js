// 生成答辩介绍 PPT：deliverables/MiniDiDi_slides.pptx
// 封面 + 正文 5 页 + 结尾，后面是 4 页附录（答辩问答时备用，不算正文）。
//
// 用法（仓库根目录）：
//   npm install pptxgenjs@3.12.0 react-icons@5.4.0 react@18.3.1 react-dom@18.3.1 sharp@0.33.5   # 装在任意目录均可
//   NODE_PATH=<上面那个目录>/node_modules node tools/build_slides.js [输出路径]
//
// 课程要求"正文不超过 5 页"。改文字直接改下面每页的内容；配色和字体集中在 THEME 里。
// 版式参考 Anthropic 的 pptx skill（github.com/anthropics/skills）：16:9 宽屏（13.33 × 7.5 英寸），
// 标题 30 磅、正文不小于 13 磅；每页一个结论写在副标题里；架构、授权、流程、甘特图都用原生形状画，
// 柱状图是原生图表，都可以在 PowerPoint 里直接改。
// 正文每页都写了演讲备注（PowerPoint 的"备注"栏），5 页约 5 分钟。
// 数据来自 docs/eval/summary.md 和 docs/test-report.md，重跑实验后记得同步这里的数字。
const fs = require('fs');
const path = require('path');
const pptxgen = require('pptxgenjs');
const React = require('react');
const ReactDOMServer = require('react-dom/server');
const sharp = require('sharp');
const fa = require('react-icons/fa');

const ROOT = path.resolve(__dirname, '..');
const OUT = process.argv[2] ? path.resolve(process.argv[2]) : path.join(ROOT, 'deliverables', 'MiniDiDi_slides.pptx');   // 可以指定输出路径
const img = (p) => path.join(ROOT, p);

// 配色：石墨深色打底，"滴滴橙"点睛（大数字、图标），青绿表示安全 / 通过，藏青画服务端
const THEME = {
  name: 'MiniDiDi',
  headFontFace: 'Microsoft YaHei',
  bodyFontFace: 'Microsoft YaHei',
  colors: {
    dk1: '1E2633', lt1: 'FFFFFF', dk2: '566173', lt2: 'F2F4F7',
    accent1: 'F26B1D', accent2: '0F9488', accent3: '27406B', accent4: 'FDE3D0',
    accent5: 'B5BDC9', accent6: 'DDF1EE', hlink: '27406B', folHlink: '566173',
  },
};
const HEX = THEME.colors;
const W = 13.333;           // 幻灯片宽（英寸）
const X0 = 0.6;             // 左右页边距
const CW = W - 2 * X0;      // 内容区宽
const Y0 = 1.8;             // 内容区顶部（标题、副标题之下）
const Y1 = 6.7;             // 内容区底部（页脚之上）

async function icon(Comp, color = HEX.lt1, size = 256) {
  const svg = ReactDOMServer.renderToStaticMarkup(React.createElement(Comp, { color: '#' + color, size: String(size) }));
  const png = await sharp(Buffer.from(svg)).png().toBuffer();
  return 'image/png;base64,' + png.toString('base64');
}

// pptxgenjs 不能写主题颜色：生成后把 THEME.colors 写进 ppt/theme/theme1.xml（与 PowerPoint "设计 → 变体 → 颜色"等效）
async function writeThemeColors(file) {
  const JSZip = require(require.resolve('jszip', { paths: [require.resolve('pptxgenjs')] }));
  const zip = await JSZip.loadAsync(fs.readFileSync(file));
  const part = 'ppt/theme/theme1.xml';
  const slots = ['dk1', 'lt1', 'dk2', 'lt2', 'accent1', 'accent2', 'accent3', 'accent4', 'accent5', 'accent6', 'hlink', 'folHlink'];
  const scheme = `<a:clrScheme name="${THEME.name}">` + slots.map((k) => `<a:${k}><a:srgbClr val="${HEX[k]}"/></a:${k}>`).join('') + '</a:clrScheme>';
  const xml = (await zip.file(part).async('string'))
    .replace(/<a:clrScheme\b[\s\S]*?<\/a:clrScheme>/, () => scheme)
    .replace(/(<a:(?:theme|fontScheme)\b[^>]*?\bname=")[^"]*"/g, (_, head) => `${head}${THEME.name}"`)
    // 中文字体：pptxgenjs 只写了西文字体，中文会落到默认的"等线"，这里把东亚字体也设成主题字体
    .replace(/<a:ea typeface="[^"]*"\/>/g, `<a:ea typeface="${THEME.bodyFontFace}"/>`)
    .replace(/<a:font script="Hans" typeface="[^"]*"\/>/g, `<a:font script="Hans" typeface="${THEME.bodyFontFace}"/>`);
  zip.file(part, xml);
  fs.writeFileSync(file, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
}

async function main() {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_WIDE';                    // 13.33 × 7.5 英寸
  pres.title = 'MiniDiDi 缩微滴滴：多智能体无人出租车系统';
  pres.subject = '综合设计与实践 C 期末答辩';
  pres.author = 'MiniDiDi 小组';
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  const C = pres.SchemeColor;

  // ---------------------------------------------------------------- 版式
  pres.defineSlideMaster({
    title: 'TITLE_DARK',
    background: { color: C.text1 },
    objects: [
      { placeholder: { options: { name: 'kicker', type: 'body', x: X0, y: 1.3, w: 6.0, h: 0.45, fontSize: 16, bold: true, color: C.accent1, valign: 'top', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'title', type: 'title', x: X0, y: 1.85, w: 6.1, h: 1.75, fontSize: 36, bold: true, color: C.background1, align: 'left', valign: 'top', margin: 0, lineSpacingMultiple: 1.05 }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', x: X0, y: 3.85, w: 5.8, h: 1.0, fontSize: 15, color: C.background2, valign: 'top', margin: 0, lineSpacingMultiple: 1.2 }, text: '' } },
      { placeholder: { options: { name: 'info', type: 'body', x: X0, y: 5.3, w: 6.0, h: 1.3, fontSize: 13, color: C.accent5, valign: 'top', margin: 0, lineSpacingMultiple: 1.25 }, text: '' } },
    ],
  });
  pres.defineSlideMaster({
    title: 'CONTENT',
    background: { color: C.background1 },
    margin: [0.5, X0, 0.6, X0],
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: X0, y: 0.4, w: CW, h: 0.65, fontSize: 30, bold: true, color: C.text1, align: 'left', valign: 'middle', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'sub', type: 'body', x: X0, y: 1.08, w: CW, h: 0.45, fontSize: 16, color: C.text2, valign: 'top', margin: 0 }, text: '' } },
      { text: { text: 'MiniDiDi 缩微滴滴 · 综合设计与实践 C · 第 1 组', options: { x: X0, y: 6.98, w: 8.0, h: 0.3, fontSize: 10, color: C.text2, margin: 0 } } },
    ],
    slideNumber: { x: W - X0 - 0.6, y: 6.98, w: 0.6, h: 0.3, fontSize: 10, color: C.text2, align: 'right' },
  });

  // 正文页标题：橙色页码 + 标题
  const title = (n, t) => [{ text: n, options: { color: C.accent1 } }, { text: '　' + t }];
  const shadow = () => ({ type: 'outer', blur: 10, offset: 3, angle: 90, color: '000000', opacity: 0.18 });

  function text(slide, t, opts) {
    slide.addText(t, { margin: 0, isTextBox: true, valign: 'top', color: C.text1, fontSize: 14, ...opts });
  }
  function card(slide, x, y, w, h, name, fill = C.background2, extra = {}) {
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.1, fill: { color: fill, ...extra }, line: { type: 'none' }, objectName: name });
  }
  // 全篇统一的"橙色圆 + 白色图标"
  async function badge(slide, Comp, x, y, d, name, fill = C.accent1) {
    slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { type: 'none' }, objectName: name + '-circle' });
    const pad = d * 0.25;
    slide.addImage({ data: await icon(Comp), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, objectName: name + '-icon', altText: name });
  }
  function arrow(slide, x, y, w, h, name, both = false, color = C.text2) {
    slide.addShape(pres.shapes.LINE, { x, y, w, h, line: { color, width: 1.5, endArrowType: 'triangle', ...(both ? { beginArrowType: 'triangle' } : {}) }, objectName: name });
  }
  function stat(slide, x, y, w, num, label, color, name, size = 40) {
    text(slide, num, { x, y, w, h: size / 60, fontSize: size, bold: true, color, valign: 'bottom', objectName: name + '-num' });
    text(slide, label, { x, y: y + size / 60 + 0.08, w, h: 0.62, fontSize: 13, color: C.text2, objectName: name + '-label' });
  }
  // 一行流程：圆角卡片 + 箭头，最后一步用深色强调
  function flow(slide, x, y, w, h, steps, name, fontSize = 14, gap = 0.35) {
    const cw = (w - gap * (steps.length - 1)) / steps.length;
    steps.forEach((t, i) => {
      const cx = x + i * (cw + gap);
      const last = i === steps.length - 1;
      card(slide, cx, y, cw, h, `${name}-step-${i}`, last ? C.accent3 : C.background2);
      text(slide, t, { x: cx + 0.05, y, w: cw - 0.1, h, fontSize, align: 'center', valign: 'middle', color: last ? C.background1 : C.text1, objectName: `${name}-step-${i}-text` });
      if (!last) arrow(slide, cx + cw + 0.06, y + h / 2, gap - 0.12, 0, `${name}-arrow-${i}`);
    });
  }

  // ---------------------------------------------------------------- 封面
  pres.addSection({ title: '封面' });
  let s = pres.addSlide({ masterName: 'TITLE_DARK', sectionTitle: '封面' });
  s.addText('综合设计与实践 C · 期末答辩', { placeholder: 'kicker' });
  s.addText('缩微滴滴\n多智能体无人出租车系统', { placeholder: 'title' });
  s.addText('城市里随时有人叫车，“系统超脑”负责派车、规划路径、管控交通；Matlab 车、手机和 Gazebo 小车都是它的数字孪生', { placeholder: 'body' });
  s.addText('第 1 组　指导教师：许春权\n组长：阮飞翔 2252753\n组员：樊宇泽 2351586　李昊博 2352853　付泽亮 2353413', { placeholder: 'info' });
  s.addImage({ path: img('docs/images/view3d.png'), x: 6.9, y: 1.74, w: W - 6.9, h: (W - 6.9) / 1.6, objectName: 'cover-3d', altText: '3D 视图截图：缩微城市与车辆' });
  s.addNotes('各位老师好，我们组的题目是缩微滴滴。我们做了一个“系统超脑”：城市里随时有人叫车，它负责派车、规划路径、管控交通；'
    + '车辆可以是服务端里的数字车，也可以是 Matlab 模拟车、手机和 Gazebo 小车，它们都是数字世界里的数字孪生。'
    + '整个系统一键启动之后全程自主运行，下面分五部分介绍。');

  // ---------------------------------------------------------------- 01 系统总体
  pres.addSection({ title: '正文' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText(title('01', '系统总体'), { placeholder: 'title' });
  s.addText('一个“系统超脑”统一调度四类设备：同一套 TCP 协议，任何设备断线都由数字世界接管', { placeholder: 'sub' });
  const devices = [
    [fa.FaGlobe, '2D / 3D 网页', 'HTTP 8080'],
    [fa.FaCar, 'Matlab 车队 ×5', 'TCP 9000'],
    [fa.FaMobileAlt, '安卓手机车', 'TCP 9000 + HTTP'],
    [fa.FaRobot, 'Gazebo 小车 ×5', 'ROS 2 桥接 → TCP'],
  ];
  const dw = (7.8 - 3 * 0.2) / 4;
  for (let i = 0; i < devices.length; i++) {
    const x = X0 + i * (dw + 0.2);
    card(s, x, Y0, dw, 1.3, `device-${i}`);
    await badge(s, devices[i][0], x + (dw - 0.5) / 2, Y0 + 0.13, 0.5, `device-${i}`);
    text(s, devices[i][1], { x, y: Y0 + 0.7, w: dw, h: 0.3, fontSize: 14, bold: true, align: 'center', objectName: `device-${i}-name` });
    text(s, devices[i][2], { x, y: Y0 + 0.99, w: dw, h: 0.25, fontSize: 12, color: C.text2, align: 'center', objectName: `device-${i}-proto` });
    arrow(s, x + dw / 2, Y0 + 1.36, 0, 0.32, `device-${i}-link`, true);
  }
  const sy = 3.55;
  card(s, X0, sy, 7.8, Y1 - sy, 'server', C.accent3);
  text(s, [{ text: '数字世界服务端', options: { fontSize: 18, bold: true, color: C.background1 } },
    { text: '　Java 17 · 只依赖 Gson · 20 Hz 单线程仿真', options: { fontSize: 13, color: C.accent5 } }],
  { x: X0 + 0.25, y: sy + 0.18, w: 7.3, h: 0.45, valign: 'middle', objectName: 'server-title' });
  const modules = [['派单', '匈牙利算法全局匹配'], ['路径规划', 'A*：格子 + 驶入方向'], ['交通管控', '格子预约 + 移动授权'],
    ['感知建图', '激光雷达 + 占据栅格'], ['图像识别', '颜色分割 + 针孔测距'], ['场景脚本', '按时间自动触发情节']];
  const mw = (7.3 - 2 * 0.2) / 3;
  for (let i = 0; i < modules.length; i++) {
    const x = X0 + 0.25 + (i % 3) * (mw + 0.2);
    const y = sy + 0.8 + Math.floor(i / 3) * 1.13;
    card(s, x, y, mw, 0.95, `module-${i}`, C.background1, { transparency: 88 });
    text(s, [{ text: modules[i][0], options: { fontSize: 15, bold: true, color: C.background1, breakLine: true } },
      { text: modules[i][1], options: { fontSize: 12, color: C.background2 } }],
    { x: x + 0.18, y, w: mw - 0.3, h: 0.95, valign: 'middle', objectName: `module-${i}-text` });
  }
  const pillars = [
    [fa.FaShieldAlt, '零碰撞由机制保证', '每辆车只能驶入授权给它的格子，任意两车的授权格互不相交'],
    [fa.FaSyncAlt, '断线自动接管', '设备 3 s 无消息，内部仿真接手驾驶，系统不停摆'],
    [fa.FaPlayCircle, '全自主运行', '一键启动；场景脚本按时间自动触发全部演示情节，考核中无需人工操作'],
  ];
  const px = 8.8; const pw = W - X0 - px; const ph = (Y1 - Y0 - 2 * 0.25) / 3;
  for (let i = 0; i < pillars.length; i++) {
    const y = Y0 + i * (ph + 0.25);
    card(s, px, y, pw, ph, `pillar-${i}`);
    await badge(s, pillars[i][0], px + 0.25, y + 0.22, 0.6, `pillar-${i}`, i === 0 ? C.accent2 : C.accent1);
    text(s, pillars[i][1], { x: px + 1.05, y: y + 0.22, w: pw - 1.3, h: 0.45, fontSize: 16, bold: true, valign: 'middle', objectName: `pillar-${i}-title` });
    text(s, pillars[i][2], { x: px + 1.05, y: y + 0.7, w: pw - 1.3, h: ph - 0.8, fontSize: 13, color: C.text2, objectName: `pillar-${i}-text` });
  }
  s.addNotes('系统由一个数字世界服务端和四类设备组成。网页走 HTTP，所有车辆设备走同一套 TCP 协议：注册、下发路线、下发授权、上报位姿。'
    + '服务端只有一个仿真线程能改世界状态，网络线程都通过命令队列排队，所以不需要加锁、结果可以复现。'
    + '右边是三个设计支柱：第一，安全靠机制保证——每辆车只能开进服务端授权给它的格子，任意两车的授权格不相交，所以不会相撞；'
    + '第二，任何设备断线，3 秒内由内部仿真接管；第三，演示中要发生的事都写在场景脚本里，考核时不需要碰电脑。');

  // ---------------------------------------------------------------- 02 派单与调度
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText(title('02', '派单与调度'), { placeholder: 'title' });
  s.addText('A* 规划路线，匈牙利算法求全局最优派单：接近饱和时平均等车时间减少 45%', { placeholder: 'sub' });
  const algos = [
    [fa.FaRoute, 'A* 路径规划', '状态 = 格子 + 驶入方向：遵守车道方向、禁止原地掉头；单行道被堵死时借对向车道掉头'],
    [fa.FaProjectDiagram, '匈牙利算法派单', '每 2 s 把“空车 × 订单”建成代价矩阵求全局最优匹配，代价 = 接驾距离 − 等待时间权重'],
    [fa.FaMapMarkedAlt, '空车调度', '9 × 9 格分区预测需求（τ = 180 s 衰减），把多余的空车提前调往即将缺车的区域'],
  ];
  const aw = 5.7; const ah = (Y1 - Y0 - 2 * 0.2) / 3;
  for (let i = 0; i < algos.length; i++) {
    const y = Y0 + i * (ah + 0.2);
    card(s, X0, y, aw, ah, `algo-${i}`);
    await badge(s, algos[i][0], X0 + 0.25, y + (ah - 0.6) / 2, 0.6, `algo-${i}`);
    text(s, algos[i][1], { x: X0 + 1.08, y: y + 0.17, w: aw - 1.3, h: 0.42, fontSize: 17, bold: true, valign: 'middle', objectName: `algo-${i}-title` });
    text(s, algos[i][2], { x: X0 + 1.08, y: y + 0.62, w: aw - 1.3, h: ah - 0.72, fontSize: 13, color: C.text2, objectName: `algo-${i}-text` });
  }
  const rx = X0 + aw + 0.4; const rw = W - X0 - rx;
  const loads = ['8 车 · 6 单/分', '16 车 · 12 单/分', '16 车 · 16 单/分'];
  s.addChart(pres.charts.BAR, [
    { name: '贪心（就近派车）', labels: loads, values: [33.0, 26.7, 70.4] },
    { name: '匈牙利（全局最优）', labels: loads, values: [28.5, 24.6, 38.6] },
  ], {
    x: rx, y: Y0, w: rw, h: 3.2, barDir: 'col', barGapWidthPct: 55,
    chartColors: [HEX.accent5, HEX.accent1],
    showTitle: true, title: '乘客平均等待时间（秒，30 分钟 × 3 个种子）', titleFontSize: 14, titleColor: HEX.dk1, titleFontFace: '+mn-lt',
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0.0', dataLabelFontSize: 12, dataLabelColor: HEX.dk1, dataLabelFontFace: '+mn-lt',
    showLegend: true, legendPos: 't', legendFontSize: 12, legendColor: HEX.dk2, legendFontFace: '+mn-lt',
    catAxisLabelColor: HEX.dk2, catAxisLabelFontSize: 12, catAxisLabelFontFace: '+mn-lt',
    valAxisLabelColor: HEX.dk2, valAxisLabelFontSize: 11, valAxisLabelFontFace: '+mn-lt', valAxisMaxVal: 80, valAxisMajorUnit: 20,
    valGridLine: { color: 'E1E5EA', size: 0.75 }, catGridLine: { style: 'none' },
    objectName: 'dispatch-chart',
  });
  const by = Y0 + 3.4;
  card(s, rx, by, rw, Y1 - by, 'dispatch-stats');
  const stats2 = [['−45%', '16 车 16 单/分时的平均等待\n匈牙利 38.6 s，贪心 70.4 s'], ['−20%', '潮汐需求下开启空车调度\n4 单/分，8 个种子配对比较']];
  for (let i = 0; i < stats2.length; i++) {
    stat(s, rx + 0.3 + i * (rw / 2), by + 0.15, rw / 2 - 0.4, stats2[i][0], stats2[i][1], C.accent1, `stat2-${i}`, 36);
  }
  s.addNotes('路径规划用 A*，状态是格子加驶入方向，这样能加转弯代价、禁止原地掉头；单行道被堵死时借对向车道掉头。'
    + '派单每 2 秒把空车和等待订单建成代价矩阵，用匈牙利算法求全局最优匹配。'
    + '右边是配对对比实验：同一组种子、完全相同的订单序列。订单稀少时两种策略差不多，接近饱和时匈牙利算法把平均等待从 70 秒降到 39 秒，少了 45%。'
    + '潮汐需求下再开启空车调度，平均等待又少了 20%。');

  // ---------------------------------------------------------------- 03 安全与感知
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText(title('03', '安全与感知'), { placeholder: 'title' });
  s.addText('车辆只能驶入服务端授权的格子；未知障碍由每辆车的模拟激光雷达发现，再写入全局地图', { placeholder: 'sub' });
  const iw = 6.4;
  s.addImage({ path: img('docs/images/overview.png'), x: X0, y: Y0, w: iw, h: iw / 1.6, objectName: 'overview-2d', altText: '2D 主界面截图', shadow: shadow() });
  text(s, '2D 主界面：车前方的浅色格子是行驶授权，任意两车互不重叠；扇形为激光雷达扫描范围', {
    x: X0, y: Y0 + iw / 1.6 + 0.12, w: iw, h: 0.6, fontSize: 12, color: C.text2, objectName: 'overview-caption',
  });
  const qx = X0 + iw + 0.35; const qw = W - X0 - qx; const qh = (Y1 - Y0 - 0.25) / 2;
  // 面板 A：移动授权示意（一条车道上的格子：A、B 两车各自的授权互不相交）
  card(s, qx, Y0, qw, qh, 'permit-panel');
  text(s, '移动授权：一格一车', { x: qx + 0.3, y: Y0 + 0.16, w: qw - 0.6, h: 0.4, fontSize: 16, bold: true, valign: 'middle', objectName: 'permit-title' });
  const n = 11; const cs = (qw - 0.6) / n; const ry = Y0 + 0.72;
  s.addShape(pres.shapes.RECTANGLE, { x: qx + 0.3, y: ry - 0.06, w: qw - 0.6, h: cs + 0.12, fill: { color: C.text2 }, line: { type: 'none' }, objectName: 'permit-road' });
  const kind = (k) => (k === 0 ? 'carA' : k <= 3 ? 'A' : k === 6 ? 'carB' : k <= 8 && k >= 7 ? 'B' : 'free');
  for (let k = 0; k < n; k++) {
    const t = kind(k);
    const fill = { carA: C.accent1, A: C.accent4, carB: C.accent2, B: C.accent6, free: C.background1 }[t];
    s.addShape(pres.shapes.RECTANGLE, { x: qx + 0.3 + k * cs + 0.03, y: ry, w: cs - 0.06, h: cs, fill: { color: fill, ...(t === 'free' ? { transparency: 70 } : {}) }, line: { type: 'none' }, objectName: `permit-cell-${k}` });
    if (t === 'carA' || t === 'carB') {
      s.addImage({ data: await icon(fa.FaCarSide), x: qx + 0.3 + k * cs + cs * 0.15, y: ry + cs * 0.15, w: cs * 0.7, h: cs * 0.7, objectName: `permit-${t}`, altText: t === 'carA' ? '车 A' : '车 B' });
    }
  }
  // A 的停车点：授权终点前留出车身半径 + 余量
  s.addShape(pres.shapes.RECTANGLE, { x: qx + 0.3 + 4 * cs - 0.14, y: ry - 0.12, w: 0.05, h: cs + 0.24, fill: { color: C.accent1 }, line: { type: 'none' }, objectName: 'permit-stop' });
  text(s, 'A 的授权', { x: qx + 0.3 + cs, y: ry + cs + 0.12, w: 3 * cs, h: 0.3, fontSize: 13, bold: true, align: 'center', objectName: 'permit-label-a' });
  text(s, 'B 的授权', { x: qx + 0.3 + 7 * cs, y: ry + cs + 0.12, w: 2 * cs, h: 0.3, fontSize: 13, bold: true, align: 'center', objectName: 'permit-label-b' });
  text(s, '橙线是 A 的停车点；路口整段 + 出口格一次申请，不堵路口；受阻 3 s 优先、12 s 绕行，没有死锁', {
    x: qx + 0.3, y: ry + cs + 0.5, w: qw - 0.6, h: Y0 + qh - (ry + cs + 0.5) - 0.1, fontSize: 13, color: C.text2, objectName: 'permit-text',
  });
  // 面板 B：感知建图流程
  const by3 = Y0 + qh + 0.25;
  card(s, qx, by3, qw, qh, 'sense-panel');
  text(s, '感知建图：发现未知障碍', { x: qx + 0.3, y: by3 + 0.16, w: qw - 0.6, h: 0.4, fontSize: 16, bold: true, valign: 'middle', objectName: 'sense-title' });
  flow(s, qx + 0.3, by3 + 0.68, qw - 0.6, 0.78, ['激光雷达\n48 束 3 m', '占据栅格\n累计证据', '写入地图\n收回授权', '车辆绕行\n或掉头'], 'sense', 13, 0.22);
  stat(s, qx + 0.3, by3 + 1.5, 2.3, '30.9 s', '障碍出现到被发现（平均）', C.accent2, 'sense-stat-0', 24);
  stat(s, qx + 0.3 + (qw - 0.6) / 2, by3 + 1.5, 2.3, '0 误检', '车停在障碍前 0.08 m', C.accent2, 'sense-stat-1', 24);
  s.addNotes('左边是 2D 主界面。车前方的浅色格子就是服务端给它的行驶授权，任意两车的授权互不重叠。'
    + '右上是原理：车 A 只能开到自己授权的最后一格，并在橙线处停下，B 的格子 A 永远申请不到，所以两车不会相撞；'
    + '路口整段一次申请，不会堵在路口里；受阻 3 秒优先、12 秒绕行，不会死锁。'
    + '右下是感知：服务端把真实世界和系统认知分开，场景脚本放出的施工围挡系统一开始并不知道，要靠每辆车的模拟激光雷达发现，'
    + '证据累计够了就写入全局地图，收回经过它的授权，车辆绕行或掉头。平均 30.9 秒发现，零误检。');

  // ---------------------------------------------------------------- 04 手机与端云互动
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText(title('04', '手机与端云互动'), { placeholder: 'title' });
  s.addText('按课程要求手机模拟一辆车：路线由服务端规划，人走路的速度就是油门', { placeholder: 'sub' });
  const lw = 7.7;
  text(s, '走路驾驶：手机 = 模拟车 car-6', { x: X0, y: Y0, w: lw, h: 0.4, fontSize: 16, bold: true, valign: 'middle', objectName: 'drive-label' });
  flow(s, X0, Y0 + 0.5, lw, 1.0, ['加速度计步\nWeinberg 步长', '走路速度\n= 步长 × 步频', '× 驾驶比例\n= 期望车速', '沿路线前进\n不越过停车点'], 'drive', 14);
  text(s, '端云互动：手机只拍照，重型图像处理在服务端', { x: X0, y: Y0 + 1.75, w: lw, h: 0.4, fontSize: 16, bold: true, valign: 'middle', objectName: 'vision-label' });
  flow(s, X0, Y0 + 2.25, lw, 1.0, ['手机拍照\n上传', 'HSV 分割\n+ 连通域', '针孔模型\n测距落格', '写入地图\n车辆绕行'], 'vision', 14);
  const stats4 = [['≤ 1 步', '计步误差（慢走、快走、\n竖拿、斜拿）'], ['0.10 m', '手机与服务端看到的\n车位置平均相差'], ['1.4×', '大图并行加速（2 核）\n端到端识别约 30 ms']];
  for (let i = 0; i < stats4.length; i++) {
    stat(s, X0 + i * (lw / 3), Y0 + 3.55, lw / 3 - 0.2, stats4[i][0], stats4[i][1], C.accent1, `stat4-${i}`, 32);
  }
  const mx = X0 + lw + 0.4; const mw4 = W - X0 - mx;
  s.addImage({ path: img('phone/docs/minimap.png'), x: mx, y: Y0, w: mw4, h: mw4 / 1.7125, objectName: 'phone-minimap', altText: '手机 App 小地图截图：我的车与路线', shadow: shadow() });
  text(s, 'App 小地图：我的车、路线和授权停车点', { x: mx, y: Y0 + mw4 / 1.7125 + 0.1, w: mw4, h: 0.3, fontSize: 12, color: C.text2, objectName: 'minimap-caption' });
  const notes4 = [
    [fa.FaWalking, '走快车快、停下车停；受弯道限速和刹车曲线约束，断线 3 s 由内部仿真接管'],
    [fa.FaCompass, '陀螺仪航向 + 零偏估计，室外融合磁力计与北斗 / GPS，估计并记录人的位姿'],
  ];
  for (let i = 0; i < notes4.length; i++) {
    const y = Y0 + 2.95 + i * 1.0;
    await badge(s, notes4[i][0], mx, y + 0.1, 0.55, `phone-note-${i}`);
    text(s, notes4[i][1], { x: mx + 0.75, y, w: mw4 - 0.75, h: 0.85, fontSize: 13, color: C.text2, valign: 'middle', objectName: `phone-note-${i}-text` });
  }
  s.addNotes('按课程要求，手机用来模拟一辆车：App 注册后绑定 car-6，路线由服务端规划，人只管拿着手机走路。'
    + 'App 用加速度计步、Weinberg 模型估步长，走路速度乘驾驶比例就是期望车速，车沿路线前进，同样受限速和刹车曲线约束、不越过授权停车点，每秒上报 10 次车的位姿。'
    + '人走得快车就快，停下车就停；断线 3 秒由内部仿真接管。陀螺仪、磁力计和卫星用来估计并记录人的位姿，供精度分析。'
    + '端云互动：手机只负责拍照上传，服务端做颜色分割、连通域和针孔模型测距，把路障写进全局地图，附近的车随即绕行。'
    + '颜色分割和开运算按行并行，大图在两核机器上加速约 1.4 倍。');

  // ---------------------------------------------------------------- 05 挑战与评估
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText(title('05', '挑战与评估'), { placeholder: 'title' });
  s.addText('课程的六个挑战项全部完成；16 车高负载运行 6 小时零碰撞', { placeholder: 'sub' });
  const gw = 6.1;
  s.addImage({ path: img('docs/images/gazebo_pair.jpg'), x: X0, y: Y0, w: gw, h: gw / 3.2267, objectName: 'gazebo-shot', altText: 'Gazebo 截图：俯视与跟车视角', shadow: shadow() });
  text(s, 'Gazebo Harmonic 中的 5 辆差速小车（ROS 2 桥接）：俯视与跟车视角', { x: X0, y: Y0 + gw / 3.2267 + 0.1, w: gw, h: 0.3, fontSize: 12, color: C.text2, objectName: 'gazebo-caption' });
  const challenges = [[fa.FaCube, '3D 可视化'], [fa.FaSatelliteDish, '感知建图'], [fa.FaCloudUploadAlt, '端云互动'],
    [fa.FaTachometerAlt, '图像加速'], [fa.FaRobot, 'Gazebo'], [fa.FaMapMarkedAlt, '空车调度']];
  const chw = (gw - 2 * 0.2) / 3; const chy = 4.3; const chh = (Y1 - chy - 0.2) / 2;
  for (let i = 0; i < challenges.length; i++) {
    const x = X0 + (i % 3) * (chw + 0.2);
    const y = chy + Math.floor(i / 3) * (chh + 0.2);
    card(s, x, y, chw, chh, `challenge-${i}`);
    await badge(s, challenges[i][0], x + 0.18, y + (chh - 0.5) / 2, 0.5, `challenge-${i}`);
    text(s, challenges[i][1], { x: x + 0.8, y, w: chw - 0.85, h: chh, fontSize: 14, bold: true, valign: 'middle', objectName: `challenge-${i}-text` });
  }
  const sx = X0 + gw + 0.4; const sw = (W - X0 - sx - 0.25) / 2; const sh = (Y1 - Y0 - 0.25) / 2;
  const stats5 = [
    ['0', '16 车高负载演示场景\n运行 6 × 60 分钟的碰撞次数'],
    ['105', '项单元测试全部通过\n服务端 30、手机 66、Gazebo 9'],
    ['0.2 s', '断开整个车队后\n全部由数字世界接管'],
    ['0 次', '考核中的人工操作\n（场景脚本自动触发）'],
  ];
  for (let i = 0; i < stats5.length; i++) {
    const x = sx + (i % 2) * (sw + 0.25);
    const y = Y0 + Math.floor(i / 2) * (sh + 0.25);
    card(s, x, y, sw, sh, `stat5-${i}`);
    stat(s, x + 0.35, y + 0.35, sw - 0.6, stats5[i][0], stats5[i][1], C.accent2, `stat5-${i}`, 44);
  }
  s.addNotes('挑战项我们做了六个：自写 WebGL 的 3D 视图、感知与辅助建图、端云互动、图像处理加速、Gazebo 物理仿真小车、需求预测空车调度。'
    + '评估方面：16 辆车、每分钟 16 单的高负载，运行带路障和未知障碍的演示场景 6 次、每次 60 分钟，碰撞全部为零；'
    + '105 项单元测试全部通过；系统集成测试中断开整个车队，0.2 秒内全部由数字世界接管。'
    + '最后，考核过程中不需要任何人工操作，所有情节都由场景脚本按时间自动触发。');

  // ---------------------------------------------------------------- 结尾
  pres.addSection({ title: '结尾' });
  s = pres.addSlide({ masterName: 'TITLE_DARK', sectionTitle: '结尾' });
  s.addText('感谢聆听', { placeholder: 'kicker' });
  s.addText('谢谢！\n请老师指正', { placeholder: 'title' });
  s.addText('接下来是现场演示：一键启动，全程无人操作', { placeholder: 'body' });
  s.addText('源码、技术报告、测试与评估数据：\ngithub.com/fuzeliang1/ZongC', { placeholder: 'info' });
  s.addImage({ path: img('docs/images/gazebo_chase.jpg'), x: 6.9, y: 1.74, w: W - 6.9, h: (W - 6.9) / 1.6, objectName: 'closing-gazebo', altText: 'Gazebo 跟车视角' });
  s.addNotes('我们的介绍就到这里，接下来是现场演示：整个系统一键启动，演示过程中我们不碰电脑。谢谢各位老师。');

  // ---------------------------------------------------------------- 附录（备用页，答辩问答时翻到）
  pres.addSection({ title: '附录' });
  const th = (t) => ({ text: t, options: { bold: true, color: C.background1, fill: { color: C.accent3 } } });
  const zebra = (rows) => rows.map((r, i) => r.map((c) => (typeof c === 'object' && !Array.isArray(c) && c.text !== undefined
    ? { text: c.text, options: { fill: { color: i % 2 ? C.background1 : C.background2 }, ...c.options } }
    : { text: c, options: { fill: { color: i % 2 ? C.background1 : C.background2 } } })));
  const tableOpts = (y, colW, fontSize = 13) => ({
    x: X0, y, w: CW, colW, fontSize, color: C.text1, valign: 'middle', margin: [0.06, 0.12, 0.06, 0.12],
    border: { type: 'solid', pt: 1, color: C.background1 },
  });

  // 附录 1：课程要求完成情况
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '附录' });
  s.addText(title('附录 1', '课程要求完成情况'), { placeholder: 'title' });
  s.addText('必做 7 项、挑战 5 项：除手机 App 待真机实测外，全部完成并经测试验证', { placeholder: 'sub' });
  const done = (rest) => [{ text: '已完成', options: { bold: true, color: C.accent2 } }, { text: '：' + rest }];
  const req = [
    ['必做', '数字世界：≥ 5 辆数字车、随机运输任务', done('默认 8 辆可配置，泊松到达订单，完整接送流程')],
    ['必做', '基于最优化的路径规划', done('A* 最短路，匈牙利算法全局最优派单')],
    ['必做', '人机交互与动态显示', done('2D 实时地图、指标面板、配置与控制')],
    ['必做', '不与静态 / 动态障碍碰撞', done('移动授权交通管控，长时间高负载零碰撞')],
    ['必做', 'Matlab 模拟车 ≥ 5 辆并与数字车同步', done('CI 中 MATLAB R2024b 运行，5 车零碰撞、零越权')],
    ['必做', '手机 App：读传感器、无线同步、估计位姿', [{ text: '基本完成', options: { bold: true, color: C.accent1 } }, { text: '：核心算法仿真验证通过，APK 已编译，待真机实测' }]],
    ['必做', '整体集成、全自主运行', done('一键启动，场景脚本自动触发，断线自动接管')],
    ['挑战', '3D 渲染可视化', done('自写 WebGL 渲染器，四种视角')],
    ['挑战', '环境感知与辅助建图、端云互动', done('模拟激光雷达 + 占据栅格；手机拍照、服务端识别')],
    ['挑战', '服务端图像处理加速模块', done('按行并行，大图加速 1.26~1.41 倍')],
    ['挑战（进阶）', 'Gazebo 中 ≥ 5 辆车', done('5 辆差速小车接入，无界面模式零碰撞')],
    ['挑战（自选）', '调度优化与定量评估', done('空车调度，配对对比实验')],
  ];
  s.addTable([[th('档次'), th('要求'), th('完成情况')], ...zebra(req.map((r) => [r[0], r[1], { text: r[2] }]))],
    { ...tableOpts(Y0, [1.7, 4.4, CW - 6.1], 13), rowH: 0.37 });
  s.addNotes('备用页：课程要求逐项对照。必做里只有手机 App 还差真机实测，代码和仿真验证都已完成。');

  // 附录 2：交通管控规则
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '附录' });
  s.addText(title('附录 2', '交通管控规则'), { placeholder: 'title' });
  s.addText('思路来自铁路的移动闭塞（CBTC）：授权格互不相交，所以任意两车不可能重叠', { placeholder: 'sub' });
  const rules = [
    ['一格一车', '每个格子同一时刻只属于一辆车：车身覆盖的格子和已授权的前方格子'],
    ['只驶入已授权的格子', '在授权终点前留出“车身半径 + 余量”停车，内部车和外部设备都遵守'],
    ['不堵路口', '路口整段加出口格必须一次申请到，否则一格也不给'],
    ['公平', '按受阻时间排序申请；受阻超过 3 s 的车可以先占住它需要的格子'],
    ['绕行', '受阻超过 12 s，给挡路的格子加惩罚后重新规划'],
    ['障碍收回授权', '路障或感知发现的障碍出现在已授权的格子里时，授权立即收回到它之前'],
  ];
  const rw2 = (CW - 2 * 0.3) / 3; const rh2 = 1.75;
  for (let i = 0; i < rules.length; i++) {
    const x = X0 + (i % 3) * (rw2 + 0.3);
    const y = Y0 + Math.floor(i / 3) * (rh2 + 0.25);
    card(s, x, y, rw2, rh2, `rule-${i}`);
    s.addShape(pres.shapes.OVAL, { x: x + 0.25, y: y + 0.22, w: 0.5, h: 0.5, fill: { color: C.accent1 }, line: { type: 'none' }, objectName: `rule-${i}-circle` });
    text(s, String(i + 1), { x: x + 0.25, y: y + 0.22, w: 0.5, h: 0.5, fontSize: 16, bold: true, color: C.background1, align: 'center', valign: 'middle', objectName: `rule-${i}-num` });
    text(s, rules[i][0], { x: x + 0.9, y: y + 0.22, w: rw2 - 1.1, h: 0.5, fontSize: 16, bold: true, valign: 'middle', objectName: `rule-${i}-title` });
    text(s, rules[i][1], { x: x + 0.25, y: y + 0.88, w: rw2 - 0.5, h: rh2 - 1.0, fontSize: 13, color: C.text2, objectName: `rule-${i}-text` });
  }
  const ay = Y0 + 2 * rh2 + 0.5;
  card(s, X0, ay, CW, Y1 - ay, 'safety-proof', C.accent4);
  text(s, [{ text: '安全性论证　', options: { bold: true } },
    { text: '车只在自己的授权格内运动，并在授权边界前留出车身半径 + 余量，所以两车车身不可能重叠；规则 3 保证路口内不会互相卡死，规则 4、5 保证受阻的车最终能通过或绕行。' }],
  { x: X0 + 0.3, y: ay, w: CW - 0.6, h: Y1 - ay, fontSize: 14, valign: 'middle', objectName: 'safety-proof-text' });
  s.addNotes('备用页：交通管控的六条规则和安全性论证。碰撞监测模块独立于交通管控、按真实几何计数，作为验证，所有实验都是零碰撞。');

  // 附录 3：评估与测试数据
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '附录' });
  s.addText(title('附录 3', '评估与测试数据'), { placeholder: 'title' });
  s.addText('离线仿真做配对比较（同一组种子、完全相同的订单序列），全部实验零碰撞', { placeholder: 'sub' });
  const evals = [
    ['派单策略', '16 车，16 单/分，30 分钟 × 3 个种子', '平均等待 70.4 → 38.6 s（−45%），送达效率 +13%'],
    ['空车调度', '潮汐需求，8 车，30 分钟 × 8 个种子', '4 单/分 27.9 → 22.3 s（−20%）；5 单/分 −18%'],
    ['感知建图', '4 处未知障碍陆续出现、消失', '平均 30.9 s 发现，零误检；停在障碍前 0.08 m'],
    ['安全性', '16 车演示场景，60 分钟 × 6 次', '车车 / 撞墙 / 撞障碍 0 / 0 / 0，最小车距 0.28 m'],
    ['图像加速', '1280×960 以上的照片，2 核机器', '并行加速 1.26~1.41 倍；端到端识别约 30 ms'],
    ['位姿估计', '传感器模拟器，多种握持方式', '计步误差 ≤ 1 步；9 m 正方形闭合误差 0.6 m'],
    ['单元测试', '服务端 / 手机核心 / Gazebo 桥接', '30 / 66 / 9 项，全部通过'],
    ['集成测试', '5 辆车 + 手机车，断线、拍照识别', '全部通过；断开车队后 0.2 s 内接管'],
    ['持续集成', 'Linux / Windows / Android / MATLAB', '每次推送自动运行，main 上四组任务全部通过'],
  ];
  s.addTable([[th('项目'), th('设置'), th('结果')], ...zebra(evals.map((r) => [{ text: r[0], options: { bold: true } }, r[1], r[2]]))],
    { ...tableOpts(Y0, [1.9, 4.5, CW - 6.4], 14), rowH: 0.47 });
  s.addNotes('备用页：评估和测试的关键数据，详细表格见技术报告第 6、7 章。');

  // 附录 4：小组分工与进度
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '附录' });
  s.addText(title('附录 4', '小组分工与进度'), { placeholder: 'title' });
  s.addText('4 人分工明确；第 4 周打通“服务端 + Matlab + 手机”骨架，之后每周在能运行的系统上迭代', { placeholder: 'sub' });
  const members = [
    ['阮飞翔（组长）', '架构与调度', '总体架构、接口协议、派单与空车调度、联调；立项与需求文档、报告统稿'],
    ['樊宇泽', '服务端', '数字世界与交通管控、设备接入、感知建图、图像处理加速；设计文档、测试报告'],
    ['李昊博', '控制与可视化', 'Matlab 模拟车、2D 界面与 3D 可视化；部署手册、答辩 PPT、演示视频'],
    ['付泽亮', '移动端与进阶', '安卓 App 与位姿估计、Gazebo 接入；效果评估报告'],
  ];
  const mcw = 5.5; const mch = (Y1 - Y0 - 3 * 0.17) / 4;
  for (let i = 0; i < members.length; i++) {
    const y = Y0 + i * (mch + 0.17);
    card(s, X0, y, mcw, mch, `member-${i}`);
    text(s, members[i][0], { x: X0 + 0.25, y: y + 0.12, w: 2.2, h: 0.4, fontSize: 16, bold: true, valign: 'middle', objectName: `member-${i}-name` });
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: X0 + mcw - 1.85, y: y + 0.15, w: 1.6, h: 0.34, rectRadius: 0.17, fill: { color: C.accent3 }, line: { type: 'none' }, objectName: `member-${i}-role-pill` });
    text(s, members[i][1], { x: X0 + mcw - 1.85, y: y + 0.15, w: 1.6, h: 0.34, fontSize: 12, color: C.background1, align: 'center', valign: 'middle', objectName: `member-${i}-role` });
    text(s, members[i][2], { x: X0 + 0.25, y: y + 0.55, w: mcw - 0.5, h: mch - 0.62, fontSize: 13, color: C.text2, objectName: `member-${i}-text` });
  }
  // 16 周甘特图（原生形状）
  const gx = X0 + mcw + 0.35; const gwid = W - X0 - gx;
  card(s, gx, Y0, gwid, Y1 - Y0, 'gantt-panel');
  const lab = 1.75; const bx = gx + 0.2 + lab; const bw = gwid - 0.4 - lab; const wk = bw / 16;
  for (let k = 1; k <= 16; k++) {
    text(s, String(k), { x: bx + (k - 1) * wk, y: Y0 + 0.15, w: wk, h: 0.3, fontSize: 11, color: C.text2, align: 'center', valign: 'middle', objectName: `gantt-week-${k}` });
  }
  text(s, '周次', { x: gx + 0.2, y: Y0 + 0.15, w: lab, h: 0.3, fontSize: 11, color: C.text2, valign: 'middle', objectName: 'gantt-week-label' });
  const phases = [['立项与设计', 1, 3], ['打通骨架', 3, 4], ['完成必做', 5, 8], ['冲优（挑战项）', 9, 12], ['加固与评估', 13, 14], ['收尾与答辩', 15, 16]];
  const rowH = 0.52;
  for (let i = 0; i < phases.length; i++) {
    const y = Y0 + 0.6 + i * rowH;
    const [name, a, b] = phases[i];
    text(s, name, { x: gx + 0.2, y, w: lab, h: rowH, fontSize: 13, valign: 'middle', objectName: `gantt-phase-${i}` });
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: bx + (a - 1) * wk + 0.03, y: y + 0.13, w: (b - a + 1) * wk - 0.06, h: rowH - 0.26, rectRadius: 0.08, fill: { color: i === 3 ? C.accent1 : C.accent3 }, line: { type: 'none' }, objectName: `gantt-bar-${i}` });
  }
  const my = Y0 + 0.6 + phases.length * rowH + 0.12;
  text(s, '里程碑', { x: gx + 0.2, y: my, w: lab, h: 0.4, fontSize: 13, bold: true, valign: 'middle', objectName: 'gantt-milestone-label' });
  const milestones = [[4, '全系统联调'], [8, '中期检查'], [16, '期末答辩']];
  for (let i = 0; i < milestones.length; i++) {
    const [wkn, name] = milestones[i];
    const cx = bx + (wkn - 0.5) * wk;
    s.addShape(pres.shapes.DIAMOND, { x: cx - 0.16, y: my + 0.04, w: 0.32, h: 0.32, fill: { color: C.accent2 }, line: { type: 'none' }, objectName: `gantt-milestone-${i}` });
    const lx = Math.min(cx - 0.6, bx + bw - 1.2);
    text(s, `第 ${wkn} 周\n${name}`, { x: lx, y: my + 0.4, w: 1.2, h: 0.55, fontSize: 12, color: C.text2, align: lx < cx - 0.6 ? 'right' : 'center', objectName: `gantt-milestone-${i}-text` });
  }
  s.addNotes('备用页：分工与 16 周进度。橙色是冲优阶段，做了 3D、感知建图与图像识别、空车调度和 Gazebo。');

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  await pres.writeFile({ fileName: OUT });
  await writeThemeColors(OUT);
  console.log('已生成', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
