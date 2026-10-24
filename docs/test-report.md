# 测试计划与测试报告

| 项 | 内容 |
|---|---|
| 被测版本 | `main` 分支，提交 `4bd075a` 之后（含本报告所在提交） |
| 测试日期 | 2026-10-24（评估实验数据生成于 2026-10-23） |
| 测试环境 | Linux 云主机，4 核（评估实验时 2 核）；OpenJDK 21；Python 3.12；Chromium（Playwright 无头模式）；GitHub Actions（ubuntu-latest、windows-latest，Temurin JDK 17，MATLAB R2024b） |
| 结论 | 服务端 29 项、手机核心 65 项、Gazebo 桥接 9 项单元测试全部通过；系统集成测试、手机端到端仿真、网页冒烟测试、Gazebo 联调（无界面模式）全部通过；持续集成中 Windows 一键脚本、安卓 APK 编译、Matlab 车队（MATLAB R2024b，5 车接入）全部通过；安卓真机、带界面的 Gazebo 尚未实测 |

## 1. 测试计划

### 1.1 目标

验证系统满足 [需求规格说明](requirements.md) 中的功能需求和非功能需求，尤其是：零碰撞（NFR-01）、设备断线接管（FR-33）、
全自主运行（FR-60、FR-61），以及各挑战项的效果。

### 1.2 测试层次与方法

| 层次 | 对象 | 方法 | 工具 | 自动化 |
|---|---|---|---|---|
| 单元测试 | 服务端世界层、算法层；手机核心算法与网络 | 白盒；随机输入 + 不变量断言；与穷举 / 广度优先搜索对拍；传感器模拟器 | JUnit 4 | ✅ `mvn test`、`gradlew test` |
| 集成测试 | 服务端 + 设备协议 + 场景脚本 | 真实启动服务端，用协议参考实现（Python 模拟设备）接入 5 车 + 1 手机；中途断开车队 | `tools/system_test.py` | ✅ |
| 端到端测试 | 手机核心代码 ↔ 服务端 | 同一套核心代码模拟"拿着手机走路"接入运行中的服务端 | `scripts/phone_sim.sh`（`PhoneSim`） | ✅ |
| 界面冒烟测试 | 2D、3D 网页 | 无头浏览器打开页面，等数据流动，检查控制台错误并截图 | Playwright + Chromium | ✅（脚本见 4.4） |
| 系统测试（性能、安全、长时间） | 整个数字世界 | 离线仿真：派单对比、空车调度对比、感知、6 × 60 分钟高负载 | `tools/run_evaluation.py` | ✅ |
| 持续集成 | 以上自动化测试 + Windows 一键脚本 + 安卓 APK 编译 + Matlab 车队 | 每次推送在 GitHub 云端机器上自动运行（4.6 节） | GitHub Actions | ✅ |
| 现场测试 | Matlab 车队本地图窗、安卓真机、带界面的 Gazebo、现场网络 | 按部署手册在真实设备上运行 | 人工 | ❌ 待人来做 |

**为什么大量用随机测试**：交通管控、规划这类逻辑的边界情况很多，手写用例很难覆盖。测试里用固定种子的随机输入
（300 组随机起终点、15 分钟 16 车稠密交通等）反复检查不变量（零碰撞、路线合法、不卡死），既能覆盖大量情况，又能复现。

### 1.3 准入与准出

- **准入**：代码能编译；上一轮单元测试全部通过。
- **准出**：单元测试 100% 通过；所有测试和实验中车车碰撞、撞墙、撞障碍、越权行驶计数均为 0；系统集成测试通过；
  已知缺陷全部修复或在第 6 节说明。

### 1.4 测试环境搭建

```bash
cd server && mvn test                    # 服务端单元测试（需要能访问 Maven Central）
cd phone && ./gradlew test               # 手机核心单元测试（需要 Android SDK）
python3 tools/system_test.py             # 系统集成测试（约 4 分钟，8080/9000/9001 端口不能被占用）
scripts/start_server.sh & scripts/phone_sim.sh   # 手机端到端仿真
python3 tools/run_evaluation.py          # 评估实验（约 5 分钟）
```

没有 Maven / Android SDK 时可以直接用 javac + JUnit 运行，命令见仓库根目录 `CLAUDE.md` 第 3 节。

## 2. 服务端单元测试（29 项，全部通过）

| 测试类 | 用例 | 验证内容 | 结果 |
|---|---|---|---|
| `PlanningTest` | `defaultMapHasNoDeadEnds` | 默认地图每个车道格沿箭头方向都能继续行驶 | ✅ |
| | `laneRulesAreEnforced` | 车道只能沿箭头方向驶入、驶出，路口任意方向 | ✅ |
| | `astarFindsLegalShortestPaths` | 300 组随机起终点：路线每一步合法、不原地掉头；与广度优先搜索的最短步数对比 | ✅ |
| | `routeGeometryIsContinuousAndInsideRoads` | 平滑路线连续，车辆中心离建筑始终 ≥ 0.2 m | ✅ |
| `DispatchTest` | `hungarianMatchesBruteForce` | 300 个随机代价矩阵（1~6 × 1~6）：匈牙利算法与穷举结果相同 | ✅ |
| | `hungarianBeatsOrTiesGreedy` | 匈牙利算法的总代价不大于贪心 | ✅ |
| | `unreachablePairsAreNeverAssigned` | 到不了的"车—单"组合不会被匹配 | ✅ |
| `WorldTest` | `denseTrafficRunsWithoutCollisionsOrDeadlock` | 16 车、16 单/分，贪心和匈牙利各运行 15 分钟：零碰撞、零撞墙、离墙 > 0.15 m、受阻不超过绕行阈值、送达 > 150 单 | ✅ |
| | `carsNeverEnterRoadblocks` | 繁忙道路上设两处路障，5 分钟内没有车进入路障格 | ✅ |
| | `taxisStopForPedestriansInsideTheirAuthority` | 行人突然走进出租车已授权的格子：车在行人前停下，行人离开后继续行驶 | ✅ |
| | `deviceTakesOverAndFallsBack` | 设备注册后接管数字车并收到 welcome / path / permit；断开后内部仿真接管 | ✅ |
| `ScenarioTest` | `shippedScenariosAreValid` | 仓库中的全部场景脚本都能通过校验 | ✅ |
| | `invalidScenariosAreRejectedWithReasons` | 错误脚本（格子越界、不可通行、缺字段……）被拒绝并指出第几个事件、什么问题 | ✅ |
| | `eventsFireOnTimeAndLoopRestoresTheWorld` | 事件按时触发；循环播放时新一轮撤掉上一轮的路障和障碍 | ✅ |
| | `obstaclesWaitUntilNoAgentIsNearby` | 未知障碍在 2.5 m 内有车或人时推迟出现 | ✅ |
| `PerceptionTest` | `lidarMeasuresBuildingsVehiclesAndBoundary` | 射线距离和命中类型与几何一致：前方的车、北边的建筑、南边的地图边界 | ✅ |
| | `hiddenObstacleAheadIsDiscoveredAndAvoided` | 系统不知道的障碍出现在车前：被发现、写入地图，车停下或绕开，绝不撞上 | ✅ |
| | `removedObstacleIsClearedFromTheMap` | 障碍撤走后，车辆路过观测到空了，系统自动从地图上删掉 | ✅ |
| | `noFalseDiscoveriesInDenseTraffic` | 没有障碍时稠密交通运行 10 分钟：不把车辆、建筑转角误判为障碍 | ✅ |
| | `carTurnsAroundWhenItsLaneIsCutOff` | 单行道前方被封死、目的地也被切断：掉头，目的地改到附近，不卡住 | ✅ |
| | `perceptionScenarioIsSafe` | 感知场景完整跑几轮：零碰撞、不卡死、障碍都被发现 | ✅ |
| `RebalanceTest` | `ordersFollowDemandHotspots` | 订单起点按热点权重分布，热点附近比例接近设定值 | ✅ |
| | `idleTaxisAreSentTowardsHotspots` | 开启调度后空车被派往需求高的区域 | ✅ |
| | `rebalancingShortensWaitingUnderTidalDemand` | 潮汐需求、同样的订单序列：开启调度后平均等待明显缩短 | ✅ |
| `VisionTest` | `detectsConeAndEstimatesBearingAndDistance` | 合成照片中的锥桶被识别，方位角和距离估计正确 | ✅ |
| | `ignoresScenesWithoutTargets` | 没有目标的场景不误检 | ✅ |
| | `parallelAndSerialAgree` | 并行与单线程的识别结果完全一致 | ✅ |
| | `rejectsGarbage` | 非图片数据被拒绝，给出原因 | ✅ |
| | `phonePhotoUpdatesTheMapAndExpires` | 手机拍到的路障写进地图、车辆绕行；120 s 后过期，由感知撤掉 | ✅ |

运行结果（`mvn test`）：`Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`。耗时最长的是 `RebalanceTest`（约 10 s）和 `WorldTest`（约 7 s），都是长时间仿真。

## 3. 手机核心单元测试（65 项，全部通过）

手机 App 的 `core` 包是纯 Java，可以在电脑上测试。测试用传感器模拟器 `SyntheticWalker`（按姿态旋转矩阵生成带噪声的加速度、
陀螺、磁力读数）模拟"人拿着手机走路"，用 `TinyHttpServer` 和本地 TCP 服务模拟服务端。

| 测试类 | 数量 | 用例与验证内容 |
|---|---|---|
| `StepDetectorTest`（计步） | 8 | `countsNormalWalking` 正常步行计数准确；`countsSlowAndFastWalking` 慢走、快走；`independentOfHowThePhoneIsHeld` 平拿、竖拿、斜拿结果一致；`standingStillGivesNoSteps` 静止不计步；`singleJoltsAreIgnored` 单次晃动不计步；`firstStepOfABoutIsConfirmedRetroactively` 一段行走的第一步事后补记；`amplitudeGrowsWithStrongerSteps` 步子越重幅值越大；`survivesTimestampGapsAndDuplicates` 时间戳跳变、重复不出错 |
| `HeadingEstimatorTest`（航向） | 9 | `integratesTurnsWhenFlat` 平放时积分转角；`integratesTurnsWhenTiltedOrRolled` 倾斜、侧翻时仍正确；`tracksTurnsWhileWalking` 走路时跟踪转弯；`magneticHeadingMatchesTrueHeading` 倾斜补偿后的磁航向等于真实航向；`initialHeadingComesFromMagnetometer` 初始航向取自磁力计；`learnsGyroBiasWhileStill` 静止时学习陀螺零偏；`magnetometerCorrectionRemovesDriftOutdoors` 室外磁力计修正消除漂移；`historyLookupInterpolates` 按时间查历史航向会插值；`phoneWithoutGyroFollowsMagnetometer` 没有陀螺仪的手机退化为磁力计 |
| `PdrEngineTest`（位姿估计） | 12 | `walkingASquareReturnsHome` 走正方形回到起点；`setPositionAndAlignHeadingDefineTheMapFrame` 设位置、对方向确定地图坐标系；`faceTowardPointsHeadingAtTarget` 面向目标点；`mapScaleMultipliesDisplacementWithoutJumping` 改比例尺不跳变；`simulatedWalkingMovesAlongPhoneHeading` 模拟行走沿手机朝向；`stepCalibrationFixesTheStepLength` 步长标定；`calibrationWithTooFewStepsIsRejected` 步数太少拒绝标定；`gnssKeepsLongWalksOnTrack` 卫星融合抑制长距离漂移；`indoorModeIgnoresGnssAndBadFixesAreRejected` 室内忽略卫星、坏定位被拒绝；`switchingToOutdoorModeKeepsMapPoseContinuous` 切换室外模式位姿连续；`restoreAndSmoothing` 恢复位姿与输出平滑；`configSurvivesSerialization` 参数保存再读出不变 |
| `GeoTest`（坐标） | 3 | `wrapsAngles` 角度归一化；`enuMatchesHaversineAroundTongji` 东北天换算与球面距离公式一致（同济校园附近）；`arrowsPointTheRightWay` 方向箭头 |
| `MapDataTest`（地图） | 6 | `parsesServerMapWithNorthRowFirst` 解析服务端地图（第一行最北）；`tinyMapOrientation` 小地图方向；`nearestLaneSkipsBuildingsCrossingsAndRoadblocks` 最近车道格跳过建筑、路口、路障；`noLaneReturnsNull` 没有车道时返回空；`rejectsInconsistentRows` 行长不一致报错；`parsesServerSnapshot` 解析快照 |
| `JsonTest`（JSON） | 6 | 嵌套结构、转义与 Unicode、缺字段和类型错误、非法输入、紧凑输出、与区域设置无关的数字格式 |
| `EndpointTest`（服务端地址） | 5 | 纯 IP 用默认端口、冒号后是 TCP 端口、浏览器网址设置 HTTP 端口、IPv6 与主机名、非法输入 |
| `DwConnectionTest`（设备协议） | 4 | `registersAsPhoneAndStreamsStateAtTenHertz` 注册为 phone 并 10 Hz 上报；`reconnectsAfterTheServerDropsTheConnection` 断线重连；`silentServerIsDetected` 服务端无响应被发现；`refusedConnectionGivesAFriendlyMessage` 连接被拒时提示友好 |
| `MapClientTest`（HTTP） | 9 | 取地图和快照、叫车返回订单号、上传照片并解析识别结果、服务端错误信息传回、未知路径、服务端不可达、轮询器先取地图再取快照、轮询出错后继续重试、IPv6 地址加方括号 |
| `CsvLogTest`（数据记录） | 3 | 位姿行与表头对齐、IMU 行、写入出错时记录而不抛出 |

运行结果：`OK (65 tests)`（用 `javac --release 8` 编译，同时保证没有用到安卓 8.0 不支持的 Java 9+ API）。

## 4. 集成测试与端到端测试

### 4.1 系统集成测试（`tools/system_test.py`）

**步骤**：编译并启动服务端（`demo` 场景脚本）→ 启动 5 辆按协议注册为 `matlab` 的模拟车和 1 部模拟手机 → 运行 180 s，每 10 s 采样指标 →
杀掉模拟车队进程，检查接管 → 上传示例照片做图像识别 → 再运行 30 s → 断言。全程没有人工操作。

**结果：全部通过**（退出码 0）。

| 检查项 | 结果 |
|---|---|
| 5 辆模拟车接入 | ✅ 启动后 0.5 s 内全部注册，接管 car-1 … car-5（数字孪生） |
| 订单流程 | ✅ 仿真 211 s 内生成 32 单、送达 16 单，平均等待 36.9 s |
| 车车碰撞 / 撞墙 / 撞路障与障碍 / 越权行驶 | ✅ 0 / 0 / 0 / 0 |
| 最小车距 / 最小离墙 | 0.342 m / 0.137 m |
| 场景脚本 | ✅ 第 60 s 自动放出未知障碍 (35,15)，第 90 s 左右放出 (11,20) |
| 感知发现障碍 | ✅ car-8 在障碍出现 6.0 s 后发现 (35,15) 并借对向车道掉头；car-7 在 13.1 s 后发现 (11,20) 并掉头 |
| 行人避让 | ✅ car-5、car-2 共 4 次为行人 phone-1 紧急停车 |
| 断线接管 | ✅ 杀掉车队进程 0.2 s 后，5 辆车全部由内部仿真接管，系统继续运行 |
| 图像识别 | ✅ 示例照片识别出 1 个路障（右前方 9°，约 7.31 m），格子 (17,8) 写入全局地图 |
| 最长连续受阻 | 3.4 s（没有卡死） |
| 行人接触 | 1 次（模拟手机随机横穿马路时从侧面走进车身，见第 7 节） |

关键事件日志（节选，方括号内为仿真时间）：

```
[  0.4] 设备 matlab@127.0.0.1:33404 接管 car-1（数字孪生）     … car-2 至 car-5 同时接管
[ 60.0] 【场景】(35,15) 突然出现施工围挡——系统并不知道，要靠车辆的激光雷达发现
[ 66.1] car-8 通过激光雷达发现未知障碍 (35,15)（障碍出现 6.0 s 后），已加入全局地图，相关车辆重新规划
[ 68.2] car-8 前方道路不通，在 (34,15) 借对向车道掉头
[103.2] car-7 通过激光雷达发现未知障碍 (11,20)（障碍出现 13.1 s 后），已加入全局地图，相关车辆重新规划
[112.3] car-5 为行人 phone-1 紧急停车
[180.9] 设备 127.0.0.1:33404 断开，car-1 由数字世界接管      … car-2 至 car-5 同时接管
[181.5] web 通过图像识别发现未知障碍 (17,8)，已加入全局地图，相关车辆重新规划
```

同一天还手工做过一次相同流程的集成测试（运行 250 s），结果一致：零碰撞、零越权，2 处未知障碍被发现，断开后约 5 s 内全部接管，
行人接触 2 次。

### 4.2 手机端到端仿真（`scripts/phone_sim.sh`）

用手机 App 的同一套核心代码（计步、航向、坐标换算、设备协议、HTTP 客户端）模拟一个拿着手机走路的人，接入正在运行的服务端：

| 检查项 | 结果 |
|---|---|
| 接入数字世界 | ✅ 注册成功，编号 phone-9 |
| 计步 | ✅ 检测 43 步 = 模拟器实际 43 步，行走 26.8 m |
| 位置同步 | ✅ 服务端看到 phone-9 共 63/64 次，与手机本地位置平均差 0.13 m、最大 0.34 m |
| 行人站到 car-1 前方第 5 格 | ✅ car-1 在行人前停车，车身与行人最小间隙 0.13 m；行人离开后恢复行驶 |
| 行人站到 car-4 前方第 3 格 | ✅ car-4 在行人前停车，最小间隙 1.13 m；行人离开后恢复行驶 |
| 叫车 | ✅ 订单 T058 创建成功，(23,21) → (44,25)，进入排队 |
| 断开 | ✅ phone-9 从数字世界中移除 |

### 4.3 图像识别接口

用服务端自带的示例照片（960×720，路面 + 橙色锥桶）调用 `POST /api/vision/detect?x=14.5&y=8.5&theta=0&scale=3`：

| 检查项 | 结果 |
|---|---|
| 识别 | ✅ 1 个路障，框 [528, 387, 144, 225]，右前方 8.7°，约 7.31 m（比例尺 3） |
| 写入地图 | ✅ 格子 (17, 8) 写入全局地图 |
| 处理尺寸与耗时 | 缩到 640×480 处理；解码 25.7 ms；单线程识别 4.8 ms；服务端启动后第一次并行识别 116 ms（即时编译与线程池预热，之后与单线程相当） |

### 4.4 网页冒烟测试

用 Playwright 驱动无头 Chromium（3D 页面需要 `--use-angle=swiftshader --enable-unsafe-swiftshader` 软件渲染 WebGL），在服务端 + 5 车 + 手机运行时打开页面，等待 8 s：

| 页面 | 标题 | 控制台错误 | 观察 |
|---|---|---|---|
| 2D 主界面 `/` | MiniDiDi 数字世界 | 0 | 地图、8 辆车（car-1 … car-5 驱动显示为"Matlab M"）、phone-1、场景进度条、"零碰撞"提示均正常显示 |
| 3D 视图 `/3d.html?mode=tour` | MiniDiDi 数字世界 · 3D | 0 | 城市、车辆、雷达扫描正常渲染，自动巡游 |

```js
// 冒烟测试脚本要点（node + playwright）
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));
await page.goto(url); await page.waitForTimeout(8000); await page.screenshot({ path });
```

### 4.5 Gazebo 联调（ROS 2 Jazzy + Gazebo Harmonic，无界面模式）

用 RoboStack（conda）安装的 ROS 2 Jazzy + Gazebo Harmonic 8.6，`ros2 launch minididi_bridge minididi.launch.py gz_args:="-r -s"`
启动 Gazebo 世界、ros_gz_bridge 和桥接节点，接到运行 `demo` 场景的服务端，运行 240 s：

| 检查项 | 结果 |
|---|---|
| 5 辆 Gazebo 小车接入 | ✅ 1.5 s 内全部注册，接管 car-1 … car-5，驱动显示为 GAZEBO |
| 订单 | ✅ 送达 19 单 |
| 车车碰撞 / 撞墙 / 撞障碍 / 越权行驶 | ✅ 0 / 0 / 0 / 0 |
| 最小车距 / 最小离墙 | 0.282 m / 0.106 m |
| 投影越过 stopS 的最大值 / 最大横向偏差 | 0.000 m / 0.092 m |
| 退出 | ✅ Ctrl+C 后正常退出并打印统计 |

另外：桥接包单元测试 9 项（控制器不越过 stopS、授权收回后在新停车点前停下、转弯横向偏差 < 0.1 m、前车急停、
世界生成器矩形合并不重不漏、SDF 结构与话题唯一性）全部通过；纯 Python 运动学后端接入服务端 90 s，最大越界 0.000 m、零碰撞。

### 4.6 持续集成（GitHub Actions）

`.github/workflows/ci.yml` 定义了四个并行任务，每次推送和 Pull Request 自动在 GitHub 的云端机器上运行
（只改文档的提交不触发）。结果在仓库的 [Actions 页面](https://github.com/fuzeliang1/ZongC/actions/workflows/ci.yml) 查看，
README 顶部的徽章显示最近一次的结果。2026-10-24 第一次完整运行（提交 `a6bcf21`）全部通过：

| 任务 | 运行环境 | 内容 | 结果 |
|---|---|---|---|
| 服务端与系统测试 | ubuntu-latest，JDK 17 | `mvn test`（29 项）、Gazebo 桥接测试（9 项）、离线实验冒烟测试、`tools/system_test.py`（180 s + 30 s） | ✅ |
| Windows 一键脚本 | windows-latest，JDK 17，Python 3.12 | `build.bat`、`experiment.bat`、Windows 上的系统集成测试（90 s）；`start_server.bat` 启动后跑 `phone_sim.bat`；`start_demo.bat` 启动后检查 5 辆模拟车 + 1 部模拟手机接入 | ✅ phone_sim 全部检查通过（位置差平均 0.08 m，两次为行人停车，叫车成功）；start_demo 5 车 + 1 手机接入，零碰撞 |
| 手机 App | ubuntu-latest，安卓 SDK | `./gradlew test assembleDebug`：65 项单元测试，用真实安卓 SDK（compileSdk 35）编译调试版 APK，作为附件上传 | ✅ 这是安卓界面层第一次用真实 SDK 编译 |
| Matlab 模拟车 | ubuntu-latest，MATLAB R2024b | `test_simcar_offline`；`system_test.py --fleet external` 启动服务端并等待外部车队，`run_fleet("127.0.0.1", [], false, 200)` 让 5 辆 Matlab 车接入 200 s | ✅ 见下表 |

Matlab 车队第一次在真正的 MATLAB 中运行的结果：

| 检查项 | 结果 |
|---|---|
| 离线控制器测试 | ✅ 第一次授权停车点 4.00 m，实际最远 4.000 m（没有越过）；最终到达终点，距离 0.000 m |
| 5 辆 Matlab 车接入 | ✅ 4.5 s 内全部注册，驾驶方显示为 MATLAB；每辆车运行 200 s，收到 9~11 条路线、约 200 次授权，行驶约 260 m |
| 订单 | ✅ 生成 33 单，送达 19 单 |
| 车车碰撞 / 撞墙 / 撞障碍 / 越权行驶 | ✅ 0 / 0 / 0 / 0 |
| 最小车距 / 最小离墙 | 0.28 m / 0.137 m |
| 场景事件 | ✅ 两处未知障碍分别在出现 6.0 s、16.0 s 后被发现，车辆借对向车道掉头 |
| 车队结束后接管 | ✅ 5 辆车同时断开，全部由数字世界内部仿真接管，系统继续运行 |
| 行人接触 | 1 次（模拟手机从侧面走进车身，见第 7 节） |

CI 没有覆盖的：MATLAB 的"Matlab 模拟车视图"图窗（CI 里不显示图窗）、Windows 上的 MATLAB 和 `start_demo` 自动启动 MATLAB、
中文 Windows 控制台、网页在浏览器中的显示效果、安卓真机运行。

## 5. 系统测试（性能、安全、长时间运行）

由 `tools/run_evaluation.py` 完成，详细数据与分析见 [效果评估报告](evaluation.md)。与需求对应的结论：

| 需求 | 测试 | 结果 |
|---|---|---|
| NFR-01 零碰撞 | 16 车、16 单/分、`demo` 场景，6 × 60 分钟 | ✅ 车车 / 撞墙 / 撞障碍 0 / 0 / 0；最小车距 0.28 m，最小离墙 0.20 m |
| NFR-01 零碰撞（感知） | `perception` 场景，8 × 30 分钟 | ✅ 0 / 0 / 0；离障碍最小间隙 0.08 m |
| NFR-03 派单耗时 | 16 车、16 单/分 | ✅ 平均 0.07~0.6 ms / 次（要求 < 5 ms） |
| NFR-03 仿真速度 | 16 车离线仿真 | ✅ 326 ± 15 倍实时（要求 > 100 倍） |
| FR-24 不死锁 | 6 × 60 分钟高负载 | ✅ 最长连续受阻 12.0 s，达到绕行阈值后均脱困 |
| FR-11 派单优化 | 3 种负载 × 3 种子，配对比较 | ✅ 接近饱和时平均等待 −45% |
| FR-13 空车调度 | 3 种到达率 × 8 种子，配对比较 | ✅ 4 单/分 −20%，5 单/分 −18% |
| FR-51 感知建图 | `perception` 场景 | ✅ 每次仿真发现 25.5 次，平均 30.9 s 发现，零误检 |
| FR-53 并行加速 | 4 种图像尺寸，各取 15 次中位数 | ✅ 2 核 1.26~1.41 倍（大于 1280×960 时） |

## 6. 缺陷记录

开发过程中通过测试发现并修复的缺陷（每个缺陷修复时都补了回归测试）：

| 编号 | 发现方式 | 现象 | 原因 | 修复（提交） | 回归测试 |
|---|---|---|---|---|---|
| D01 | 接入手机后的端到端检查（`PhoneSim`：把行人放到行驶中出租车前方第 3 格） | 行人站进车辆已经预约的格子，车从行人身上开过去 | 交通管控只在"申请新授权"时检查行人，不检查已经授权的格子 | 每个仿真步先检查已授权、未到达的格子，有行人就收回授权（会停在路口里时退到路口前）（`b6a2b82`） | `WorldTest.taxisStopForPedestriansInsideTheirAuthority` |
| D02 | 感知单元测试（稠密交通 10 分钟） | 没有任何障碍时 10 分钟误检 10 处，车辆绕开不存在的障碍 | 用"命中点往前一点"反查命中格，射线擦过建筑转角时落进旁边的路面 | 命中格直接取光线投射撞上的那一格（`c2645e6`） | `PerceptionTest.noFalseDiscoveriesInDenseTraffic` |
| D03 | 设计配对对比实验时 | 换派单策略或开关空车调度后订单序列也跟着变，对比不公平 | 订单生成与车辆行为共用一个随机数序列 | 订单改用只由种子决定的独立随机数序列（`c2645e6`） | `RebalanceTest.rebalancingShortensWaitingUnderTidalDemand`（配对） |
| D04 | 开发图像识别时 | 长期被观测为空的格子上新出现障碍时，要很久才能被确认；拍照写入的路障可能因为"雷达说没有"马上被撤掉 | 空闲证据可以累积得很低，新的占据观测要很久才能推翻；照片里的路障没有登记到真实世界 | 空闲证据下限收紧到 −2（新障碍约 0.3 s 即可推翻）；照片观测不受过去空闲观测影响，并登记到真实世界 120 s（`1a70ac4`） | `VisionTest.phonePhotoUpdatesTheMapAndExpires` |
| D05 | 编写评估脚本时 | 用场景脚本做实验时，命令行指定的到达率被场景里的 `rate` 事件改写，实验条件与预期不符 | 场景脚本的 `rate` 事件会修改到达率 | 命令行指定的到达率优先于场景脚本设置（`5961945`） | `tools/run_evaluation.py` 结果核对 |

本轮测试（Gazebo 联调）新发现并修复的问题：

| 编号 | 发现方式 | 现象 | 原因 | 修复 | 验证 |
|---|---|---|---|---|---|
| D06 | Gazebo 联调 | 物理小车越过授权停车点约 0.08 m，最小车距降到 0.12 m | 里程计经 ros_gz_bridge 有几十毫秒延迟；指令到车轮减速有滞后 | 位姿按最新速度外推到当前时刻；刹车时预留 v × 0.08 s 的距离 | 越界 0.000 m，最小车距 0.28 m |
| D07 | Gazebo 联调 | 转弯时横向偏差 0.15 m，最小离墙 0.05 m | DiffDrive 插件的角加速度上限太低，进弯时角速度建立慢 | 角加速度上限 8 → 25 rad/s²，线加速度 3 → 4 m/s² | 偏差 0.09 m，离墙 0.11 m |
| D08 | Gazebo 渲染截图时 | Gazebo 很忙时小车停在停车场，服务端看到的位置乱跳 | 传送服务超时后改用了里程计里的停车场位置 | 传送确认之前一直重试，只报目标位姿、只发零速度 | 截图运行中 5 车正常行驶，越界 0 |
| D09 | Gazebo 联调 | Ctrl+C 时桥接节点报错、统计不输出 | ROS 上下文关闭后仍发布停车指令 | 先输出统计，发布失败时忽略 | 正常退出 |

搭建持续集成时发现并修复的问题：

| 编号 | 发现方式 | 现象 | 原因 | 修复 | 验证 |
|---|---|---|---|---|---|
| D10 | CI（Windows） | `start_demo.bat` 输出"Input redirection is not supported"，没有等服务端启动就启动了模拟车（靠模拟车自动重连才恢复） | `timeout` 命令在输入被重定向时（CI、部分 IDE 终端）直接报错退出 | 改用 `ping -n 7 127.0.0.1 >nul` 等待 6 s | CI 中无报错，5 车 + 1 手机正常接入 |
| D11 | 开发 CI 的外部车队模式时 | 服务端刚启动时 `system_test.py` 读取快照报 `KeyError: 'agents'` | 第一帧快照发布前接口返回 `{}` | 读取快照时等到第一帧发布 | 外部车队模式与默认模式都通过 |

## 7. 已知限制与未覆盖项

| 项 | 说明 | 计划 |
|---|---|---|
| 行人接触 | 系统集成测试中记录到少量"行人接触"（`freeContacts`）：模拟手机随机横穿马路时从侧面走进行驶中的车身。车只能对**前方**授权格里的行人刹车，行人从侧面撞上来任何车端规则都避免不了，碰撞监测照实记录 | 属设计限制，在报告中说明；演示时手机用户不要横穿车流 |
| Matlab 车队 | CI 中用 MATLAB R2024b（Linux、不显示图窗）实测通过（4.6 节）；"Matlab 模拟车视图"图窗、Windows 上的 MATLAB、`start_demo` 自动启动 MATLAB 尚未实测 | 组员在自己的 MATLAB 上跑一次带视图的 `run_fleet` 和 `start_demo` |
| Windows 一键脚本 | CI（英文版 Windows）中 5 个 `.bat` 全部通过；中文 Windows 控制台、浏览器里的显示效果没有自动检查 | 组员在自己的 Windows 笔记本上跑一次 `start_demo.bat` |
| 安卓真机 | 核心算法已单元测试和端到端仿真；CI 用真实安卓 SDK 编译通过并产出 APK；还没有装到真机上运行 | 从 Actions 下载 APK（或用 Android Studio 编译）安装，做 10 m 正方形实验 |
| Gazebo | 无界面模式（`gz sim -s`）已实测通过（4.5 节）；带界面的 Gazebo 窗口、Ubuntu 上 apt 安装的版本未测 | 在 Ubuntu 24.04 上按 `gazebo/README.md` 带界面运行一次 |
| 现场网络 | 只在本机回环上测试过 | 在答辩教室用自带路由器彩排 |
| 网页交互 | 只做了冒烟测试（加载、渲染、无报错），按钮和表单没有自动化测试 | 演示前人工过一遍控制面板 |

## 8. 结论

- 单元测试 103 项（服务端 29 + 手机核心 65 + Gazebo 桥接 9）全部通过，覆盖规划、派单、交通管控、设备协议、场景脚本、感知建图、空车调度、图像识别、手机位姿估计和 Gazebo 小车控制。
- 系统集成测试在无人操作的情况下完成了设备接入、场景事件、感知发现障碍、掉头脱困、断线接管、图像识别全流程，零碰撞、零越权。
- 长时间高负载实验（6 × 60 分钟）零碰撞，满足必做要求中"不与静态 / 动态障碍碰撞"。
- 5 辆 Gazebo 物理仿真小车接入后同样零碰撞、零越权，联调中发现的延迟与执行滞后问题已修复。
- 持续集成每次推送自动验证：Windows 一键脚本可用；安卓 App 能用真实 SDK 编译出 APK；Matlab 车队在 MATLAB R2024b 中 5 车接入零碰撞、零越权。
- 剩余风险集中在需要真实设备的部分（安卓真机、带界面的 Gazebo、Matlab 本地图窗、现场网络），需要组员在第 13–14 周加固阶段完成并补充本报告。
