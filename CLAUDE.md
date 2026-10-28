# CLAUDE.md — 项目记忆（MiniDiDi / 综设 C）

> 给接手这个仓库的人和 AI 助手看的"交接本"。新会话请先读完本文件，再看 `docs/plan.md` 和 `README.md`。
> 面向组员的入门说明（完成情况、上手步骤、分工与任务）在 `docs/onboarding.md`。
> 每完成一个功能就更新第 6 节"进度"，并随代码一起提交推送（云端环境可能被重置，没推送的代码会丢）。

## 0. 接手第一步

1. 读本文件第 6 节：已完成什么、还剩什么（按优先级排好了，每项写了做法）。
2. 跑一遍测试确认环境正常（第 3 节）：服务端 30 项、手机核心 66 项，应全部通过。
3. 起服务端看看效果：`scripts/start_demo.sh --no-browser`，浏览器打开 http://localhost:8080 和 /3d.html。
4. 用户（项目组成员）用中文交流；文档、注释、界面、提交信息也都用中文。

## 1. 项目是什么

- 同济大学"综合设计与实践 C"（综设 C）小组课程项目，题目"缩微滴滴"（RoboTax / MiniDiDi）：
  城市里有人叫车，"系统超脑"（数字世界服务端）派车、规划路径、管控交通；车辆可由服务端内部仿真，
  也可由 Matlab 模拟车、手机、Gazebo 驱动（数字孪生）。
- 课程要求（来自老师的 PPT，原文要点）：
  - 必做 → "良"：数字世界 DW（≥5 辆数字车、随机运输任务、基于最优化的路径规划、人机交互与动态显示）；
    Matlab 模拟车（≥5 辆，与数字车同步，不与静态 / 动态障碍碰撞）；手机 App（读 GPS/北斗、加速度、陀螺、
    地磁，无线发给服务端并与某智能体同步，基于惯性元件估计位姿，精度不作要求）。
  - 挑战 → "优"：3D 渲染可视化；Gazebo 中 ≥5 辆车（进阶）；第 2 页的"智能"：环境感知（知道周边障碍物
    和目标物）与辅助建图、端云互动（服务端做重型图像处理）；服务端"图像处理加速模块"。
  - 考核：启动后即自主运行，**考核过程中不可人工切换模式、设置或遥控**；必须整体可集成运行
    （孤立模块拿不到良）；文档要覆盖立项→需求→设计→测试→部署→效果评估全流程，图符要专业。
  - 组织：第 1–16 周，第 8 周中期检查，第 16 周答辩；4–5 人一组。课程 PPT 写的是用 gitee 私有库，
    **小组决定只用 GitHub 仓库 `fuzeliang1/ZongC`**，不建 gitee，也不写周会纪要（模板已删除）。
  - 提交物：技术报告（docx/pdf，格式参考综设 A）、介绍 PPT（正文 ≤5 页）、源码附件、图片和短视频（≤5 分钟）。
  - 技术路线：选了 PPT 中的"路线 1"（HTML + JS + CSS 前端，Java 服务端 + REST）。
- 小组 4 人（分工详见 `docs/plan.md` 第 3 节，已写进立项申请书、技术报告、PPT）：
  - 阮飞翔（2252753，组长）：架构与调度——架构、协议、派单与空车调度、联调；立项 / 需求文档、报告统稿、答辩主讲
  - 樊宇泽（2351586）：服务端——数字世界、交通管控、设备接入、感知建图、图像处理；设计文档、测试报告
  - 李昊博（2352853）：控制与可视化——Matlab 模拟车、2D / 3D 网页；部署手册、PPT、视频
  - 付泽亮（2353413）：移动端与进阶——安卓 App、Gazebo；评估报告、GitHub 仓库管理
  - 第 1 组，指导教师许春权；报告封面不写完成日期。报告 8.3 节个人总结已按分工写好（改了相关内容要同步）。

## 2. 仓库结构

```
server/            数字世界服务端：Java 17+，只依赖 Gson（server/lib 自带 jar），JDK 自带 HttpServer
  src/main/java/com/zongc/dw/
    map/ plan/ traffic/ dispatch/   栅格地图、A*（含掉头）、移动授权交通管控、派单
    world/          World（仿真步）、Perception（感知建图）、Scenario*（场景脚本）、Demand/Rebalancer（空车调度）、
                    DeviceBridge（设备协议）、CollisionMonitor、Metrics、Snapshot
    vision/         图像识别：ColorBlobDetector、VisionService、SyntheticImages
    sim/ api/ device/   仿真线程、HTTP（REST + SSE）、TCP/UDP 设备接入
    tools/          Experiment（离线对比实验）、VisionBench（图像处理测速）
  scenarios/       场景脚本 demo / perception / rush-hour（JSON）
  config/ maps/    配置、地图
web/               网页：index.html（2D，Canvas）、3d.html（3D，自写 WebGL 渲染器 js/gl/）
matlab/            Matlab 模拟车（DwClient / SimCar / FleetView / run_fleet）——CI 中用 MATLAB R2024b 实测通过（无图窗）
phone/             安卓 App（Android Studio 工程，Java，零第三方库，minSdk 26，compile/target 35，AGP 8.7.3，Gradle 8.9）
gazebo/            gen_world.py（地图 → Gazebo 世界）、minididi_bridge/（ROS 2 桥接包，含纯 Python 运动学后端与测试）
tools/             mock_devices.py（协议参考实现）、gen_map.py、run_evaluation.py（一键跑全部评估实验）、system_test.py、
                   plot_eval.py、render_uml.py、build_report.py、build_slides.js、record_demo.py/.js
scripts/           build / start_server / start_demo / experiment / phone_sim（.sh 与 .bat 成对）
docs/              全流程文档（索引 docs/README.md，组员入门 docs/onboarding.md）、uml/（PlantUML 源）、report/report.md（技术报告源）、eval/、images/
deliverables/      提交物：技术报告 docx/pdf、答辩 PPT、演示视频（都由 tools/ 下的脚本生成）
.github/workflows/ci.yml   持续集成：Linux / Windows（.bat）/ Android（编译 APK）/ MATLAB（5 车接入）四个任务
```

## 3. 常用命令

```bash
scripts/build.sh                      # 编译服务端 → server/build/classes
scripts/start_server.sh               # 启动服务端：2D http://localhost:8080，3D /3d.html，设备 TCP 9000 / UDP 9001
scripts/start_server.sh --scenario perception   # 运行指定场景脚本（server/scenarios/ 下的文件名），none = 不运行
scripts/start_demo.sh                 # 一键全自主演示：服务端 + demo 场景 + Matlab 车队（没装 MATLAB 时用 Python 模拟车）
                                      #   + 模拟手机车 + 浏览器；参数 --cars auto|matlab|python|none --phones N --scenario 名 --no-browser
scripts/experiment.sh --minutes 30 --seeds 1,2,3 --taxis 16 --rate 16     # 派单策略对比（仓库根目录运行）
scripts/experiment.sh --minutes 30 --seeds 1,2,3,4,5,6,7,8 --strategies HUNGARIAN \
    --rebalance off,on --rate 5 --scenario rush-hour                     # 空车调度对比
python3 tools/run_evaluation.py       # 全部效果评估实验（约 5 分钟）→ docs/eval/summary.md + CSV
python3 tools/plot_eval.py            # 评估 CSV → docs/images/eval/*.png（需要 matplotlib）
python3 tools/system_test.py          # 系统集成测试（约 4 分钟）：服务端 + 5 车 + 1 辆手机车、断线接管、图像识别，断言零碰撞
python3 tools/render_uml.py           # docs/uml/*.puml → docs/images/uml/*.png（PlantUML，自动下载；有 Graphviz 更好）
python3 tools/build_report.py --pdf   # 技术报告 docs/report/report.md → deliverables/*.docx / *.pdf（pandoc + LibreOffice）
python3 gazebo/gen_world.py           # 地图 → Gazebo 世界 + ros_gz_bridge 配置；桥接包测试：cd gazebo/minididi_bridge && python3 -m unittest discover -s test
scripts/phone_sim.sh                  # 用手机 App 的核心代码模拟"拿着手机走路驾驶 car-6"，接入运行中的服务端
cd server && mvn test                 # 服务端单元测试 30 项（需要能访问 Maven Central）
cd phone && ./gradlew test            # 手机核心算法单元测试 66 项（需要 Android SDK）
python3 tools/system_test.py --fleet external --bind-timeout 300   # 不起模拟车，等外部车队（如 MATLAB run_fleet）接入
```

**持续集成**：推送后 GitHub Actions 自动运行 `.github/workflows/ci.yml`（只改 `*.md`、`docs/`、`deliverables/` 不触发；同一分支新推送会取消正在跑的）。
沙箱里没有 gh，但公开仓库可以直接 `curl -s https://api.github.com/repos/fuzeliang1/ZongC/actions/runs?per_page=3` 查状态，
日志用 GitHub MCP 的 `get_job_logs`。MATLAB 任务依赖仓库公开（私有库要配 `MLM_LICENSE_TOKEN`）；Android 任务产出的 APK 在运行页面的 Artifacts 里。

没有 Maven / Android SDK 时（例如受限的云沙箱）直接用 javac + JUnit 4（junit-4.13.2.jar、hamcrest-core-1.3.jar，
沙箱里在 `/opt/gradle-*/lib/`）：

```bash
J=/opt/gradle-8.14.3/lib/junit-4.13.2.jar:/opt/gradle-8.14.3/lib/hamcrest-core-1.3.jar; O=/tmp/t
# 服务端（必须在 server/ 下运行，测试会读 config/default.json）
cd server && mkdir -p $O/m $O/t && javac -nowarn -encoding UTF-8 --release 17 -cp lib/gson-2.13.1.jar -d $O/m $(find src/main/java -name '*.java') \
 && javac -nowarn -encoding UTF-8 --release 17 -cp $O/m:lib/gson-2.13.1.jar:$J -d $O/t $(find src/test/java -name '*.java') \
 && java -cp $O/m:$O/t:lib/gson-2.13.1.jar:$J org.junit.runner.JUnitCore $(cd src/test/java && find . -name '*Test.java' | sed 's|^\./||;s|\.java$||;s|/|.|g')
# 手机核心：javac --release 8 编译 phone/app/src/main/java/com/zongc/phone/core 与 src/test/java，
# 复制 src/test/resources，再用 JUnitCore 跑（--release 8 顺便保证没用安卓 8.0 不支持的 Java 9+ API）
```

## 4. 架构要点与不变量（改代码前必读）

- **线程模型**：`World` 只在仿真线程里访问；HTTP、TCP 线程一律通过 `SimulationEngine.submit/call` 排队。
  World 内部不加锁。图像识别在 HTTP 线程里算完，再用 `engine.call` 把结果交给仿真线程。
- **坐标**：米；地图格 (x, y) 覆盖 [x, x+1]×[y, y+1]（分辨率 1 m），(0,0) 在西南角，y 向北；航向 θ 从 +x
  逆时针。`/api/map` 的 rows 第一行是最北边一行。车道 `> < ^ v` 单向、右侧通行，`+` 路口，`#` 建筑。
  3D 视图：地图 (x, y) → GL (x, 高度, −y)。
- **交通管控 = 格子预约的"移动授权"**：一格一车；车只能驶入已授权格，在授权终点前 bodyRadius+stopMargin
  处停车；路口整段 + 出口格一次性申请（不堵路口）；受阻 3 s 优先、12 s 绕行。授权通常只向前延伸，
  **例外**：已授权的格子变得不可通行（路障 / 感知发现的障碍）时立即收回（`TrafficController.revokeBefore`，
  停在路口里时退到路口前）。设备端必须直接采用最新 permit 的 `stopS`（不要取历史最大值）。
  外部设备有延迟：收回后 `REVOKE_GRACE_SEC`（1 s）内、在收回前已授权范围内多走进一格记为 `revokeOverruns`，不算 `authorityViolations`。
- **规划**：A*，状态 = (格, 驶入方向)，禁止原地掉头；前方无路时 `AStarPlanner.planUTurn` 借对向车道掉头，
  车已停在格内时回到格中心原地转向；上下车点被障碍切断（任何车都到不了）时就近改点（`World.planToNearby`、
  派单时 `dispatch()` 里改上车点）。
- **派单**：GREEDY / HUNGARIAN（代价 = 路网距离 − 等待时间权重）；可选空车调度 `Rebalancer`（9×9 格分区、
  τ = 180 s 衰减的需求计数、匈牙利算法调空车）。订单生成用**独立随机数序列**（只由种子决定），对比实验是配对的。
- **数字孪生**：设备注册（`kind = matlab/gazebo/phone/generic`）后一律绑定一辆出租车，世界里没有行人；
  3 s 无消息判离线，出租车由内部仿真接管。协议以 `docs/protocol.md` 为准，改协议必须同步改文档、
  `tools/mock_devices.py`、`matlab/`、`phone/` 四处（新增的下行消息旧设备会忽略）。
- **感知与建图**（`world/Perception.java`）：真实世界（建筑 + 未知障碍 `hidden` + 人工路障）与系统认知
  （地图 + 感知加入的障碍 `discovered`）分开；每辆车 10 Hz 模拟 48 束、3 m 激光雷达，命中格直接取光线投射撞上的
  那一格（不要用"命中点往前一点"反查，会在建筑转角处误检）；log-odds 限制在 [−2, 4]，命中 +0.85、穿过 −0.4，
  ≥1.4 写入地图（收回授权、重规划），≤−1.4 自动撤掉。碰撞监测按真实世界统计 `barrierHits`，必须为 0。
- **图像识别**（`vision/`）：照片缩到 640 宽 → HSV 颜色分割（红橙 = 路障，黄 = 警示牌）→ 开运算 → 连通域
  （合并被白色反光条切开的锥桶）→ 针孔模型测方位和距离 → 落到地图格子。路障作为观测并入占据栅格（一次即确认），
  同时登记到数字孪生"真实世界" 120 s（`World.VISION_TTL_SEC`，再拍到续期），过期后由感知撤掉。
- **场景脚本**（`world/Scenario*.java`，格式见 `docs/protocol.md` 第 6 节）：按仿真时间自动触发事件；循环播放时
  新一轮开始会撤掉上一轮的路障和障碍并清掉感知结论；未知障碍 2.5 m 内有车时推迟出现。
- **安全统计**：`CollisionMonitor` 独立计数碰撞 / 撞墙 / 撞障碍；所有测试和实验必须为 0。
- **手机 = 模拟车**（课程 PPT 第 12 页"以人携带手机运动来模拟车辆的运动"）：App 以 `kind = phone` 注册、默认请求
  `car-6`（Matlab 车队占 car-1~5）。路线由服务端规划，快慢由人决定：`core/RouteFollower` 以"走路速度 × 驾驶比例
  （driveScale）"为期望车速沿 path 折线推进弧长 s，受 vMax / vTurn / 刹车曲线 / 加减速度限制，始终 s ≤ stopS，
  上报 s 处的位姿。PDR 只在手机坐标系 P 里估计位姿（显示、记录），不再有地图坐标换算。`tools/mock_devices.py`
  的 `PhoneCar` 是同一套逻辑的 Python 版，改一处要同步另一处。

## 5. 约定

- 文档、注释、界面、日志都用中文；面向初学者，写清"为什么"。
- 服务端 Java 17；手机 App 只用 Java 8 语法 + 安卓自带 API，零第三方依赖（`core` 包不得引用 `android.*`）；
  网页零第三方依赖（ES 模块，3D 用自写 WebGL）。
- 测试优先：改服务端逻辑要补 `server/src/test`；改手机算法要补 `phone/app/src/test`；
  长时间稠密交通实验必须零碰撞（含 barrierHits）。改了影响结果的逻辑后重跑 `tools/run_evaluation.py`。
- 提交信息用中文概括做了什么、为什么；`.sh` 用 LF，`.bat` 用 CRLF 且只写 ASCII 注释（见 `.gitattributes`）。
- 不要在演示 / 考核流程里依赖人工点击：新的演示动作写进场景脚本。

## 6. 进度

### 已完成（全部已推送到 GitHub main）

| 提交 | 内容 |
|---|---|
| e13aa62 / bd89519 | 服务端 v1（地图、A*、移动授权、贪心 / 匈牙利派单、订单、碰撞监测、设备接入、REST + SSE、离线实验）；2D 网页；Python 模拟设备；Matlab 模拟车代码；一键脚本；文档 |
| b6a2b82 | 行人走进已授权格子时紧急收回授权 |
| db5f17d | 安卓 App：PDR（计步 + Weinberg 步长 + 陀螺航向 + 零偏）、室外磁力计 PI 融合 + 卫星卡尔曼融合、标定、小地图、叫车、模拟行走、CSV 记录 + MATLAB 分析脚本 |
| c2645e6 | 场景脚本、感知与建图（模拟激光雷达、占据栅格、掉头、改点、barrierHits）、需求热点与空车调度 |
| 5961945 | 网页显示场景 / 感知 / 需求；`tools/run_evaluation.py` 与 `docs/eval/`；协议、架构文档 |
| 1a70ac4 | 图像处理加速模块（服务端识别 + 网页面板 + 手机"拍照"按钮）、VisionBench |
| a8b4047 | 3D 视图（自写 WebGL，四种视角） |
| c6450d4 | 全自主一键演示（含 Matlab 车队自动启动、无 MATLAB 时回退）；手机 App 打开即自动连接 |
| 03b5da1 / 738d994 | 全流程文档（立项、需求、设计、测试、部署、评估、CHANGELOG）+ PlantUML 图 18 张 + 评估图表；`tools/system_test.py` 系统集成测试 |
| f5b8976 | Gazebo 接入（世界生成器 + ROS 2 桥接包，无界面模式实测 5 车零碰撞）；技术报告 docx/pdf |
| 3d39eb2 / 198794f | 答辩 PPT；演示录屏工具与视频 |
| 198794f 之后 | 组员上手指南 `docs/onboarding.md`；填写成员与分工；改为只用 GitHub、删除周会纪要模板；封面与个人总结 |
| 669688c / a6bcf21 | GitHub Actions 持续集成（Linux / Windows / Android / MATLAB），第一次运行全部通过；`run_fleet` 的 duration 参数、`system_test.py --fleet external`；修复 `start_demo.bat` 的 timeout 问题 |
| 87589f7 | 文档同步 CI 结果：测试报告 4.6 节、上手指南 6.6 节、README 徽章；"Matlab 没跑过 / 只做过编译检查"的说法按实测改掉 |
| cb2a700 | CI 在 main 上发现的缺陷 D12：外部设备来不及响应授权收回被误记为越权 → `REVOKE_GRACE_SEC` + `revokeOverruns`，新增回归测试（服务端 30 项） |
| 8adfba6 | 交接本与上手指南：记录 CI 完成后的最终状态 |
| 2038ba8 | 手机改为"模拟车"（走路即油门），删除行人与叫车；重新生成报告、PPT 和演示视频（详见下一小节） |
| （本次） | 安卓真机实测（vivo S20，安卓 15）：结果和截图写进评估 / 测试 / 技术报告、phone/README、部署手册，重新生成报告 docx/pdf 和 PPT（详见"需要人来做"） |

以上提交已全部推送到 main（当前 `2038ba8`）。`2038ba8` 在 main 上的 CI 四个任务全部通过（已写进测试报告 4.6 节）。
真机实测和中文 Windows 一键演示的记录在分支 `phone-real-test` 上（2026-10-28），CI 通过后合并到 main。
以后的开发在新分支上进行，完成后再合并到 main；合并前分支上的 CI 要先变绿，合并后再看一次 main 上的 CI。

### 手机改为"模拟车"（提交 `2038ba8`，2026-10-26）

用户按课程 PPT 第 12 页的要求决定：**只保留车**（删除行人、叫车、车给行人让路，以及文档里的相关内容），
手机驾驶方式为**走路 = 油门**（车沿服务端路线自动行驶，人走多远车开多远，不需要转身选路）。

- 完成并验证的内容：服务端（删除 FREE / taxi 字段 / 行人表 / freeContacts / phoneRadius，新增
  `WorldTest.arcLengthDeviceCompletesTrips`，30 项通过）；网页；手机 App（新增 `RouteFollower` + 5 项测试，删除
  `MapFrame`、设位置 / 对方向 / 比例尺 / 叫车，设置里加"车号""驾驶比例"，CSV 加车的列，核心 66 项通过，安卓层对照
  android.jar 编译通过）；`PhoneSim` 端到端通过（走 23.0 m、车开 21.6 m、位置差平均 0.10 m）；`mock_devices.py`
  的 `PhoneCar`；`system_test.py` 全部通过（手机车全程接入、累计 ≥161 m、零碰撞）；CI、start_demo 脚本；
  文档：protocol、phone/README（小地图图片用 App 的绘图代码重新渲染）、architecture、requirements、design、
  test-report（2026-10-26 重测数据；D01 / D12 保留并加说明）、report、onboarding、deployment、plan、evaluation、
  proposal、dev-guide、CHANGELOG、README、docs/README；15 张 UML 改完重新渲染（Graphviz 排版）；2D / 3D 截图重拍；网页冒烟测试
  （Edge 无头，2D / 3D 控制台零报错）；`build_slides.js` 第 4 页改为"拿着手机走路就能开车"；报告 docx / pdf 和 PPT 已重新生成（报告里的 UML 图按用户要求改成表格和文字，目录写进 docx；付泽亮在 WPS 里删掉了第 7 章"部署与使用"和附录 B、改短 1.2 节首句，`deliverables/` 里的报告就是他改的那份，按他的要求在他那份上把原第 8、9 章顺延为第 7、8 章（节号、图表号、引用同步），用 WPS 更新目录并导出 PDF（40 页），`report.md` 已同步；PPT 按用户要求重新设计：宽屏、大字号、原生图示，正文 5 页 + 附录 4 页）；演示视频重录（4 分 41 秒，`record_demo.py --car car-6` 跟拍手机车）。
- 已于 2026-10-26 提交推送到 main（`2038ba8`）。
- `2038ba8` 的 CI 四个任务全部通过（2026-10-28 查看，已补进测试报告 4.6 节和第 7 节）。

**2026-10-24 的决定**：调研同类项目后提出过几项改进（录制回放 + 指标曲线、报告补"相关工作"一节、动态改派、拼车、时空预约 / 路口细化、拥堵感知规划、感知加噪声等），用户只要求做 CI，**其他都不做**。除非用户再提，不要主动去做这些。

效果评估关键数字（`docs/eval/summary.md`）：接近饱和时匈牙利比贪心平均等待 −45%；潮汐需求下空车调度 −20%
（4 单/分）/ −18%（5 单/分）；感知场景零误检、零碰撞；16 车高负载演示场景 6 × 60 分钟零碰撞（含撞障碍）。

### 待做（按优先级，AI 可以继续做）

1. ~~全流程文档 + UML~~ ✅ 已完成：索引 `docs/README.md`；UML 用 PlantUML（`docs/uml/*.puml`，18 张，共用 `_style.iuml`），
   改了源文件跑 `tools/render_uml.py`。真机 / Matlab 本地图窗 / 带界面 Gazebo 实测后要补 `docs/test-report.md` 第 7 节和 `docs/evaluation.md` 4.6、4.8 节。
2. ~~提交物~~ ✅ 已完成（`deliverables/`，生成方法见 `docs/README.md`"提交物"一节）：技术报告 docx/pdf、
   答辩 PPT（`tools/build_slides.js`，pptxgenjs）、演示录屏（`tools/record_demo.py`）。封面信息与个人总结已填写；
   改了实验数据要同步报告、PPT 里的数字并重新生成。
   沙箱里 pptxgenjs 没有预装：`npm install` 到临时目录后用 `NODE_PATH=<目录>/node_modules` 运行。
3. ~~Gazebo 接入~~ ✅ 已完成（`gazebo/`，见其 README）：世界生成器 + ROS 2 桥接包 + launch，无界面模式实测 5 车零碰撞。
   云端复现方法：`micromamba create -n ros -c conda-forge -c robostack-jazzy python=3.11 ros-jazzy-ros-base ros-jazzy-ros-gz
   colcon-common-extensions`（约 6 GB，conda.anaconda.org 可访问时）；渲染截图要 `apt-get install` Xvfb/Mesa 已有，
   `LIBGL_ALWAYS_SOFTWARE=1 xvfb-run gz sim -s -r <带相机传感器的世界>`，用 `gz topic -e --json-output` 取图（conda 里的
   Python protobuf 版本与 gz-msgs 不匹配，不能用 Python 绑定订阅图像）。带界面的 Gazebo 仍待人在 Ubuntu 上实测。
4. ~~更新 `docs/plan.md` 第 2 节进度表~~ ✅ 已更新。
5. ~~持续集成~~ ✅ 已完成（`.github/workflows/ci.yml`，说明见 `docs/onboarding.md` 6.6 节、`docs/test-report.md` 4.6 节）。
   CI 红了要找根因：先看失败步骤的日志和 `*-logs` 附件里 system_test 的"关键事件"，修代码并补回归测试，不要靠重跑或删检查变绿。
6. 以上 AI 能做的都已完成，并已合并到 main。剩下的是组员实测（见下一小节）；实测结果回来后帮忙补测试报告第 7 节、
   评估报告 4.6 / 4.8 节和技术报告，并重新生成报告 / PPT。其他改进按上面的决定不做。

### 需要人来做（AI 在云端做不了）

- 在自己的 MATLAB 上跑带"Matlab 模拟车视图"图窗的 `run_fleet`，再跑一次 `start_demo`（会自动起 MATLAB）——CI 只验证了 Linux 上无图窗的 R2024b。
- ~~装到真机~~ 2026-10-27 已装到安卓真机，经手机热点接入服务端，功能正常（已写进测试报告第 7 节）。vivo S20、安卓 15，拍照识别稳定。
  97 s 走路驾驶记录在 `docs/eval/phone/`，已写进评估报告 4.6 节、测试报告第 7 节、`phone/README.md` 第 4 节。真机截图在 `phone/docs/real_*.jpg`
  和 `docs/images/phone_pair.jpg`（报告图 5-3，原 Gazebo 图顺延为 5-4）；技术报告 6.3、6.5、7.7、8.2 节和 PPT 附录 1 已同步，docx/pdf 用
  LibreOffice 重新生成（44 页；之前 WPS 导出的是 40 页，多出的是新增的图和段落，以及 LibreOffice 的目录多占 1 页）。
  CSV 里的经纬度两列已删掉（仓库公开）。还差（可选）：带停步的走路驾驶（测停步到停车的时间）、10 m 正方形实验。
- 向老师确认 `docs/plan.md` 第 8 节的问题。~~中文 Windows 上跑 `start_demo.bat`~~ 2026-10-28 已在中文 Windows 11 上跑通（没装 MATLAB，
  自动用 Python 模拟车；已写进测试报告第 7 节）。用户准备装 MATLAB，装好后再跑 `run_fleet`（带图窗）和 `start_demo`，结果补进测试报告第 7 节、
  评估报告和技术报告 6.5 节。
- 拍摄真机 / 现场演示视频；准备答辩。每人的具体任务见 `docs/onboarding.md` 第 7 节。

## 7. 已知坑

- 云沙箱的网络权限因环境而异：有的会话里 Maven Central、Google Maven、Gradle、npm、PyPI、CDN 都访问不了（403 或 DNS 失败），
  不要反复重试；有的会话可以访问（2026-10-24 那次可以 `pip install matplotlib`、`apt-get install graphviz`、下载 PlantUML）。无论如何：
  服务端不用 Spring Boot、Gson jar 随仓库提供；网页不用 Three.js；Android APK 无法在沙箱里构建（交给 CI）。
- 安卓层在沙箱里只能对照手写的安卓 API 桩做编译检查，桩没有提交进仓库；改了安卓代码以 CI 的 Android 任务（真实 SDK）为准。
- 在 Windows 的 Git Bash 里：Java 类路径分隔符是 `;` 不是 `:`（`scripts/phone_sim.sh` 因此在 Git Bash 下跑不了，
  用 `.bat` 或手动 javac/java）；Java 中文输出会乱码，加 `-Dstdout.encoding=UTF-8`，Python 用 `python -X utf8`。
- Windows 上系统 Python 没有 LibreOffice 的 uno，LibreOffice 自带的 Python 又没有 python-docx，所以报告分两步生成：
  `python tools/build_report.py`，再用 `<LibreOffice>/program/python.exe tools/build_report.py --pdf-only`
  （更新目录、导出 PDF，并把目录写进 docx——不写的话 Word / WPS 打开时目录是空的，要手动"更新域"）。
  LibreOffice 的配置目录必须用 `pathlib.Path(...).as_uri()`：写成 `file://C:\...` 会弹"bootstrap.ini 已经损坏"（文件其实没坏）。
  导出 PDF 后关闭文档偶尔会卡住（soffice.bin 一直满负荷），`to_pdf` 只等 20 秒，然后用 `taskkill /T` 连子进程一起结束。
  报告里不放 UML 图（缩到 A4 后字看不清），用表格和文字写；表格列宽由 `widen_tables` 按内容计算，源文件里写 `|---|` 即可。
- 组员可能直接用 WPS / Word 改 `deliverables/` 里的文件。重新生成报告或 PPT 之前，先看 `docProps/core.xml` 的
  `lastModifiedBy`、`docProps/app.xml` 的 `Application` 和修改时间，被人改过就先比较内容、把改动并回源文件。
- Windows 版 PlantUML 自带 Graphviz 2.44.1（`java -jar plantuml.jar -testdot` 可以看到），但 `tools/render_uml.py`
  只看 PATH 里有没有 `dot`，没有就强制用 smetana 排版（和其他图风格不一致）。在 Windows 上渲染前，把任意一个
  `dot.exe` 所在目录加进 PATH（或者装 Graphviz）。
- 在 Windows 上录演示视频（`tools/record_demo.py`）：Playwright 自带的 Chromium 和 ffmpeg 都要从国外下载，可以不装。
  写一个名为 `playwright` 的转接模块（放进 `NODE_PATH`），转发 `playwright-core`，并把 `chromium.launch` 的 `executablePath`
  指向本机 Edge；Playwright 录屏用的 ffmpeg 放在 `$PLAYWRIGHT_BROWSERS_PATH/ffmpeg-<版本>/ffmpeg-win64.exe`（版本号见
  `playwright-core/browsers.json`），转 MP4 的 ffmpeg 要在 PATH 里。完整的 ffmpeg 可以从国内 PyPI 镜像下载 `imageio-ffmpeg` 包，
  包里自带 exe。Git Bash 会把以 `/` 开头的参数改写成 Windows 路径，必要时加 `MSYS_NO_PATHCONV=1`。
- `.bat` 里不要用 `timeout /t N` 等待：输入被重定向时（CI、部分 IDE 终端）会直接报错退出，用 `ping -n N+1 127.0.0.1 >nul`。
- GitHub Actions 的同一分支并发组会取消正在跑的运行：等一轮 CI 结果时不要推送会触发 CI 的提交（只改文档的可以）。
- CI 的 MATLAB / 系统测试任务里有真实的时序（设备延迟、模拟手机车走走停停的节奏），同一份代码在不同运行里可能碰到不同情况：
  D12 就是分支上通过、main 上才出现的。偶发的红灯也要找原因，不要当成"运气不好"。
- `pkill -f DwServer`（以及任何 `pkill -f <命令行里也出现的字符串>`）会把执行它的 shell 也杀掉；启动 / 停止服务端请记录 PID。
  `kill` 掉 `ros2 run` / `xvfb-run` / `timeout` 等包装进程时子进程会残留（同名 Gazebo 话题出现多个发布者，车辆位置乱跳）：
  用 `setsid` 启动、`kill -- -<pgid>` 结束，或用 ros2 launch。
- 云沙箱可能被重置（未提交的代码会丢）：每完成一块就提交推送。万一丢了，可以从会话记录
  （`~/.claude/projects/*/*.jsonl`，按 parentUuid 链还原顺序）里重放 Write / Edit 调用恢复。
- POSIX 区域设置下 Java 读命令行里的中文参数会变成"?"：运行工具前 `export LC_ALL=C.UTF-8`，并加 `-Dstdout.encoding=UTF-8`。
- 服务端测试依赖工作目录（`server/`）；`experiment.sh` 在仓库根目录运行，`--scenario` 可以只写场景名。
- `AndroidManifest` 里 `LocationListener` 的三个旧回调必须保留实现，否则安卓 10 及以下机型会崩溃。
- 安卓本地单元测试编译时用的是 android.jar，测试代码不能用 `com.sun.*`、`javax.imageio` 等 JDK 专有 API。
- 演示服务器只有 2 个 CPU 核时图像处理的并行加速比只有 1.0~1.4（报告里已如实写明），学生电脑核数多会更高。
