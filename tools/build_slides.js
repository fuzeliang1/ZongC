// 生成答辩介绍 PPT（封面 + 正文 5 页 + 结尾）：deliverables/MiniDiDi_slides.pptx
//
// 用法（仓库根目录）：
//   npm install pptxgenjs@3.12.0 react-icons@5.4.0 react@18.3.1 react-dom@18.3.1 sharp@0.33.5   # 装在任意目录均可
//   NODE_PATH=<上面那个目录>/node_modules node tools/build_slides.js
//
// 课程要求"正文不超过 5 页"。改文字直接改下面每页的内容；配色和字体集中在 THEME 里。
// 每页都写了演讲备注（PowerPoint 的"备注"栏），5 页正文约 5 分钟。
// 数据来自 docs/eval/summary.md 和 docs/test-report.md，重跑实验后记得同步这里的数字。
const fs = require('fs');
const path = require('path');
const pptxgen = require('pptxgenjs');
const React = require('react');
const ReactDOMServer = require('react-dom/server');
const sharp = require('sharp');
const fa = require('react-icons/fa');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'deliverables', 'MiniDiDi_slides.pptx');
const img = (p) => path.join(ROOT, p);

// 配色：柏油路深灰为主、出租车黄点睛，青绿表示"安全 / 通过"
const THEME = {
  name: 'MiniDiDi',
  headFontFace: 'Microsoft YaHei',
  bodyFontFace: 'Microsoft YaHei',
  colors: {
    dk1: '1E2732', lt1: 'FFFFFF', dk2: '4A5866', lt2: 'EEF1F4',
    accent1: 'F2B705', accent2: '1B998B', accent3: 'E4572E', accent4: '5B7083',
    accent5: '9AA5B1', accent6: '2E86AB', hlink: '2E86AB', folHlink: '5B7083',
  },
};
const HEX = THEME.colors;

async function icon(Comp, color = HEX.dk1, size = 256) {
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
    .replace(/(<a:(?:theme|fontScheme)\b[^>]*?\bname=")[^"]*"/g, (_, head) => `${head}${THEME.name}"`);
  zip.file(part, xml);
  fs.writeFileSync(file, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
}

async function main() {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_16x9';                    // 10 × 5.625 英寸
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
      { placeholder: { options: { name: 'title', type: 'title', x: 0.6, y: 1.25, w: 4.9, h: 1.5, fontSize: 30, bold: true, color: C.background1, align: 'left', valign: 'bottom', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', x: 0.6, y: 2.95, w: 4.9, h: 0.9, fontSize: 16, color: C.accent1, valign: 'top', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'info', type: 'body', x: 0.6, y: 4.3, w: 4.9, h: 0.8, fontSize: 12, color: C.background2, valign: 'top', margin: 0 }, text: '' } },
    ],
  });
  pres.defineSlideMaster({
    title: 'CONTENT',
    background: { color: C.background1 },
    margin: [0.5, 0.5, 0.5, 0.5],
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.5, y: 0.28, w: 9.0, h: 0.7, fontSize: 26, bold: true, color: C.text1, valign: 'middle', margin: 0 }, text: '' } },
      { text: { text: 'MiniDiDi 缩微滴滴 · 综合设计与实践 C', options: { x: 0.5, y: 5.22, w: 5.0, h: 0.3, fontSize: 10, color: C.text2, margin: 0 } } },
    ],
    slideNumber: { x: 9.0, y: 5.22, w: 0.5, h: 0.3, fontSize: 10, color: C.text2, align: 'right' },
  });

  // 一个"黄色圆形 + 深色图标"的组合，全篇统一使用
  async function badge(slide, Comp, x, y, d, name) {
    slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: C.accent1 }, line: { color: C.accent1 }, objectName: name + '-circle' });
    const pad = d * 0.24;
    slide.addImage({ data: await icon(Comp), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, objectName: name + '-icon', altText: name });
  }
  function card(slide, x, y, w, h, name, fill = C.background2) {
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { color: fill }, objectName: name });
  }

  // ---------------------------------------------------------------- 封面
  pres.addSection({ title: '封面' });
  let s = pres.addSlide({ masterName: 'TITLE_DARK', sectionTitle: '封面' });
  s.addText('缩微滴滴：多智能体无人出租车系统', { placeholder: 'title' });
  s.addText('MiniDiDi · 综合设计与实践 C 期末答辩', { placeholder: 'body' });
  s.addText('第 ___ 组　组长 阮飞翔 2252753\n组员 樊宇泽 2351586　李昊博 2352853　付泽亮 2353413', { placeholder: 'info' });
  s.addImage({ path: img('docs/images/view3d.png'), x: 5.75, y: 1.15, w: 3.85, h: 2.41, objectName: 'cover-3d', altText: '3D 视图截图' });
  s.addText('城市里有人叫车，“系统超脑”派车、规划路径、管控交通；Matlab、手机、Gazebo 小车都是它的数字孪生', {
    x: 5.75, y: 3.7, w: 3.85, h: 0.75, fontSize: 12, color: C.background2, margin: 0, isTextBox: true, objectName: 'cover-tagline',
  });
  s.addNotes('各位老师好，我们组的题目是缩微滴滴。我们做了一个“系统超脑”：城市里随时有人叫车，它负责派车、规划路径、管控交通；'
    + '车辆可以是服务端里的数字车，也可以是 Matlab 模拟车、手机和 Gazebo 小车，它们都是数字世界里的数字孪生。'
    + '整个系统一键启动之后全程自主运行，下面分五部分介绍。');

  // ---------------------------------------------------------------- 1 系统总体
  pres.addSection({ title: '正文' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText('系统总体：一个超脑，四类设备，同一套协议', { placeholder: 'title' });
  const devices = [
    [fa.FaGlobe, '2D/3D 网页', 'HTTP 8080'],
    [fa.FaCar, 'Matlab ×5', 'TCP 9000'],
    [fa.FaMobileAlt, '安卓手机', 'TCP + HTTP'],
    [fa.FaRobot, 'Gazebo ×5', 'ROS 2 桥接'],
  ];
  for (let i = 0; i < devices.length; i++) {
    const x = 0.5 + i * 2.3;
    card(s, x, 1.15, 2.1, 0.95, `device-${i}`);
    await badge(s, devices[i][0], x + 0.15, 1.33, 0.58, `device-${i}`);
    s.addText([{ text: devices[i][1], options: { bold: true, fontSize: 13, color: C.text1, breakLine: true } },
      { text: devices[i][2], options: { fontSize: 11, color: C.text2 } }],
    { x: x + 0.85, y: 1.2, w: 1.2, h: 0.85, valign: 'middle', margin: 0, isTextBox: true, objectName: `device-${i}-text` });
    s.addShape(pres.shapes.LINE, { x: x + 1.05, y: 2.12, w: 0, h: 0.26, line: { color: C.text2, width: 1.25, beginArrowType: 'triangle', endArrowType: 'triangle' }, objectName: `device-${i}-link` });
  }
  card(s, 0.5, 2.42, 9.0, 1.05, 'server', C.text1);
  s.addText([{ text: '数字世界服务端（Java 17，只依赖 Gson）', options: { bold: true, fontSize: 15, color: C.background1, breakLine: true } },
    { text: '20 Hz 单线程仿真 · 派单 · A* 规划 · 交通管控 · 感知建图 · 图像识别 · 场景脚本', options: { fontSize: 12, color: C.accent1 } }],
  { x: 0.75, y: 2.47, w: 8.5, h: 0.95, valign: 'middle', margin: 0, isTextBox: true, objectName: 'server-text' });
  const pillars = [
    [fa.FaShieldAlt, '零碰撞由机制保证', '格子预约 + 移动授权：任意两车的授权格互不相交'],
    [fa.FaSyncAlt, '断线自动接管', '设备 3 s 无消息，内部仿真接手，系统不停摆'],
    [fa.FaPlayCircle, '全自主运行', '一键启动；场景脚本按时间自动触发全部演示情节'],
  ];
  for (let i = 0; i < pillars.length; i++) {
    const x = 0.5 + i * 3.05;
    card(s, x, 3.68, 2.9, 1.35, `pillar-${i}`);
    await badge(s, pillars[i][0], x + 0.18, 3.85, 0.5, `pillar-${i}`);
    s.addText(pillars[i][1], { x: x + 0.8, y: 3.85, w: 2.0, h: 0.5, fontSize: 14, bold: true, color: C.text1, valign: 'middle', margin: 0, isTextBox: true, objectName: `pillar-${i}-title` });
    s.addText(pillars[i][2], { x: x + 0.18, y: 4.42, w: 2.6, h: 0.55, fontSize: 11, color: C.text2, valign: 'top', margin: 0, isTextBox: true, objectName: `pillar-${i}-text` });
  }
  s.addNotes('系统由一个数字世界服务端和四类设备组成。网页走 HTTP，所有车辆设备走同一套 TCP 协议：注册、下发路线、下发授权、上报位姿。'
    + '服务端只有一个仿真线程能改世界状态，网络线程都通过命令队列排队，所以不需要加锁、结果可以复现。'
    + '三个设计支柱：第一，安全靠机制保证——每辆车只能开进服务端授权给它的格子，任意两车的授权格不相交，所以不会相撞；'
    + '第二，任何设备断线，3 秒内由内部仿真接管；第三，演示中要发生的事都写在场景脚本里，考核时不需要碰电脑。');

  // ---------------------------------------------------------------- 2 数字世界与调度
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText('数字世界与调度：最优化让饱和时的等车时间少 45%', { placeholder: 'title' });
  const algos = [
    [fa.FaRoute, 'A* 路径规划', '状态 = 格子 + 驶入方向，遵守车道、禁止原地掉头；被堵死时借对向车道掉头'],
    [fa.FaProjectDiagram, '匈牙利算法派单', '每 2 s 求“空车 × 订单”全局最优匹配，代价 = 接驾距离 − 等待时间权重'],
    [fa.FaMapMarkedAlt, '空车调度', '分区需求预测（τ = 180 s 衰减），把多余空车提前调往缺车区域'],
  ];
  for (let i = 0; i < algos.length; i++) {
    const y = 1.2 + i * 1.3;
    card(s, 0.5, y, 4.3, 1.15, `algo-${i}`);
    await badge(s, algos[i][0], 0.68, y + 0.27, 0.6, `algo-${i}`);
    s.addText([{ text: algos[i][1], options: { bold: true, fontSize: 14, color: C.text1, breakLine: true } },
      { text: algos[i][2], options: { fontSize: 11, color: C.text2 } }],
    { x: 1.45, y: y + 0.08, w: 3.25, h: 1.0, valign: 'middle', margin: 0, isTextBox: true, objectName: `algo-${i}-text` });
  }
  s.addChart(pres.charts.BAR, [
    { name: '贪心', labels: ['8 车 6 单/分', '16 车 12 单/分', '16 车 16 单/分'], values: [33.0, 26.7, 70.4] },
    { name: '匈牙利', labels: ['8 车 6 单/分', '16 车 12 单/分', '16 车 16 单/分'], values: [28.5, 24.6, 38.6] },
  ], {
    x: 5.05, y: 1.15, w: 4.45, h: 2.75, barDir: 'col', barGapWidthPct: 60,
    chartColors: [HEX.accent5, HEX.accent1],
    showTitle: true, title: '乘客平均等待时间（秒，30 分钟 × 3 个种子）', titleFontSize: 11, titleColor: HEX.dk1, titleFontFace: '+mn-lt',
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0.0', dataLabelFontSize: 10, dataLabelColor: HEX.dk1, dataLabelFontFace: '+mn-lt',
    showLegend: true, legendPos: 't', legendFontSize: 10, legendColor: HEX.dk2, legendFontFace: '+mn-lt',
    catAxisLabelColor: HEX.dk2, catAxisLabelFontSize: 10, catAxisLabelFontFace: '+mn-lt',
    valAxisLabelColor: HEX.dk2, valAxisLabelFontSize: 9, valAxisLabelFontFace: '+mn-lt', valAxisMaxVal: 80,
    valGridLine: { color: 'E1E5EA', size: 0.75 }, catGridLine: { style: 'none' },
    objectName: 'dispatch-chart',
  });
  const stats2 = [['−45%', '接近饱和时平均等待\n（匈牙利 vs 贪心）'], ['−20%', '潮汐需求下开启空车调度\n（4 单/分，8 个种子配对）']];
  for (let i = 0; i < stats2.length; i++) {
    const x = 5.05 + i * 2.3;
    s.addText(stats2[i][0], { x, y: 4.05, w: 2.15, h: 0.55, fontSize: 30, bold: true, color: C.accent3, margin: 0, isTextBox: true, objectName: `stat2-${i}-num` });
    s.addText(stats2[i][1], { x, y: 4.58, w: 2.15, h: 0.5, fontSize: 10.5, color: C.text2, margin: 0, isTextBox: true, objectName: `stat2-${i}-label` });
  }
  s.addNotes('路径规划用 A*，状态是格子加驶入方向，这样能加转弯代价、禁止原地掉头；单行道被堵死时借对向车道掉头。'
    + '派单每 2 秒把空车和等待订单建成代价矩阵，用匈牙利算法求全局最优匹配。'
    + '右边是配对对比实验：同一组种子、完全相同的订单序列。订单稀少时两种策略差不多，接近饱和时匈牙利算法把平均等待从 70 秒降到 39 秒，少了 45%。'
    + '潮汐需求下再开启空车调度，平均等待又少了 20%。');

  // ---------------------------------------------------------------- 3 孪生车与感知避障
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText('孪生车与感知避障：授权保证安全，感知发现未知', { placeholder: 'title' });
  s.addImage({ path: img('docs/images/overview.png'), x: 0.5, y: 1.15, w: 5.0, h: 3.125, objectName: 'overview-2d', altText: '2D 主界面截图' });
  s.addText('2D 主界面：车前方的浅色格子是行驶授权，任意两车互不重叠；扇形为激光雷达扫描范围', {
    x: 0.5, y: 4.35, w: 5.0, h: 0.5, fontSize: 10.5, color: C.text2, margin: 0, isTextBox: true, objectName: 'overview-caption',
  });
  const twins = [
    [fa.FaCar, 'Matlab / Gazebo 孪生车', '服务端下发路线和停车点 stopS，设备纯追踪执行，速度 v ≤ √(2a·(stopS − s))'],
    [fa.FaSatelliteDish, '模拟激光雷达 + 占据栅格', '48 束 × 3 m × 10 Hz；确认后写入全局地图、收回授权、掉头绕行；平均 30.9 s 发现、零误检'],
    [fa.FaRobot, 'Gazebo 物理小车', '5 车 240 s：碰撞 0、越权 0，补偿里程计延迟后越过停车点 0 m'],
  ];
  for (let i = 0; i < twins.length; i++) {
    const y = 1.15 + i * 1.3;
    card(s, 5.75, y, 3.75, 1.15, `twin-${i}`);
    await badge(s, twins[i][0], 5.9, y + 0.29, 0.55, `twin-${i}`);
    s.addText([{ text: twins[i][1], options: { bold: true, fontSize: 13, color: C.text1, breakLine: true } },
      { text: twins[i][2], options: { fontSize: 10.5, color: C.text2 } }],
    { x: 6.6, y: y + 0.06, w: 2.8, h: 1.04, valign: 'middle', margin: 0, isTextBox: true, objectName: `twin-${i}-text` });
  }
  s.addNotes('Matlab 和 Gazebo 小车都是数字孪生：服务端下发路线和授权停车点 stopS，设备用纯追踪跟踪路线，速度上限保证总能在停车点前刹住。'
    + '服务端把真实世界和系统认知分开：场景脚本放出的施工围挡系统一开始并不知道，要靠每辆车的模拟激光雷达发现。'
    + '命中点并入占据栅格，确认后写入全局地图，收回经过它的授权，车辆重规划或掉头；障碍消失后也会被自动撤掉。'
    + 'Gazebo 里 5 辆物理小车接入后同样零碰撞——我们发现并补偿了里程计延迟和执行滞后，否则车会越过停车点 8 厘米。');

  // ---------------------------------------------------------------- 4 手机与端云互动
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText('手机与端云互动：人也是数字世界里的智能体', { placeholder: 'title' });
  function flow(slide, y, label, steps, name) {
    slide.addText(label, { x: 0.5, y, w: 5.6, h: 0.35, fontSize: 13, bold: true, color: C.text1, margin: 0, isTextBox: true, objectName: name + '-label' });
    const w = (5.6 - 0.25 * (steps.length - 1)) / steps.length;
    steps.forEach((t, i) => {
      const x = 0.5 + i * (w + 0.25);
      card(slide, x, y + 0.42, w, 0.72, `${name}-step-${i}`, i === steps.length - 1 ? C.text1 : C.background2);
      slide.addText(t, { x: x + 0.06, y: y + 0.42, w: w - 0.12, h: 0.72, fontSize: 11, align: 'center', valign: 'middle', margin: 0,
        color: i === steps.length - 1 ? C.background1 : C.text1, isTextBox: true, objectName: `${name}-step-${i}-text` });
      if (i < steps.length - 1) {
        slide.addShape(pres.shapes.LINE, { x: x + w + 0.03, y: y + 0.78, w: 0.19, h: 0, line: { color: C.text2, width: 1.25, endArrowType: 'triangle' }, objectName: `${name}-arrow-${i}` });
      }
    });
  }
  flow(s, 1.15, '惯性元件估计位姿（行人航位推算），10 Hz 上报地图坐标', ['加速度\n计步', 'Weinberg\n步长', '陀螺仪航向\n+ 零偏估计', '比例尺换算\n地图坐标'], 'pdr');
  flow(s, 2.5, '端云互动：手机只拍照，重型图像处理在服务端', ['手机拍照\n上传', 'HSV 分割\n+ 连通域', '针孔模型\n测距落格', '写入地图\n车辆绕行'], 'vision');
  const stats4 = [['≤ 1 步', '计步误差（多种握持）'], ['1.7%', '9 m 正方形闭合误差'], ['1.4×', '大图并行加速（2 核）']];
  for (let i = 0; i < stats4.length; i++) {
    const x = 0.5 + i * 1.95;
    s.addText(stats4[i][0], { x, y: 3.95, w: 1.8, h: 0.55, fontSize: 26, bold: true, color: C.accent3, margin: 0, isTextBox: true, objectName: `stat4-${i}-num` });
    s.addText(stats4[i][1], { x, y: 4.5, w: 1.8, h: 0.4, fontSize: 10.5, color: C.text2, margin: 0, isTextBox: true, objectName: `stat4-${i}-label` });
  }
  s.addImage({ path: img('phone/docs/minimap.png'), x: 6.35, y: 1.25, w: 3.15, h: 1.8, objectName: 'phone-minimap', altText: '手机 App 小地图截图' });
  await badge(s, fa.FaWalking, 6.35, 3.3, 0.5, 'phone-walk');
  s.addText('行人走到车前，服务端立即收回这辆车的授权，车停在行人前；人离开后继续行驶', {
    x: 7.0, y: 3.25, w: 2.5, h: 0.75, fontSize: 11, color: C.text2, valign: 'middle', margin: 0, isTextBox: true, objectName: 'phone-yield',
  });
  await badge(s, fa.FaHandPointer, 6.35, 4.15, 0.5, 'phone-hail');
  s.addText('在小地图上点目的地即可叫车，订单与普通订单一样被派出', {
    x: 7.0, y: 4.1, w: 2.5, h: 0.6, fontSize: 11, color: C.text2, valign: 'middle', margin: 0, isTextBox: true, objectName: 'phone-hail-text',
  });
  s.addNotes('手机是数字世界里的行人。App 用加速度计步、Weinberg 模型估步长、陀螺仪积分航向，再按比例尺换算成地图坐标，每秒上报 10 次——'
    + '教室里收不到卫星，走 1 米可以当地图上 5 米。行人走进某辆车的授权格，车会立刻停下。'
    + '端云互动：手机只负责拍照上传，服务端做颜色分割、连通域和针孔模型测距，把路障写进全局地图，附近的车随即绕行。'
    + '颜色分割和开运算按行并行，大图在两核机器上加速约 1.4 倍。');

  // ---------------------------------------------------------------- 5 挑战亮点与评估
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: '正文' });
  s.addText('挑战亮点与评估：高负载运行 6 小时零碰撞', { placeholder: 'title' });
  s.addImage({ path: img('docs/images/gazebo_pair.jpg'), x: 0.5, y: 1.15, w: 4.4, h: 1.364, objectName: 'gazebo-shot', altText: 'Gazebo 截图：俯视与跟车视角' });
  s.addText('Gazebo Harmonic 中的 5 辆差速小车（ROS 2 桥接）', {
    x: 0.5, y: 2.58, w: 4.4, h: 0.3, fontSize: 10.5, color: C.text2, margin: 0, isTextBox: true, objectName: 'gazebo-caption',
  });
  const challenges = [[fa.FaCube, '3D 可视化'], [fa.FaSatelliteDish, '感知建图'], [fa.FaCloudUploadAlt, '端云互动'],
    [fa.FaTachometerAlt, '图像加速'], [fa.FaRobot, 'Gazebo'], [fa.FaMapMarkedAlt, '空车调度']];
  for (let i = 0; i < challenges.length; i++) {
    const x = 0.5 + (i % 3) * 1.5;
    const y = 3.1 + Math.floor(i / 3) * 0.95;
    card(s, x, y, 1.4, 0.8, `challenge-${i}`);
    await badge(s, challenges[i][0], x + 0.1, y + 0.2, 0.4, `challenge-${i}`);
    s.addText(challenges[i][1], { x: x + 0.58, y, w: 0.8, h: 0.8, fontSize: 11, bold: true, color: C.text1, valign: 'middle', margin: 0, isTextBox: true, objectName: `challenge-${i}-text` });
  }
  const stats5 = [
    ['0', '车辆碰撞：16 车高负载\n运行 6 × 60 分钟'],
    ['103', '项单元测试全部通过'],
    ['0.2 s', '断开车队后全部\n由数字世界接管'],
    ['0 次', '考核中的人工操作\n（场景脚本自动触发）'],
  ];
  for (let i = 0; i < stats5.length; i++) {
    const x = 5.15 + (i % 2) * 2.2;
    const y = 1.15 + Math.floor(i / 2) * 1.95;
    card(s, x, y, 2.05, 1.8, `stat5-${i}`);
    s.addText(stats5[i][0], { x: x + 0.15, y: y + 0.18, w: 1.75, h: 0.75, fontSize: 32, bold: true, color: C.accent2, margin: 0, isTextBox: true, objectName: `stat5-${i}-num` });
    s.addText(stats5[i][1], { x: x + 0.15, y: y + 0.98, w: 1.8, h: 0.7, fontSize: 11, color: C.text2, valign: 'top', margin: 0, isTextBox: true, objectName: `stat5-${i}-label` });
  }
  s.addNotes('挑战项我们做了六个：自写 WebGL 的 3D 视图、感知与辅助建图、端云互动、图像处理加速、Gazebo 物理仿真小车、需求预测空车调度。'
    + '评估方面：16 辆车、每分钟 16 单的高负载，运行带路障和未知障碍的演示场景 6 次、每次 60 分钟，碰撞全部为零；'
    + '103 项单元测试全部通过；系统集成测试中断开整个车队，0.2 秒内全部由数字世界接管。'
    + '最后，考核过程中不需要任何人工操作，所有情节都由场景脚本按时间自动触发。');

  // ---------------------------------------------------------------- 结尾
  pres.addSection({ title: '结尾' });
  s = pres.addSlide({ masterName: 'TITLE_DARK', sectionTitle: '结尾' });
  s.addText('谢谢！请老师指正', { placeholder: 'title' });
  s.addText('现场演示：一键启动，全程无人操作', { placeholder: 'body' });
  s.addText('源码、技术报告、测试与评估数据：\ngithub.com/fuzeliang1/ZongC', { placeholder: 'info' });
  s.addImage({ path: img('docs/images/gazebo_chase.jpg'), x: 5.75, y: 1.15, w: 3.85, h: 2.41, objectName: 'closing-gazebo', altText: 'Gazebo 跟车视角' });
  s.addNotes('我们的介绍就到这里，接下来是现场演示：整个系统一键启动，演示过程中我们不碰电脑。谢谢各位老师。');

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  await pres.writeFile({ fileName: OUT });
  await writeThemeColors(OUT);
  console.log('已生成', path.relative(ROOT, OUT));
}

main().catch((e) => { console.error(e); process.exit(1); });
