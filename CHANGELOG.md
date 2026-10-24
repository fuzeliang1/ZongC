# 版本记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。每次中期、期末提交前在 `main` 分支打标签（如 `v0.8-midterm`），
并把"未发布"一节改成对应的版本号和日期。协议本身的版本变化见 [docs/protocol.md 第 7 节](docs/protocol.md#7-版本记录)。

## [未发布]

### 新增

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

### 变更

- 填写小组成员与分工（阮飞翔、樊宇泽、李昊博、付泽亮，4 人均分）：立项申请书、项目计划、技术报告、答辩 PPT 同步更新。
- 代码统一托管在 GitHub 仓库 `fuzeliang1/ZongC`，不另建 gitee 库；删除周会纪要模板。

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
