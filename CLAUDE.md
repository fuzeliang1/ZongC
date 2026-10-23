# CLAUDE.md — 项目记忆（MiniDiDi / 综设 C）

> 给接手这个仓库的人和 AI 助手看的"交接本"。新会话请先读完本文件，再读 `docs/plan.md`。
> 每完成一个功能就更新第 6 节"进度"，并随代码一起提交。

## 1. 项目是什么

- 同济大学"综合设计与实践 C"（综设 C）小组课程项目，题目"缩微滴滴"（RoboTax / MiniDiDi）：
  城市里有人叫车，"系统超脑"（数字世界服务端）派车、规划路径、管控交通；车辆可由服务端内部仿真，
  也可由 Matlab 模拟车、手机、Gazebo 驱动（数字孪生）。
- 课程要求（来自老师的 PPT，原文要点）：
  - 必做 → "良"：数字世界 DW（≥5 辆数字车、随机运输任务、基于最优化的路径规划、人机交互与动态显示）；
    Matlab 模拟车（≥5 辆，与数字车同步，不与静态 / 动态障碍碰撞）；手机 App（读 GPS/北斗、加速度、陀螺、
    地磁，无线发给服务端并与某智能体同步，基于惯性元件估计位姿，精度不作要求）。
  - 挑战 → "优"：3D 渲染可视化；Gazebo 中 ≥5 辆车（进阶）；第 2 页的"智能"：环境感知（知道周边障碍物
    和目标物）与辅助建图；服务端可选"图像处理加速模块"。
  - 考核：启动后即自主运行，**考核过程中不可人工切换模式、设置或遥控**；必须整体可集成运行
    （孤立模块拿不到良）；文档要覆盖立项→需求→设计→测试→部署→效果评估全流程，图符要专业。
  - 组织：第 1–16 周，第 8 周中期检查，第 16 周答辩；4–5 人一组；**要求用 gitee.com 私有库**
    （目前代码在 GitHub `fuzeliang1/ZongC`，需要导入到 gitee）。
  - 提交物：技术报告（docx/pdf，格式参考综设 A）、介绍 PPT（正文 ≤5 页）、源码附件、图片和短视频（≤5 分钟）。
  - 技术路线：选了 PPT 中的"路线 1"（HTML + JS + CSS 前端，Java 服务端 + REST）。

## 2. 仓库结构

```
server/   数字世界服务端：Java 17+，只依赖 Gson（server/lib 里自带 jar），JDK 自带 HttpServer
web/      网页前端：原生 HTML/CSS/JS（ES 模块），Canvas 2D；index.html 为 2D 主界面
matlab/   Matlab 模拟车（DwClient / SimCar / FleetView / run_fleet）——尚未在 MATLAB 中实测
phone/    安卓 App（Android Studio 工程，Java，无第三方库，minSdk 26，compile/target 35，AGP 8.7.3，Gradle 8.9）
tools/    mock_devices.py（协议参考实现：Python 模拟车 / 模拟手机）、gen_map.py（地图生成）
scripts/  build / start_server / start_demo / experiment / phone_sim（.sh 与 .bat 成对）
docs/     plan.md（计划与进度）、architecture.md（架构）、protocol.md（接口协议 v1）、dev-guide.md
```

## 3. 常用命令

```bash
scripts/build.sh                      # 编译服务端 → server/build/classes
scripts/start_server.sh               # 启动服务端：浏览器 http://localhost:8080，设备 TCP 9000 / UDP 9001
scripts/start_demo.sh                 # 服务端 + 5 辆 Python 模拟车 + 1 部模拟手机 + 打开浏览器
scripts/experiment.sh --minutes 30 --seeds 1,2,3 --taxis 16 --rate 16   # 离线派单对比实验
scripts/phone_sim.sh                  # 用手机 App 的核心代码模拟"拿着手机走路"，接入运行中的服务端做端到端检查
cd server && mvn test                 # 服务端单元测试（有网络时）
cd phone && ./gradlew test            # 手机核心算法单元测试（需要 Android SDK）
```

没有 Maven / Android SDK 时（例如在受限的云沙箱里），可以直接用 javac + JUnit 4（junit-4.13.2.jar、
hamcrest-core-1.3.jar）编译运行测试：服务端测试必须在 `server/` 目录下运行（会读 `config/default.json`）；
手机 `core` 包是纯 Java，用 `javac --release 8` 编译即可在电脑上测试（`--release 8` 顺便保证没有用到
安卓 8.0 不支持的 Java 9+ API）。

## 4. 架构要点与不变量（改代码前必读）

- **线程模型**：`World` 只在仿真线程里访问；HTTP、TCP 线程一律通过 `SimulationEngine.submit/call` 排队。
  World 内部不加锁，不要从别的线程直接调用 World 的方法。
- **坐标**：米；地图格 (x, y) 覆盖 [x, x+1]×[y, y+1]（分辨率 1 m），(0,0) 在西南角，y 向北；航向 θ 从 +x
  逆时针。`/api/map` 的 rows 第一行是最北边一行。车道 `> < ^ v` 单向、右侧通行，`+` 路口，`#` 建筑。
- **交通管控 = 格子预约的"移动授权"**：一格一车；车只能驶入已授权格，在授权终点前 bodyRadius+stopMargin
  处停车；路口整段 + 出口格一次性申请（不堵路口）；受阻 3 s 优先、12 s 绕行。
  授权通常只向前延伸，**例外**：行人（手机等自由智能体）或新出现的障碍 / 路障落在已授权、车还没开到的格子里时，
  服务端立即收回授权（`TrafficController.revokeBefore`，停在路口里时退到路口前）。设备端必须直接采用最新
  permit 的 `stopS`（不要取历史最大值）。
- **规划**：A*，状态 = (格, 驶入方向)，禁止原地掉头；前方无路（单行道被堵死）时 `planUTurn` 借对向车道掉头。
- **派单**：GREEDY / HUNGARIAN（匈牙利算法，代价 = 路网距离 − 等待时间权重）；可选空车调度（`Rebalancer`）。
- **数字孪生**：设备注册 `kind = matlab/gazebo/generic` 绑定出租车，`phone` 新建自由智能体（行人）；
  3 s 无消息判离线，出租车由内部仿真接管。协议细节以 `docs/protocol.md` 为准，改协议必须同步改文档、
  `tools/mock_devices.py`、`matlab/`、`phone/` 四处。
- **感知与建图**（`world/Perception.java`）：真实世界（建筑 + 未知障碍 `hidden` + 人工路障）与系统认知
  （地图 + 由感知加入的障碍 `discovered`）分开；每辆车 10 Hz 模拟 48 束、3 m 激光雷达；log-odds 占据栅格，
  概率 > 0.8 加入全局地图并收回授权、重规划，< 0.2 自动撤掉。
- **场景脚本**（`world/Scenario*.java`，`server/scenarios/*.json`）：按仿真时间自动触发事件，满足
  "考核中不可人工操作"。
- **安全统计**：`CollisionMonitor` 独立计数碰撞 / 撞墙 / 行人接触，长时间测试必须为 0。

## 5. 约定

- 文档、注释、界面、日志都用中文；面向初学者，写清"为什么"。
- 服务端 Java 17；手机 App 只用 Java 8 语法 + 安卓自带 API，零第三方依赖（`core` 包不得引用 `android.*`）。
- 测试优先：改服务端逻辑要补 `server/src/test`；改手机算法要补 `phone/app/src/test`。
  长时间稠密交通实验（`scripts/experiment.sh`）必须零碰撞。
- 提交信息用中文概括做了什么、为什么；脚本 `.sh` 用 LF，`.bat` 用 CRLF（见 `.gitattributes`）。
- 不要在演示 / 考核流程里依赖人工点击：新的演示动作写进场景脚本。

## 6. 进度（每完成一项就更新）

### 已完成（2026-10-23 之前）
- 服务端 v1：地图、A*、移动授权交通管控、贪心 / 匈牙利派单、订单流程、碰撞监测、设备接入（TCP/UDP）、
  REST + SSE、离线实验工具；服务端单元测试 11 项；30 分钟稠密实验零碰撞。
- 2D 网页界面；Python 模拟设备；一键演示脚本。
- Matlab 模拟车代码（未在 MATLAB 实测）。
- 安卓 App（`phone/`）：PDR（计步 + Weinberg 步长 + 陀螺航向 + 零偏估计）、室外磁力计 PI 融合 + 卫星卡尔曼融合、
  标定、小地图、叫车、模拟行走、CSV 记录 + MATLAB 分析脚本；核心 64 项单元测试 + 端到端仿真通过；
  **未在真机运行过**（安卓层只对照 API 桩做过编译检查）。
- 服务端修复：行人走进已授权格子时紧急收回授权。

### 进行中（2026-10-23，目标："把能做的都做到较好的程度"）
1. 场景脚本引擎（按时间自动触发：公告、到达率、策略、路障、未知障碍、订单、订单潮、需求热点、空车调度）
2. 感知与建图挑战（模拟激光雷达、未知障碍、占据栅格、自动更新全局地图、设备 scan 消息、掉头脱困）
3. 图像处理加速模块（服务端颜色分割识别路障）+ 手机拍照上报
4. 派单优化：需求热点 + 空车调度 + 对比实验
5. Three.js 3D 可视化
6. 全自主演示：一键启动（含 Matlab）、App 自动连接
7. Gazebo 接入脚手架（无法在当前环境测试）
8. 全流程文档（立项、需求、测试、部署、效果评估、周会纪要模板）与 UML 图
9. 技术报告初稿、5 页 PPT、演示录屏

### 需要人来做（AI 在云端做不了）
- 在 MATLAB 里跑 `matlab/test_simcar_offline.m` 和 `run_fleet`（5 辆车），修复报错。
- 用 Android Studio 编译 `phone/`、装到真机实测，做 `phone/README.md` 第 4 节的 10 m 正方形实验。
- 在 gitee 建私有库并从 GitHub 导入；向老师确认 `docs/plan.md` 第 8 节的问题。
- 拍摄真机 / 现场演示视频；准备答辩。

## 7. 已知坑

- 在云沙箱里：Maven Central、Google Maven、services.gradle.org 无法访问，所以服务端不用 Spring Boot、
  Gson jar 随仓库提供；Android APK 无法在沙箱里构建。
- `pkill -f DwServer` 会把执行它的 shell 也杀掉；启动 / 停止服务端请记录 PID。
- 服务端测试依赖工作目录（`server/`）。
- `AndroidManifest` 里 `LocationListener` 的三个旧回调必须保留实现，否则安卓 10 及以下机型会崩溃。
- 安卓本地单元测试编译时用的是 android.jar，测试代码不能用 `com.sun.*`、`javax.imageio` 等 JDK 专有 API。
