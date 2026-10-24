# MiniDiDi 缩微滴滴：多智能体无人出租车系统

综合设计与实践 C 课程项目。城市里不断有乘客叫车，"系统超脑"——数字世界（Digital World, DW）服务端负责派车、规划路径、管控交通，让多辆自主运动的车把乘客从出发地送到目的地。车辆可以由服务端自己仿真，也可以由 Matlab 模拟车、手机或 Gazebo 驱动。外部设备驱动时，服务端里的数字车就是它的数字孪生。

![数字世界实时界面](docs/images/overview.png)

图中，每辆车前方浅色的格子是它当前获得的"行驶授权"。任意两辆车的授权格互不重叠，所以不会相撞。实线路线表示车上有乘客，虚线表示空车。琥珀色圆点是等待接驾的乘客，旗帜是目的地。车周围的扇形是模拟激光雷达的扫描范围，橙色锥桶是车辆感知发现、已写进地图的障碍，紫色虚线圆是早高峰的需求热点。顶部是正在自动运行的场景脚本和下一个事件。

![3D 视图](docs/images/view3d.png)

## 快速开始

只需要安装 **JDK 17 或更新版本**。演示用的模拟设备另需 Python 3。

**Windows**：双击 `scripts\start_demo.bat`，会依次启动服务端（运行"期末演示"场景脚本，之后全程自动）、5 辆 Matlab 模拟车（没装 MATLAB 时自动改用 Python 模拟车）、1 部模拟手机，并打开 2D 和 3D 两个视图。

**Linux / macOS**：

```bash
scripts/start_demo.sh
```

只启动服务端用 `scripts/start_server.bat` 或 `scripts/start_server.sh`，然后浏览器打开 http://localhost:8080 （3D 视图：http://localhost:8080/3d.html ）。加 `--scenario perception`（或 `demo`、`rush-hour`）运行指定的场景脚本。

也可以单独接入 Matlab 模拟车：先启动服务端，然后在 MATLAB 中进入 `matlab/` 目录，运行 `run_fleet`。详见 [matlab/README.md](matlab/README.md)。`start_demo.sh` 的参数：`--cars auto|matlab|python|none`、`--phones N`、`--scenario 名称`、`--no-browser`。

## 已实现的内容

| PPT 要求 | 实现 |
|---|---|
| 可独立运行的数字世界模拟程序，至少 5 辆数字车 | 默认 8 辆，可配置；不接任何设备也能完整运行 |
| 随机生成运输任务，按地图一步步送达 | 泊松到达的订单，完整的接驾→上车→送客→下车流程 |
| 路径规划基于最优化原理与方法 | A*（带转弯代价、遵守车道方向）；派单用匈牙利算法求最优匹配 |
| 人机交互和动态显示 | 实时地图（车辆插值动画）、统计面板、车辆和订单列表、配置管理 |
| 不能与静止障碍物和其他车辆碰撞 | 基于格子预约的移动授权交通管控；独立的碰撞监测计数（长时间测试均为零碰撞） |
| 设备接入模块：UDP/TCP/RESTful | TCP 9000（注册、路线、授权、状态）、UDP 9001（状态上报）、HTTP 8080（REST + SSE） |
| 优化与决策模块 | 贪心 / 匈牙利两种派单策略，可在界面上切换并用离线实验对比 |
| Matlab 模拟车，至少 5 辆，与数字车同步 | `matlab/`：纯追踪 + 速度 PI 控制，遵守行驶授权，断线由数字世界接管 |
| 启动后全自主运行 | 一键启动（含 Matlab 车队）；场景脚本按时间自动制造路障、未知障碍、早晚高峰、订单潮并循环播放（`server/scenarios/`）；手机 App 打开即自动连接；设备断线自动接管、重连自动恢复 |
| 智能：环境感知与辅助建图（第 2 页） | 每辆车 10 Hz 模拟 48 束激光雷达；系统一开始不知道的障碍由车辆发现，占据栅格确认后写入全局地图、收回授权、重规划或掉头，障碍消失后自动撤掉；按真实世界统计"车撞障碍"，长时间实验为零。详见 [架构设计说明 5.9 节](docs/architecture.md#59-环境感知与辅助建图) |
| 优化与决策：空车调度 | 分区需求预测 + 匈牙利算法调度空车；潮汐需求下平均等待降低约 20% |
| 手机 App 作为智能体接入、位姿估计 | `phone/`：安卓 App，行人航位推算（计步 + 步长 + 陀螺仪航向），室外可融合磁力计和卫星定位（含北斗）；出租车会给行人让路，还能在手机上叫车。详见 [phone/README.md](phone/README.md) |
| 3D 可视化（挑战项） | `web/3d.html`：自己实现的 WebGL 渲染（不依赖第三方库，断网也能打开），城市、车辆、乘客、障碍、雷达扫描、路线；自由 / 俯视 / 跟随某辆车 / 自动巡游四种视角 |
| Gazebo（进阶挑战） | `gazebo/`：由地图自动生成 Gazebo Harmonic 世界和 5 辆差速小车；ROS 2 桥接节点把每辆车注册为 `gazebo` 设备（数字孪生），纯追踪 + 授权限速 + 延迟补偿。已在 ROS 2 Jazzy + Gazebo Harmonic 的无界面模式下实测零碰撞，带界面版本待在 Ubuntu 上实测。详见 [gazebo/README.md](gazebo/README.md) |

## 目录

```
server/    数字世界服务端（Java 17+，只依赖 Gson）
web/       网页前端（原生 HTML/CSS/JS，Canvas 2D）
matlab/    Matlab 模拟车
phone/     安卓手机 App（Java，无第三方库；用 Android Studio 打开这个文件夹）
gazebo/    Gazebo 世界生成器与 ROS 2 桥接包（进阶）
tools/     模拟设备（协议参考实现）、地图生成器、评估与系统测试脚本
scripts/   编译、启动、演示、实验脚本（Windows .bat / Linux .sh）
docs/      文档
```

## 文档

全流程文档的索引见 [docs/README.md](docs/README.md)（按立项 → 需求 → 设计 → 测试 → 部署 → 评估组织，UML 图齐全）。常用的几份：

- [立项申请书](docs/proposal.md)、[项目计划](docs/plan.md)：目标、分工、16 周进度、风险
- [需求规格说明](docs/requirements.md)：用例图、功能与非功能需求、验收标准、需求追踪
- [软件设计说明](docs/design.md)：组件图、部署图、类图、时序图、状态图、活动图
- [架构设计说明](docs/architecture.md)：交通管控、规划、派单、感知建图等算法细节
- [接口协议说明](docs/protocol.md)：设备接入（TCP/UDP）、浏览器接口（REST/SSE）、地图与场景格式
- [测试计划与测试报告](docs/test-report.md)、[效果评估报告](docs/evaluation.md)
- [部署与使用手册](docs/deployment.md)：一键演示、现场网络、考核当天检查单
- [开发指南](docs/dev-guide.md)、[版本记录](CHANGELOG.md)

## 测试与实验

```bash
cd server && mvn test                                    # 服务端单元测试（29 项）
cd phone && ./gradlew test                               # 手机 App 核心算法单元测试（65 项）
scripts/experiment.sh --minutes 30 --seeds 1,2,3 --taxis 16 --rate 16   # 派单策略对比
python3 tools/run_evaluation.py                          # 跑完全部效果评估实验，结果写到 docs/eval/summary.md
python3 tools/system_test.py                             # 系统集成测试：服务端 + 5 车 + 手机，断线接管、图像识别，断言零碰撞
scripts/phone_sim.sh                                     # 模拟一部"拿着走路的手机"接入运行中的服务端
```

接近饱和的场景（16 辆车，16 单/分钟）下，匈牙利算法派单比贪心派单的平均等待时间少约 45%（39 s 对 70 s），送达效率高约 13%；潮汐需求下开启空车调度，平均等待再降约 20%；16 车高负载运行演示场景 6 × 60 分钟，零碰撞（含车撞障碍）。详见 [效果评估实验结果](docs/eval/summary.md)。
