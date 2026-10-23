# MiniDiDi 缩微滴滴：多智能体无人出租车系统

综合设计与实践 C 课程项目。城市里不断有乘客叫车，"系统超脑"——数字世界（Digital World, DW）服务端负责派车、规划路径、管控交通，让多辆自主运动的车把乘客从出发地送到目的地。车辆可以由服务端自己仿真，也可以由 Matlab 模拟车、手机或 Gazebo 驱动。外部设备驱动时，服务端里的数字车就是它的数字孪生。

![数字世界实时界面](docs/images/overview.png)

图中，每辆车前方浅色的格子是它当前获得的"行驶授权"。任意两辆车的授权格互不重叠，所以不会相撞。实线路线表示车上有乘客，虚线表示空车。琥珀色圆点是等待接驾的乘客，旗帜是目的地。带"M"标记的车由 Matlab（这里用模拟设备代替）驱动。

## 快速开始

只需要安装 **JDK 17 或更新版本**。演示用的模拟设备另需 Python 3。

**Windows**：双击 `scripts\start_demo.bat`，会依次启动服务端、5 辆模拟车、1 部模拟手机，并打开浏览器。

**Linux / macOS**：

```bash
scripts/start_demo.sh
```

只启动服务端用 `scripts/start_server.bat` 或 `scripts/start_server.sh`，然后浏览器打开 http://localhost:8080 。

接入 Matlab 模拟车：先启动服务端，然后在 MATLAB 中进入 `matlab/` 目录，运行 `run_fleet`。详见 [matlab/README.md](matlab/README.md)。

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
| 启动后全自主运行 | 一键启动；设备断线自动接管、重连自动恢复 |
| 手机 App、Gazebo、3D 可视化 | 尚未开始，见 [docs/plan.md](docs/plan.md) |

## 目录

```
server/    数字世界服务端（Java 17+，只依赖 Gson）
web/       网页前端（原生 HTML/CSS/JS，Canvas 2D）
matlab/    Matlab 模拟车
tools/     模拟设备（协议参考实现）、地图生成器
scripts/   编译、启动、演示、实验脚本（Windows .bat / Linux .sh）
docs/      文档
```

## 文档

- [项目计划](docs/plan.md)：进度、分工、时间安排、挑战项、待向老师确认的问题
- [架构设计说明](docs/architecture.md)：模块划分、线程模型、交通管控与派单算法、实验数据
- [接口协议说明](docs/protocol.md)：设备接入（TCP/UDP）、浏览器接口（REST/SSE）、地图格式
- [开发指南](docs/dev-guide.md)：环境、命令、配置、代码约定、Git 协作、常见问题

## 测试与实验

```bash
cd server && mvn test                                    # 单元测试（10 项）
scripts/experiment.sh --minutes 30 --seeds 1,2,3 --taxis 16 --rate 16   # 派单策略对比
```

接近饱和的场景（16 辆车，16 单/分钟）下，匈牙利算法派单比贪心派单的平均等待时间少约 45%（36 s 对 66 s），送达效率高约 10%。详见 [架构设计说明 5.4 节](docs/architecture.md#54-派单)。
