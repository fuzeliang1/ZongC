# 上手指南（新组员从这里开始）

> 写给本组 4 位同学：读完这一篇（约 20 分钟），就知道这个仓库里有什么、课程要求完成到哪一步、
> 怎么把系统跑起来，以及自己接下来该做什么。更细的内容按第 6.4 节的顺序去读其他文档。

## 1. 项目现状

题目是"缩微滴滴"（MiniDiDi / RoboTax）。城市里不断有人叫车，"系统超脑"（数字世界服务端）负责派车、规划路径、
管控交通。车辆可以由服务端自己仿真，也可以由 Matlab 模拟车、手机、Gazebo 小车驱动，这时服务端里的车就是它们的
数字孪生。

**现状**：

- 老师课程 PPT 里的必做项和挑战项，代码都已写完，并合并到 GitHub 仓库 `fuzeliang1/ZongC` 的 `main` 分支。
- 四个提交物（技术报告 docx / pdf、答辩 PPT、演示视频）都已生成初稿，在 `deliverables/`；封面（第 1 组，指导教师许春权）
  和报告 8.3 节的个人总结都已写好。
- 仓库配了持续集成（CI，见 6.6 节）：每次推送自动在 GitHub 的云端机器上验证服务端、Windows 一键脚本、安卓 APK 编译和
  Matlab 车队。Matlab 车已在 MATLAB R2024b 中实际运行（5 车接入零碰撞），安卓 App 已用真实 SDK 编译出 APK，`.bat` 已在 Windows 上跑通。
  `main` 上的 CI 目前全部通过（绿色）；CI 上线后发现并修好了 3 个问题（测试报告缺陷 D10–D12）。
- 还没完成的是**要真设备、人来看**的部分：Matlab 的"模拟车视图"图窗、带界面的 Gazebo、答辩教室的网络。

## 2. 小组分工

| 成员 | 角色 | 主要负责（代码） | 兼任（文档与提交物） |
|---|---|---|---|
| 阮飞翔（2252753，组长） | 架构与调度 | 总体架构、接口协议、派单与空车调度、联调集成、进度管理 | 立项申请书、需求规格说明、技术报告统稿、答辩主讲 |
| 樊宇泽（2351586） | 服务端 | 数字世界与交通管控、设备接入、感知建图、图像处理加速 | 软件设计说明、测试计划与报告 |
| 李昊博（2352853） | 控制与可视化 | Matlab 模拟车（纯追踪 + 速度 PI、5 车车队）、2D 界面与 3D 可视化 | 部署与使用手册、答辩 PPT、演示视频 |
| 付泽亮（2353413） | 移动端与进阶 | 安卓 App 与位姿估计、Gazebo 接入（ROS 2 桥接） | 效果评估报告、GitHub 仓库管理（协作者、分支） |

- 每人主负责一块，同时审查另一块的代码：阮飞翔 ↔ 樊宇泽、李昊博 ↔ 付泽亮。联调全员参加。
- 答辩时老师可能问任何人整体架构，所以每个人都要读 `docs/architecture.md`，能讲清楚"行驶授权为什么能保证不撞车"
  "匈牙利派单比贪心好在哪"。

## 3. 仓库里有什么

### 3.1 系统本体

| 模块 | 位置 | 做了什么 |
|---|---|---|
| 数字世界服务端 | `server/`（Java 17，约 6500 行） | 栅格城市地图；A* 路径规划（不能原地掉头，堵死时借对向车道掉头）；派单（贪心 / 匈牙利）；交通管控（格子预约 + 移动授权）；碰撞监测；REST + SSE 接口；TCP / UDP 设备接入 |
| 场景脚本 | `server/scenarios/` | `demo`、`perception`、`rush-hour` 三个，按时间自动制造路障、未知障碍、早高峰、订单潮，循环播放。这是"考核时不碰电脑"的关键 |
| 感知与建图 | `server/.../world/Perception.java` | 每辆车模拟 48 束激光雷达；系统一开始不知道的障碍靠车"看到"后写进地图，车辆重新规划或掉头；障碍消失后同样要车确认才删掉 |
| 图像识别 | `server/.../vision/` | 手机拍照上传，服务端按颜色找出锥桶、测距、写进地图；颜色分割和开运算按行多线程并行 |
| 空车调度 | `server/.../world/Rebalancer.java` | 统计各区域叫车需求，用匈牙利算法把空车提前调往需求多的区域 |
| 2D 网页 | `web/index.html` | 实时地图、统计面板、车辆和订单列表、感知图层、需求热力图、图像识别面板 |
| 3D 网页 | `web/3d.html` | 自己写的 WebGL 渲染器（不用 Three.js，断网也能开）；自由、俯视、跟车、巡游四种视角 |
| Matlab 模拟车 | `matlab/` | `DwClient`（通信）、`SimCar`（纯追踪 + 速度 PI）、`run_fleet`（5 车）、`FleetView`（车队视图）、`test_simcar_offline`（离线自测） |
| 安卓 App | `phone/` | 手机 = 模拟车：绑定一辆数字车（默认 car-6），人拿着手机走路，车沿服务端规划的路线开，走路速度就是油门；惯性位姿估计（行人航位推算：计步 + Weinberg 步长 + 陀螺仪航向），室外融合磁力计和卫星（含北斗）；小地图、拍照识别、模拟行走、数据记录；打开即自动连接 |
| Gazebo 接入 | `gazebo/` | `gen_world.py` 把地图转成 Gazebo 世界和 5 辆差速小车；ROS 2 桥接包 `minididi_bridge` 把每辆小车注册成数字孪生 |
| Python 模拟设备 | `tools/mock_devices.py` | 协议的参考实现；没有 MATLAB 时冒充 Matlab 车，也能冒充手机车 |
| 一键脚本 | `scripts/` | `build`、`start_server`、`start_demo`、`experiment`、`phone_sim`，`.bat` / `.sh` 成对 |

### 3.2 文档（`docs/`，索引见 [README](README.md)）

| 阶段 | 文档 | 内容 |
|---|---|---|
| 立项 | [proposal.md](proposal.md) | 背景、目标、技术方案、可行性、分工、16 周甘特图、风险与对策 |
| 计划 | [plan.md](plan.md) | 进度表、分工、挑战项、现场演示要点、待向老师确认的问题 |
| 需求 | [requirements.md](requirements.md) | 用例图、功能需求 FR、非功能需求 NFR、验收标准、需求追踪矩阵 |
| 设计 | [design.md](design.md) | 组件图、部署图、类图、时序图、状态图、活动图、设计决策 |
| 设计 | [architecture.md](architecture.md) | 交通管控、规划、派单、感知建图、空车调度、图像识别、3D 的算法细节 |
| 设计 | [protocol.md](protocol.md) | 设备协议（TCP / UDP）、浏览器接口（REST / SSE）、地图与场景脚本格式 |
| 实现 | [dev-guide.md](dev-guide.md) | 开发环境、常用命令、目录结构、配置、代码约定、Git 协作 |
| 测试 | [test-report.md](test-report.md) | 测试计划、105 项单元测试清单、集成测试、缺陷记录 D01–D12、已知限制 |
| 部署 | [deployment.md](deployment.md) | 软硬件要求、一键演示、现场网络、**考核当天检查单**、常见问题 |
| 评估 | [evaluation.md](evaluation.md)、[eval/summary.md](eval/summary.md) | 指标、实验方法、结果与分析；原始数据 CSV |
| 发布 | [CHANGELOG.md](../CHANGELOG.md) | 版本记录 |

UML 图共 18 张（用例图 2、组件图、部署图、类图 3、时序图 5、状态图 2、活动图 2、位姿估计数据流图、甘特图），
用 PlantUML 写，源文件在 `docs/uml/`，图片在 `docs/images/uml/`。改了源文件后运行 `python3 tools/render_uml.py` 重画。

### 3.3 测试与评估工具

| 命令 | 做什么 | 时间 |
|---|---|---|
| `cd server && mvn test` | 服务端单元测试 30 项 | 约 1 分钟 |
| `cd phone && ./gradlew test` | 手机核心算法单元测试 66 项（需要 Android SDK） | 约 1 分钟 |
| `cd gazebo/minididi_bridge && python3 -m unittest discover -s test` | Gazebo 桥接包测试 9 项（不需要 ROS） | 几秒 |
| `python3 tools/system_test.py` | 系统集成测试：服务端 + 5 车 + 1 辆手机车，跑演示场景、断线接管、图像识别，断言零碰撞 | 约 4 分钟 |
| `python3 tools/run_evaluation.py` | 全部效果评估实验 → `docs/eval/summary.md` + CSV | 约 5 分钟 |
| `python3 tools/plot_eval.py` | 评估 CSV → `docs/images/eval/*.png`（需要 matplotlib） | 几秒 |
| 推送到 GitHub（自动） | 持续集成：以上单元测试和系统集成测试 + Windows 一键脚本 + 编译 APK + Matlab 车队，见 6.6 节 | 约 10 分钟 |

### 3.4 提交物（`deliverables/`）

| 文件 | 内容 | 源文件 / 重新生成 |
|---|---|---|
| `MiniDiDi_report.docx` / `.pdf` | 技术报告 40 页：1 概述、2 需求、3 设计、4 关键算法、5 实现、6 测试、7 评估、8 总结，参考文献，附录（源码结构） | 改 `docs/report/report.md`，运行 `python3 tools/build_report.py --pdf` |
| `MiniDiDi_slides.pptx` | 答辩 PPT：封面 + 正文 5 页 + 结尾 + 附录 4 页（问答备用），正文每页带演讲备注 | 改 `tools/build_slides.js` 后重新生成（见第 8 节） |
| `MiniDiDi_demo.mp4` | 演示视频 4 分 41 秒：片头 → 2D 主界面 → 3D 巡游 → 3D 跟随手机车 car-6 → 片尾，全程无人操作 | `python3 tools/record_demo.py --car car-6` |

演示视频里的 5 辆车是 Python 模拟车，按 Matlab 车的协议接入；手机车 car-6 也是模拟的（`tools/mock_devices.py` 的 `PhoneCar`
模拟人拿着手机走走停停），片头已如实说明。Matlab 车队和真机实测通过后可以重录一版。

## 4. 对照课程要求的完成情况

### 4.1 必做（决定能否拿"良"）

| 课程要求 | 状态 | 实现 |
|---|---|---|
| 数字世界：≥5 辆数字车 | ✅ | 默认 8 辆，可配置 |
| 随机运输任务 | ✅ | 泊松到达的订单，完整的接驾 → 上车 → 送客 → 下车 |
| 基于最优化的路径规划 | ✅ | A* 最短路（带转弯代价）；匈牙利算法全局最优派单 |
| 人机交互与动态显示 | ✅ | 2D 实时地图、统计面板、配置面板 |
| 不与静态 / 动态障碍碰撞 | ✅ | 格子预约：一格只给一辆车，两车授权区永不重叠；16 车高负载 6 × 60 分钟零碰撞（含撞障碍） |
| Matlab 模拟车 ≥5 辆，与数字车同步 | ✅ | CI 中用 MATLAB R2024b 实际运行：离线控制器测试通过，5 辆 Matlab 车接入服务端 200 s，零碰撞、零越权（本地带图窗运行待确认） |
| 手机 App：读 GPS / 北斗、加速度、陀螺、地磁，无线上报并同步，惯性元件估计位姿 | ✅ | 代码完成（按 PPT"以人携带手机运动来模拟车辆的运动"：手机驾驶一辆数字车，走路即油门），核心算法 66 项测试通过并做过端到端仿真，CI 已编译出 APK；2026-10-27 在 vivo S20 真机上实测通过 |

### 4.2 挑战（决定能否拿"优"）

| 课程要求 | 状态 | 实现 |
|---|---|---|
| 3D 渲染可视化 | ✅ | 自写 WebGL，四种视角 |
| 环境感知与辅助建图 | ✅ | 模拟激光雷达 + 占据栅格；发现、写入、撤除障碍全自动 |
| 端云互动（服务端做重型图像处理） | ✅ | 手机拍照 → 服务端识别锥桶 → 写进地图 |
| 服务端图像处理加速模块 | ✅ | 按行并行，2 核机器加速比 1.26–1.41（核数越多越明显） |
| Gazebo 中 ≥5 辆车（进阶） | ✅ / 🟡 | 无界面模式实测 5 车零碰撞、零越权；带界面版本待在 Ubuntu 上实测 |
| 自选：调度优化与定量评估 | ✅ | 空车调度 + 配对对比实验 |

### 4.3 考核方式

| 课程要求 | 状态 | 实现 |
|---|---|---|
| 启动后自主运行，考核中不可人工切换模式、设置或遥控 | ✅ | 一键 `start_demo`；场景脚本约 6 分钟一轮、循环播放；手机 App 打开即自动连接；设备断线 3 秒后由服务端接管 |
| 整体可集成运行 | ✅ | `tools/system_test.py` 一次跑通全流程 |
| 文档覆盖立项 → 需求 → 设计 → 测试 → 部署 → 效果评估，图符专业 | ✅ | 第 3.2 节；UML 按规范绘制 |

### 4.4 提交物

| 课程要求 | 状态 | 还差什么 |
|---|---|---|
| 技术报告 docx / pdf | ✅ 初稿 | 封面与个人总结已填；实测后补第 5、6 章 |
| 介绍 PPT（正文 ≤5 页） | ✅ 初稿 | 实测后可补真机照片 |
| 源码附件 | ✅ | GitHub 仓库本身 |
| 图片和短视频（≤5 分钟） | ✅ | 真机和现场照片、视频要人拍 |

代码统一放在 GitHub 仓库 `fuzeliang1/ZongC`，不另建 gitee 库。

### 4.5 关键数字（答辩时可以直接用，出处 `docs/eval/summary.md`）

| 实验 | 结果 |
|---|---|
| 派单：16 车、16 单/分（接近饱和） | 匈牙利比贪心平均等待少 45%（70.4 s → 38.6 s），送达多 13% |
| 空车调度：潮汐需求 | 平均等待少 20%（4 单/分）、18%（5 单/分）；负载越高空车越少，效果越弱（6 单/分时 −6%） |
| 感知建图 | 零误检；未知障碍陆续出现、消失；零碰撞 |
| 安全 | 16 车 6 × 60 分钟，每次送达约 855 单，车车 / 车墙 / 车撞障碍全为 0，最小车距 0.28 m |
| 图像处理加速 | 2 核机器加速比 1.26–1.41 |
| Gazebo | 5 车 240 秒，零碰撞、零越权 |
| Matlab 车队（CI，MATLAB R2024b） | 5 车接入 200 秒，送达 19 单，零碰撞、零越权，最小车距 0.28 m；结束后由数字世界接管 |

改了影响结果的代码后要重跑 `tools/run_evaluation.py`，并同步更新报告和 PPT 里的数字。

## 5. 答辩 PPT 每页讲什么

| 页 | 标题 | 内容 |
|---|---|---|
| 封面 | 缩微滴滴：多智能体无人出租车系统 | 第 1 组、指导教师许春权、组长与组员；3D 视图截图 |
| 01 | 系统总体 | 架构图：服务端（六个模块）+ 网页、Matlab、手机、Gazebo，统一的 TCP 协议；三个设计支柱（零碰撞、断线接管、全自主） |
| 02 | 派单与调度 | A*、匈牙利派单、空车调度；三种负载下平均等待的柱状图；−45%、−20% |
| 03 | 安全与感知 | 2D 截图；移动授权示意图（两车的授权格互不相交、停车点）；感知建图流程与数字 |
| 04 | 手机与端云互动 | 走路驾驶和拍照识别两条流程；小地图截图；计步误差、位置差、并行加速 |
| 05 | 挑战与评估 | Gazebo 截图；六个挑战项；零碰撞、105 项单元测试、0.2 s 接管、0 次人工操作 |
| 结尾 | 谢谢！请老师指正 | 仓库地址 github.com/fuzeliang1/ZongC |
| 附录 1–4 | 课程要求完成情况、交通管控规则、评估与测试数据、小组分工与进度 | 问答时翻到；不算正文页数 |

正文每页底部都有演讲备注，可以直接当讲稿练。

## 6. 上手步骤

### 6.1 拿到代码

付泽亮先在 GitHub 仓库的 Settings → Collaborators 里把其他三位加为协作者（各自接受邀请邮件），然后：

```bash
git clone https://github.com/fuzeliang1/ZongC.git
cd ZongC
```

### 6.2 装环境

| 谁 | 装什么 | 说明 |
|---|---|---|
| 所有人 | JDK 17 或更新（推荐 21，例如 Adoptium Temurin） | `java -version` 检查。服务端唯一的第三方库 Gson 已经放在 `server/lib/`，不需要 Maven |
| 所有人 | Python 3.8+ | 模拟车、模拟手机车，只用标准库 |
| 所有人 | Chrome / Edge 新版 | 3D 需要 WebGL，集成显卡即可 |
| 樊宇泽（可选） | IntelliJ IDEA 社区版 | File → Open 选 `server/pom.xml`；运行 `com.zongc.dw.DwServer`，工作目录设为 `server` |
| 李昊博 | MATLAB R2020b 或更新 | 不需要工具箱 |
| 付泽亮 | Android Studio 2024.2+、安卓 8.0 以上的手机 | 打开 `phone/` 文件夹（不是仓库根目录） |
| 付泽亮 | Ubuntu 24.04 + ROS 2 Jazzy + Gazebo Harmonic | 只用于带界面的 Gazebo，最好是双系统、有独显，见 `gazebo/README.md` 第 3 节 |

### 6.3 先把系统跑起来看一遍（最重要）

| 系统 | 命令 |
|---|---|
| Windows | 双击 `scripts\start_demo.bat` |
| Linux / macOS | `scripts/start_demo.sh` |

依次启动：

1. 服务端，运行"期末演示"场景；
2. 5 辆车：装了 MATLAB 就启动 Matlab 车队（MATLAB 启动约半分钟，之前由服务端内部驾驶），没装就用 Python 模拟车；
3. 1 辆模拟手机车（驾驶 car-6，模拟拿着手机的人走走停停）；
4. 浏览器打开 2D（http://localhost:8080 ）和 3D（http://localhost:8080/3d.html ）。

之后什么都不用点。一轮约 6 分钟，按下面的时间线自动发生：

| 时间 | 事件 | 看什么 |
|---|---|---|
| 0 s | 开始，匈牙利派单，6 单/分 | 车前方的浅色格子是"授权"，任意两车不重叠 |
| 30 s | 已知路障：交通管制封路 | 途经车辆立即绕行 |
| 60 s、90 s | 施工围挡、掉落货物（**系统事先不知道**） | 车的雷达扇形扫到后，地图上出现橙色锥桶，车重新规划或掉头 |
| 120 s | 早高峰：西南居民区 → 东北商务区 | 紫色需求热点 |
| 150 s | 开启空车调度 | 空车被提前调往热点 |
| 210 s | 演唱会散场，一次 8 单 | 派单压力 |
| 240–265 s | 路障解除，障碍撤走 | 障碍要等车"看到"消失后才从地图上删掉 |
| 300 s | 恢复平峰，关闭空车调度 | — |
| 340 s | 本轮结束，20 秒后重新开始 | 统计面板的碰撞计数始终是 0 |

其他用法：

- 只起服务端：`scripts/start_server.sh`（Windows：`scripts\start_server.bat`）。加 `--scenario perception` 或 `rush-hour`
  单独排练某个情节，`--scenario none` 不运行场景。
- `start_demo.sh` 的参数：`--cars auto|matlab|python|none`、`--phones N`、`--scenario 名称`、`--no-browser`。
- 3D 视图的网址参数：`3d.html?mode=tour`（自动巡游）、`3d.html?car=car-3&mode=follow`（跟随 car-3）。
- 日志在仓库根目录的 `server.log`、`mock_cars.log` 等文件里。Linux / macOS 按 Ctrl+C 结束全部进程；Windows 关闭各命令行窗口。

### 6.4 读文档的顺序

1. 根目录 [README.md](../README.md)（10 分钟）：项目是什么、课程要求对照表。
2. 本篇。
3. [plan.md](plan.md)：分工、进度、现场演示要点；第 8 节是要问老师的问题。
4. [architecture.md](architecture.md)：算法怎么工作。**每个人都要读**。
5. [protocol.md](protocol.md)：设备协议。李昊博（Matlab）、付泽亮（手机、Gazebo）必读。
6. 自己模块的 README：[matlab/README.md](../matlab/README.md)、[phone/README.md](../phone/README.md)、[gazebo/README.md](../gazebo/README.md)。
7. [CLAUDE.md](../CLAUDE.md) 第 4 节：改代码前必须知道的规则（线程模型、坐标系、交通管控的不变量）。

### 6.5 改代码的规矩

- **`main` 永远能编译、测试通过、能演示**。新功能开分支（例如 `feature/matlab-mpc`、`fix/xxx`），发 Pull Request，
  按第 2 节的结对审查、**CI 全部通过（绿色 ✓）** 后再合并。合并前在本地跑一遍单元测试和一键演示。
- **改服务端逻辑要补测试**（`server/src/test`），改手机算法也要补（`phone/app/src/test`）。没有 Maven 时只用 `javac` + JUnit 4
  的跑法见 `CLAUDE.md` 第 3 节。
- **零碰撞是硬指标**：改了影响结果的逻辑，重跑 `python3 tools/system_test.py` 和 `python3 tools/run_evaluation.py`，
  碰撞（含车撞障碍）必须全为 0。
- **改协议要同步改四处**：`docs/protocol.md`、`tools/mock_devices.py`、`matlab/`、`phone/`（Gazebo 桥接用到的话还有 `gazebo/`）。
- **新的演示动作写进场景脚本**（`server/scenarios/`，格式见 `docs/protocol.md` 第 6 节），不要依赖现场点按钮。
- 文档、注释、界面、提交信息都用中文；`.sh` 用 LF 换行，`.bat` 用 CRLF 且只写英文注释（见 `.gitattributes`）。
- 手机 App 只用 Java 8 语法和安卓自带 API，`core` 包不能引用 `android.*`；网页不用第三方库。

### 6.6 持续集成（CI）怎么看

仓库的 `.github/workflows/ci.yml` 让 GitHub 在每次推送和 Pull Request 后自动开四台云端机器测试（只改文档的提交不触发），
约 10 分钟出结果。结果在 [Actions 页面](https://github.com/fuzeliang1/ZongC/actions/workflows/ci.yml) 查看，根目录 README 顶部的徽章也会显示通过（passing）或失败（failing）。

| 任务 | 验证什么 | 失败时先看 |
|---|---|---|
| 服务端与系统测试（Linux） | 服务端 30 项、Gazebo 桥接 9 项单元测试；离线实验；系统集成测试（服务端 + 5 车 + 1 辆手机车，零碰撞） | 樊宇泽 |
| Windows 一键脚本 | `build.bat`、`experiment.bat`、`start_server.bat`、`phone_sim.bat`、`start_demo.bat` 在 Windows 上能用 | 樊宇泽 |
| 手机 App | 66 项单元测试；用真实安卓 SDK 编译 APK | 付泽亮 |
| Matlab 模拟车 | `test_simcar_offline`；`run_fleet` 的 5 辆 Matlab 车接入服务端 200 s，零碰撞、零越权 | 李昊博 |

- **下载 APK**：点最新一次成功的运行，页面底部 Artifacts 里的 `MiniDiDi-phone-debug-apk`（需要登录 GitHub，保留 30 天）。
- **失败了怎么办**：点进红色的任务，展开红色 ✗ 的步骤看日志；系统测试的服务端、模拟车日志在 Artifacts 里（`*-logs`）。
  修好后再推送，CI 会自动重跑。不要为了变绿去删测试或跳过检查，也不要直接点"重新运行"了事：MATLAB 和系统测试里有真实的时序
  （设备延迟、模拟手机车走走停停），同样的代码这次过、下次不过，往往是真问题（缺陷 D12 就是这样发现的）。
- **系统测试表怎么读**：碰撞、撞墙、撞障碍、越权行驶必须全为 0。"收回授权时来不及停下"不算失败：外部设备车前突然出现路障或障碍时，
  设备要等下一条授权到达才知道新停车点，可能多走进一格（见 `docs/architecture.md` 5.3 节"新障碍收回授权"一条）。
- MATLAB 任务能免费运行是因为仓库是公开的；如果改成私有库，需要向 MathWorks 申请批处理许可令牌，
  在仓库 Settings → Secrets 里配置为 `MLM_LICENSE_TOKEN`，否则这个任务会失败。

## 7. 每个人的第一批任务

| 成员 | 任务 | 怎么做 | 完成后更新 |
|---|---|---|---|
| 阮飞翔 | 问老师 | `docs/plan.md` 第 8 节的四个问题 | `docs/plan.md` |
| 阮飞翔 | 组织现场彩排 | 按 `docs/deployment.md` 第 5、6 节：自带路由器、关 AP 隔离、防火墙放行 8080 / 9000 / 9001；在答辩教室完整跑 10 分钟，碰撞全为 0 | `docs/test-report.md` 第 7 节 |
| 樊宇泽 | 在自己的 Windows 笔记本上跑一次 `start_demo.bat`（✅ 2026-10-28 已在一台中文 Windows 11 上跑通，没装 MATLAB 时用 Python 模拟车） | CI 已在英文版 Windows 上验证 5 个 `.bat`；这里确认中文系统的控制台、浏览器里 2D / 3D 页面的显示，以及防火墙弹窗 | `docs/test-report.md` 第 7 节 |
| 樊宇泽 | 在自己电脑上跑全部测试 | IDEA 或 `mvn test` 跑服务端 30 项；`python3 tools/system_test.py`；熟悉 `World.java`、`TrafficController.java` | `docs/test-report.md` 第 7 节（实测结果） |
| 李昊博 | Matlab 车队本地运行 | CI 已用 MATLAB R2024b 跑通离线测试和 5 车接入；在自己电脑上启动服务端、跑带"Matlab 模拟车视图"的 `run_fleet`，网页上 car-1 … car-5 显示为 Matlab 驱动；再跑一次 `start_demo`，确认它会自动拉起 MATLAB | `matlab/README.md`、`docs/test-report.md` 第 7 节 |
| 李昊博 | 用真 MATLAB 重录演示视频 | Matlab 车队跑通后运行 `python3 tools/record_demo.py --car car-6`（需要 Node.js + Playwright、ffmpeg），片头文字在 `tools/record_demo.py` 里改 | `deliverables/MiniDiDi_demo.mp4` |
| 付泽亮 | 加协作者 | GitHub → Settings → Collaborators，邀请另外三位 | — |
| 付泽亮 | 手机真机实测（✅ 已装机实测，2026-10-27） | 还可以补做：`phone/README.md` 第 4 节的 10 m × 10 m 正方形行走实验（测闭合误差），以及带停步的走路驾驶实验（停步后多久停车） | `docs/evaluation.md` 4.6 节、`phone/README.md` 第 4 节 |
| 付泽亮 | 带界面的 Gazebo | 在 Ubuntu 24.04 上按 `gazebo/README.md` 第 3–5 节安装、构建、运行，截图、录屏 | `gazebo/README.md` 第 6 节、`docs/evaluation.md` 4.8 节 |
| 每个人 | 读一遍自己的个人总结 | 报告 8.3 节已按分工写好，实测后可以补上真机、MATLAB 上遇到的问题 | `docs/report/report.md` |

实测中遇到的问题和结果补进测试报告和评估报告后，由阮飞翔统一同步到技术报告第 5、6、8 章并重新生成。

## 8. 怎么重新生成提交物

| 提交物 | 需要的工具 | 命令 |
|---|---|---|
| 技术报告 | pandoc、`pip install python-docx`；PDF 还要 LibreOffice | `python3 tools/build_report.py --pdf`（Windows 上分两步，见脚本开头的说明） |
| 答辩 PPT | Node.js；在任意临时目录 `npm install pptxgenjs react-icons react react-dom sharp` | `NODE_PATH=<临时目录>/node_modules node tools/build_slides.js` |
| 演示视频 | Node.js + Playwright（`npm i playwright && npx playwright install chromium`）、ffmpeg、Pillow | `python3 tools/record_demo.py`（8080 / 9000 / 9001 端口不能被占用） |
| UML 图 | JDK（第一次会自动下载 PlantUML；装了 Graphviz 更好） | `python3 tools/render_uml.py` |
| 评估图表 | `pip install matplotlib` | `python3 tools/plot_eval.py` |

注意：报告和 PPT 都是由源文件生成的，**直接在 Word / PowerPoint 里改的内容，下次重新生成会被覆盖**。
内容修改请改源文件；在 Word / PowerPoint 里只做提交前最后一步的小修饰。Windows 上装 pandoc 和 LibreOffice 比较麻烦，
建议由一个人（阮飞翔）负责生成。

## 9. 已知限制与风险

| 项 | 说明 | 对策 |
|---|---|---|
| Matlab 车队 | CI 中 MATLAB R2024b（Linux）实测通过；图窗显示、Windows 上的 MATLAB、更老的 MATLAB 版本没测 | 李昊博在自己电脑上跑一次；不行时 `start_demo` 会自动改用 Python 模拟车，演示不受影响 |
| 安卓真机 | 已在 vivo S20 上实测通过；连接时要填 WLAN 网卡的地址（见部署手册 4.1 节） | `LocationListener` 的三个旧回调不能删，否则安卓 10 及以下会崩溃 |
| Windows 脚本 | CI（英文版 Windows）中全部通过；2026-10-28 中文 Windows 11 上 `start_demo.bat` 一键启动、全自主运行正常 | 装 MATLAB 的电脑上再跑一次，确认自动启动 Matlab 车队 |
| 现场网络 | 只在本机测试过；校园网常有终端隔离 | 自带路由器、固定 IP、放行端口，答辩教室彩排 |
| 课程要求 gitee | 课程 PPT 写的是 gitee 私有库，小组决定只用 GitHub | 如果老师坚持，在 gitee 上"从 GitHub 导入仓库"即可，几分钟完成 |
| 2 核电脑 | 图像处理并行加速比只有 1.0~1.4 | 报告已如实写明；演示电脑核数多时会更高 |

## 10. 用 AI 继续开发

仓库根目录的 [CLAUDE.md](../CLAUDE.md) 是写给 AI 助手（以及新组员）的交接本：项目要求、架构不变量、常用命令、
进度和已知的坑都在里面。新开 Claude Code 会话时，让它先读 `CLAUDE.md` 就能接上进度。
每完成一项工作，记得更新 `CLAUDE.md` 第 6 节和 `docs/plan.md` 第 2 节的进度，并随代码一起提交推送。
