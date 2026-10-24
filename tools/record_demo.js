// 录制演示视频（由 tools/record_demo.py 调用，也可以单独运行；服务端要先在 8080 端口运行）：
//   node tools/record_demo.js <输出目录> <2D 秒数> <3D 巡游秒数> <3D 跟车秒数> [跟车的车号]
// 依次录 2D 主界面、3D 自动巡游、3D 跟随某辆车，存成一个 .webm，最后打印文件路径。
// 全程不点任何按钮：画面里发生的一切都由场景脚本自动触发（与考核时一样）。
// 无头 Chromium 渲染 WebGL 需要 SwiftShader 软件渲染参数。
const { chromium } = require('playwright');

(async () => {
  const [outDir, s2d, sTour, sFollow, car] = process.argv.slice(2);
  if (!outDir) {
    console.error('用法：node tools/record_demo.js <输出目录> <2D 秒数> <3D 巡游秒数> <3D 跟车秒数> [车号]');
    process.exit(2);
  }
  const base = process.env.DW_URL || 'http://localhost:8080';
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: outDir, size: { width: 1280, height: 720 } },
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  const wait = (s) => page.waitForTimeout(Number(s) * 1000);

  await page.goto(base + '/');
  await wait(s2d || 150);
  await page.goto(base + '/3d.html?mode=tour');
  await wait(sTour || 60);
  await page.goto(`${base}/3d.html?car=${car || 'car-3'}&mode=follow`);
  await wait(sFollow || 45);

  const video = page.video();
  await context.close();
  await browser.close();
  if (errors.length) console.error('页面错误：\n' + errors.join('\n'));
  console.log(await video.path());
})();
