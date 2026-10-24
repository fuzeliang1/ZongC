# Matlab 模拟车

PPT 任务"基于 Matlab 的模拟车"的实现：5 辆在 PC 上运行的模拟车，通过网络与数字世界中的数字车保持同步（数字孪生），在地图描述的世界中行驶，不与静态障碍和其他车辆碰撞。

> 说明：这部分代码按 `tools/mock_devices.py`（协议参考实现）逐行移植，已在 GitHub Actions（CI）中用 **MATLAB R2024b（Linux）实际运行**：`test_simcar_offline` 通过；`run_fleet` 的 5 辆车接入服务端运行 200 s，送达订单、零碰撞、零越权，结束后由数字世界接管（见 `docs/test-report.md` 4.6 节）。CI 里不显示图窗，在自己电脑上第一次使用时请先跑 `test_simcar_offline`，再跑带"Matlab 模拟车视图"的 `run_fleet`。

## 文件

| 文件 | 作用 |
|---|---|
| `run_fleet.m` | 入口函数：建立 5 条 TCP 连接，注册 car-1 … car-5，20 Hz 控制循环，10 Hz 上报状态 |
| `SimCar.m` | 一辆模拟车：独轮车运动学模型、纯追踪转向、速度 PI 控制、遵守行驶授权、前车急停 |
| `DwClient.m` | TCP 连接封装：每行一条 JSON，非阻塞收取消息 |
| `FleetView.m` | 本地实时视图：从服务端获取地图，画出各车位置、路线和授权停车点 |
| `test_simcar_offline.m` | 不连服务端的控制器测试：检查不越过停车点、能到达终点，并画出轨迹和速度曲线 |

## 运行

1. 启动服务端（仓库根目录）：Windows 双击 `scripts\start_server.bat`，或 `scripts/start_server.sh`。
2. MATLAB 当前目录切到本文件夹：

   ```matlab
   test_simcar_offline          % 先离线检查控制器
   run_fleet                    % 连接本机服务端，接管 car-1 … car-5
   run_fleet("192.168.1.20")    % 服务端在另一台电脑上
   run_fleet("127.0.0.1", [], false, 180)   % 不显示视图，运行 180 s 后自动结束（CI 用）
   ```

3. 浏览器打开 `http://localhost:8080`，车辆列表中 car-1 … car-5 的驱动会显示为"Matlab M"。
4. 关闭"Matlab 模拟车视图"窗口或按 Ctrl+C 结束。断开后数字世界会自动接管这些车，系统继续运行。

需要 MATLAB R2020b 或更新版本（用到 `tcpclient`、`jsonencode` / `jsondecode`、字符串数组）。不需要任何工具箱。

## 它和服务端怎么配合

```
服务端（数字世界）                          Matlab（SimCar）
  welcome  ── 初始位姿、车辆参数 ──────────▶  设置起点
  path     ── 平滑路线的路点（每 0.25 m）──▶  纯追踪跟踪
  permit   ── stopS：最远可以开到哪里 ─────▶  v ≤ √(2·a·(stopS − s))，绝不越界
  world    ── 周边车辆位置（5 Hz）────────▶  前方近距离有车则急停
           ◀────────── state：x, y, θ, v（10 Hz）── 上报位姿
```

服务端负责派单、全局路径和交通管控（保证任意两车不会被授权进入同一个格子），Matlab 负责把车开好。消息格式详见 `docs/protocol.md`。

## 可以在这里做的改进（挑战项）

- 用 **MPC** 替换纯追踪 + PI，与原方案对比跟踪误差和通行时间；
- 加入执行器一阶惯性、转向延迟、速度噪声，让模型更接近真车；
- 用**模拟激光雷达**（在地图上做射线检测）感知障碍物，把发现的路障上报给服务端（见项目计划中的挑战项 C）；
- 用**速度障碍法（VO/ORCA）**做局部避让，替换现在的"前车急停"。
