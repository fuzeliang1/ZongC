# 版本记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。每次中期、期末提交前在 `main` 分支打标签（如 `v0.8-midterm`），
并把"未发布"一节改成对应的版本号和日期。协议本身的版本变化见 [docs/protocol.md 第 7 节](docs/protocol.md#7-版本记录)。

## [未发布]

### 新增

- **Matlab 车队本地实测**（2026-10-28，Windows 11，MATLAB R2024b，只装 MATLAB 本体）：带"Matlab 模拟车视图"的 `run_fleet`
  连续运行约 16 分钟，5 辆车合计行驶约 5920 m，零碰撞、零越权；`start_demo.bat` 自动启动 MATLAB 车队正常。
  结果写进测试报告第 7 节、评估报告 4.7 节、技术报告 6.3 / 6.5 节，图窗截图见 `docs/images/matlab_fleetview.png`。
- **安卓真机实测**（2026-10-27，vivo S20，安卓 15）：接入、走路驾驶、拍照识别正常；97 s 记录（`docs/eval/phone/`）中
  往返延迟中位数 34 ms，车从未越过授权停车点。结果写进评估报告 4.6 节、测试报告第 7 节、技术报告 6.3 / 7.7 节，
  真机截图见 `phone/README.md` 和报告图 5-3。部署手册补充多网卡时选哪个地址、用微信 / QQ 传 APK 的注意事项。
- 全流程过程文档：[立项申请书](docs/proposal.md)、[需求规格说明](docs/requirements.md)（用例图、功能 / 非功能需求、
  验收标准、需求追踪矩阵）、[软件设计说明](docs/design.md)（组件图、部署图、类图、时序图、状态图、活动图、设计决策）、
  [测试计划与测试报告](docs/test-report.md)、[部署与使用手册](docs/deployment.md)、[效果评估报告](docs/evaluation.md)、
  [文档索引](docs/README.md)。
- UML 图的 PlantUML 源文件（`docs/uml/`）与渲染结果（`docs/images/uml/`），`tools/render_uml.py` 一键重画。
- `tools/plot_eval.py`：把评估实验 CSV 画成报告用的图表（`docs/images/eval/`）。
- `tools/system_test.py`：系统集成测试——自动启动服务端、5 辆模拟车和模拟手机，运行演示场景，断开车队检查接管，
  调用图像识别，断言零碰撞。
- **Gazebo 接入**（`gazebo/`）：`gen_world.py` 由地图生成 Gazebo Harmonic 世界（格子合并成矩形）和 N 辆差速小车；
  ROS 2 桥接包 `minididi_bridge`（每车一条 TCP 连接注册为 `gazebo` 设备，纯追踪 + 授权限速，延迟补偿与提前刹车，
  传送失败自动重试；车辆后端抽象成接口，附纯 Python 运动学后端）；launch 文件；9 项单元测试。
  在 ROS 2 Jazzy + Gazebo Harmonic 无界面模式下实测 5 车 240 s 零碰撞、零越权。
- **技术报告**：源文件 `docs/report/report.md`，`tools/build_report.py` 生成 `deliverables/MiniDiDi_report.docx` / `.pdf`
  （宋体 / 黑体、三线表、页眉页码、自动目录、公式）。
- 手机位姿估计数据流图（`docs/uml/pdr.puml`）；数字世界类图改为纵向布局。
- **答辩 PPT**：`tools/build_slides.js` 生成 `deliverables/MiniDiDi_slides.pptx`（封面 + 正文 5 页 + 结尾，原生图表、演讲备注）。
- **上手指南**（[docs/onboarding.md](docs/onboarding.md)）：仓库内容、课程要求完成情况、答辩 PPT 内容、上手步骤、
  每位组员的分工与第一批任务。
- **演示录屏**：`tools/record_demo.py` + `tools/record_demo.js` 自动启动系统、录制 2D / 3D 画面并加片头片尾，
  生成 `deliverables/MiniDiDi_demo.mp4`。

- **持续集成**（`.github/workflows/ci.yml`）：每次推送自动在 GitHub Actions 上跑 Linux（服务端单元测试、Gazebo 桥接测试、系统集成测试）、Windows（5 个 `.bat` 一键脚本）、Android（单元测试 + 编译 APK 并上传）、MATLAB（R2024b 中跑 SimCar 离线测试和 5 辆 Matlab 车接入的系统测试）四组任务；第一次运行全部通过。
- `run_fleet` 增加第 4 个参数 `duration`（运行多少秒后自动结束）；`tools/system_test.py` 增加 `--fleet external`（等待外部车队接入、到时自己断开）、`--bind-timeout`、`--exit-timeout`。

### 修复

- `start_demo.bat` 在输入被重定向时（CI、部分 IDE 终端）`timeout` 直接报错、不等服务端启动：改用 `ping` 等待。
- `system_test.py` 在服务端发布第一帧快照前读取会报错：等第一帧发布后再读。
- 外部设备（Matlab / Gazebo 车）前方已授权的格子出了状况（当时是行人走进来）、授权被收回时，设备在收到新停车点之前多走进一格会被误记为"越权行驶"（CI 中 Matlab 车队发现）：收回后 1 s 内、原授权范围内的越界改为单独统计 `revokeOverruns`，不算违规；新增回归测试。
- 刚启动的 MATLAB 运行带图窗的 `run_fleet` 时，注册后要等十几秒画图、等其余车辆建立连接，3 s 内没上报状态，5 辆车被服务端判为心跳超时断开（自动重连后恢复，但命令行先报一屏红字；2026-10-28 在 Windows 上实测发现）：改为先打开本地视图、建好全部连接，最后一起注册。

### 变更

- **手机改为模拟车**（课程 PPT 第 12 页"以人携带手机运动来模拟车辆的运动"）：手机 App 以 `phone` 注册后绑定一辆数字车
  （默认 car-6），车沿服务端规划的路线行驶，人走路的速度就是油门（`RouteFollower`，始终不越过授权停车点）；设置里新增
  "车号""驾驶比例"；CSV 增加车的位姿、车速、沿路线弧长与授权停车点；小地图显示我的车的路线和授权停车点。
  `tools/mock_devices.py` 的模拟手机改为同一套逻辑的 `PhoneCar`；系统集成测试检查手机车全程接入并沿路线行驶；
  手机核心单元测试 66 项，服务端新增 `WorldTest.arcLengthDeviceCompletesTrips`（替换行人避让测试，仍为 30 项）。
  技术报告、答辩 PPT（第 4 页"拿着手机走路就能开车"）重新生成；演示视频重录（4 分 41 秒，3D 跟车段跟拍手机车 car-6）。
- **技术报告的图改为表格和文字**：UML 图缩到 A4 页宽后字看不清，报告第 1–4 章的甘特图、用例图、组件图、部署图、类图、
  状态图、活动图、时序图和手机数据流图都改写成表格（如表 3-6 车辆状态转移、表 4-1 手机端的数据流）和分步说明，
  完整 UML 图仍在需求规格说明、软件设计说明里。`tools/build_report.py`：导出 PDF 时把 LibreOffice 算好的目录写进 docx
  （以前要在 Word 里"更新域"才有目录）；新增 `--pdf-only`（Windows 上用 LibreOffice 自带的 Python 运行）；表格列宽
  先保证短列一行放得下、长驼峰名在单词边界换行，单元格行距改为"最小值"（中英文单元格对齐），12 行以内的表格不跨页。
  报告删去第 7 章"部署与使用"和附录 B"复现方法"（部署见部署与使用手册），评估、总结顺延为第 7、8 章；1.2 节首句精简。
- **答辩 PPT 重新设计**（参考 Anthropic pptx skill 的版式建议）：改为 16:9 宽屏，正文字号不小于 13 磅、标题 30 磅，
  每页一个结论写在副标题里；新配色（石墨 + 橙 + 青绿）；架构图、移动授权示意、流程、甘特图都用原生形状画；
  主题里补上中文字体（原来中文会落到"等线"）；正文仍为 5 页，结尾后新增 4 页附录（课程要求完成情况、交通管控规则、
  评估与测试数据、小组分工与进度）供问答时使用。
- 填写小组成员与分工（阮飞翔、樊宇泽、李昊博、付泽亮，4 人均分）：立项申请书、项目计划、技术报告、答辩 PPT 同步更新。
- 代码统一托管在 GitHub 仓库 `fuzeliang1/ZongC`，不另建 gitee 库；删除周会纪要模板。

### 移除

- 行人智能体及相关功能（随"手机改为模拟车"一起）：服务端的自由智能体（`FREE` 状态、`taxi` 字段、行人占用表、
  `vehicle.phoneRadius`、行人接触统计 `freeContacts` / `freeAgents`）和车给行人让路；手机 App 的"设位置""对方向"、
  比例尺与坐标换算 `MapFrame`、叫车。

## 2026-10-23（开发版本，未打标签）

### 新增

- **全自主演示**（`c6450d4`）：`start_demo` 一键启动服务端（`demo` 场景）、Matlab 车队（没有 MATLAB 时用 Python 模拟车）、
  模拟手机和 2D / 3D 视图；手机 App 打开即自动连接上次的服务端。
- **3D 视图**（`a8b4047`）：自己实现的 WebGL 渲染器，城市、车辆、障碍、雷达扫描、路线；自由 / 俯视 / 跟随 / 巡游四种视角。
- **图像处理加速模块**（`1a70ac4`）：手机拍照上传，服务端颜色分割 + 开运算 + 连通域识别路障（按行并行），
  针孔模型测距后写入全局地图；网页图像识别面板；手机"拍照"按钮；`VisionBench` 测速。
- **网页显示感知、场景与需求；评估实验一键重跑**（`5961945`）：场景进度与横幅、感知图层、雷达扫描、需求热力图；
  `tools/run_evaluation.py` 跑完全部评估实验并生成 `docs/eval/summary.md`。
- **场景脚本、感知与建图、空车调度**（`c2645e6`）：按时间自动触发事件并循环播放；模拟激光雷达 + 占据栅格发现未知障碍；
  单行道被堵时掉头、上下车点被切断时就近改点；需求热点与分区空车调度；安全统计新增车撞障碍。
- **安卓手机 App**（`db5f17d`）：行人航位推算（计步 + Weinberg 步长 + 陀螺仪航向）、室外磁力计与卫星融合、标定、
  小地图、叫车、模拟行走、数据记录与 MATLAB 分析脚本；核心算法 64 项单元测试（后增至 65 项）。
- **Matlab 模拟车、单元测试与脚本**（`bd89519`）：DwClient、SimCar（纯追踪 + 速度 PI）、run_fleet、FleetView；
  服务端首批 10 项 JUnit 测试；Windows / Linux 一键脚本；项目计划、架构、协议、开发指南。
- **第一版**（`e13aa62`）：数字世界服务端（栅格地图、A*、移动授权、贪心 / 匈牙利派单、碰撞监测、设备接入、REST + SSE、
  离线实验）、2D 网页、Python 模拟设备与地图生成器。

### 修复

- 行人走进出租车已授权的格子时，车会从行人身上开过：现在立即收回授权，车停在行人前（`b6a2b82`）。
- 激光雷达擦过建筑转角时误检为障碍：命中格改为光线投射直接撞上的那一格（`c2645e6`）。
- 对比实验中换策略后订单序列也变化：订单改用独立随机数序列（`c2645e6`）。
- 长期空闲的格子上新障碍确认太慢：空闲证据下限收紧到 −2（`1a70ac4`）。

### 其他

- 新增 `CLAUDE.md` 项目交接本（`9b00295`、`4bd075a`）。
