# 部署与使用手册

> 面向演示操作员和组员：从一台全新的电脑开始，到考核现场全自主运行。开发相关的内容（代码约定、Git 流程）见 [开发指南](dev-guide.md)。

## 1. 软硬件要求

| 用途 | 要求 | 是否必需 |
|---|---|---|
| 演示笔记本 | Windows 10/11、Linux 或 macOS；4 GB 以上内存；有 Wi-Fi | 必需 |
| JDK | 17 或更新（推荐 21，例如 Adoptium Temurin） | 必需 |
| 浏览器 | Chrome / Edge / Firefox 新版；3D 视图需要 WebGL（集成显卡即可） | 必需 |
| MATLAB | R2020b 或更新，不需要工具箱 | Matlab 车队需要；没有时自动用 Python 模拟车 |
| Python | 3.8 或更新，只用标准库 | 没有 MATLAB 时需要；模拟手机车需要 |
| 安卓手机 | 安卓 8.0 或更新，有加速度计、陀螺仪、磁力计 | 手机演示需要 |
| 无线路由器 | 任意家用路由器，能关闭"AP 隔离 / 访客隔离" | 现场演示强烈建议 |
| Gazebo 工作站 | Ubuntu 24.04 + ROS 2 Jazzy + Gazebo Harmonic，最好有独显 | 进阶，可选（见 `gazebo/README.md`） |

检查 JDK：命令行输入 `java -version`，版本应为 17 以上。

## 2. 获取代码与编译

```bash
git clone https://github.com/fuzeliang1/ZongC.git   # 或者解压源码附件
cd ZongC
scripts/build.sh                          # Windows：scripts\build.bat
```

编译只需要 JDK（唯一的第三方库 Gson 已随仓库提供在 `server/lib/`），输出到 `server/build/classes`。
一键启动脚本发现没有编译过会自动编译。

## 3. 一键全自主演示

| 系统 | 命令 |
|---|---|
| Windows | 双击 `scripts\start_demo.bat`（或 `scripts\start_demo.bat perception` 运行指定场景） |
| Linux / macOS | `scripts/start_demo.sh`，参数：`--cars auto\|matlab\|python\|none`、`--phones N`、`--scenario 名称`、`--no-browser` |

启动顺序：

1. 服务端，运行 `demo` 场景脚本（"期末演示"，约 6 分钟一轮、循环播放）；
2. 5 辆模拟车：装了 MATLAB 就启动 Matlab 车队（`run_fleet`），否则用 Python 模拟车（同一协议，网页上同样显示为 Matlab 驱动）；
   MATLAB 启动约需半分钟，在此之前车辆由数字世界内部仿真驾驶；
3. 1 辆模拟手机车（驾驶 car-6，模拟拿着手机的人走走停停）；
4. 浏览器打开 2D 主界面（http://localhost:8080 ）和 3D 视图（自动巡游）。

之后全程**不需要任何操作**：路障、系统不知道的障碍、早晚高峰、订单潮都由场景脚本按时间自动触发，网页顶栏显示场景进度和下一个事件。
Linux / macOS 按 Ctrl+C 结束全部进程；Windows 关闭各个命令行窗口。日志写在仓库根目录的 `server.log`、`mock_cars.log` 等文件中。

## 4. 单独启动各部分

### 4.1 服务端

```bash
scripts/start_server.sh                         # 默认配置，不运行场景脚本
scripts/start_server.sh --scenario perception   # 运行 server/scenarios/perception.json
scripts/start_server.sh --config server/config/my.json
```

启动后控制台会打印"局域网地址"，例如 `http://192.168.1.20:8080`，手机和其他电脑用这个地址。

| 场景 | 内容 |
|---|---|
| `demo` | 期末演示：正常运行 → 已知路障 → 未知障碍被感知发现 → 早高峰 + 空车调度 → 订单潮 → 障碍撤除 → 恢复平峰，循环 |
| `perception` | 感知与建图：4 处未知障碍陆续出现、消失 |
| `rush-hour` | 早晚高峰潮汐需求，用于空车调度对比 |

### 4.2 网页

| 页面 | 地址 | 说明 |
|---|---|---|
| 2D 主界面 | http://localhost:8080 | 实时地图、运行指标、车辆与订单列表、控制面板、事件日志、图像识别面板 |
| 3D 视图 | http://localhost:8080/3d.html | 自由 / 俯视 / 跟随 / 巡游四种视角；`?mode=tour` 自动巡游，`?car=car-3&mode=follow` 跟随某辆车 |

2D 主界面的操作（调试和排练时用，**考核时不要操作**）：

- 点地图上的车查看详情；控制面板可切换派单策略、调整到达率和倍速、暂停 / 继续、开关空车调度、切换场景、开关图层；
- 按住 Shift 点击地图上的格子，设置 / 解除路障；点"手动下单"后在地图上依次点上车点和目的地；
- 图像识别面板可以上传照片或用示例图片测试识别。

### 4.3 Matlab 模拟车

```matlab
cd matlab
test_simcar_offline          % 第一次先离线检查控制器（不需要服务端）
run_fleet                    % 连接本机服务端，接管 car-1 … car-5
run_fleet("192.168.1.20")    % 服务端在另一台电脑上
```

网页车辆列表中 car-1 … car-5 的驱动显示为"Matlab M"即接入成功。关闭"Matlab 模拟车视图"窗口或按 Ctrl+C 结束，
断开后数字世界自动接管这些车。详见 [matlab/README.md](../matlab/README.md)。

### 4.4 手机 App

1. **安装**：用 Android Studio 打开仓库里的 `phone` 文件夹，连接手机点 ▶；或者 **Build → Build APK(s)**，把
   `app/build/outputs/apk/debug/app-debug.apk` 发到手机安装（需要允许"安装未知应用"）。
2. **连接**：手机和电脑连同一个 Wi-Fi，打开 App，输入电脑 IP，点"连接"。顶栏显示"● 已接入，驾驶 car-6"即成功，
   网页车辆列表里 car-6 的驱动变为"手机 P"。以后打开 App 会**自动连接**上次的服务端。
3. **设置与标定**（每次演示前）：在"设置"里确认车号（默认 car-6，Matlab 车队占用 car-1 ~ car-5）和驾驶比例
   （默认 1：人走 1 m，车开 1 m；走得慢可以设 1.5~2）→ 步长标定（走 10 m 直线）。
4. **使用**：拿着手机正常走路，车就沿服务端规划的路线开起来，站住车就停；前方有车或障碍时车停在授权停车点前等待放行；
   "拍照"识别前方的橙色锥桶；没人走动时用"模拟行走"（手机平放，App 按设定的步长和步频自动"走路"）。

完整说明见 [phone/README.md](../phone/README.md)。请让 App 一直留在前台。

### 4.5 Python 模拟设备（后备 / 联调）

```bash
python3 tools/mock_devices.py --cars 5                  # 5 辆模拟车（generic）
python3 tools/mock_devices.py --cars 5 --kind matlab    # 冒充 Matlab 车（与 start_demo 相同）
python3 tools/mock_devices.py --cars 0 --phones 2       # 2 辆模拟手机车（car-6、car-7）
python3 tools/mock_devices.py --host 192.168.1.20 --cars 5
scripts/phone_sim.sh                                    # 用手机 App 的核心代码模拟"拿着手机走路驾驶 car-6"
```

## 5. 现场网络部署

```
            ┌──────────── 无线路由器（自带，关闭 AP 隔离）────────────┐
            │  192.168.1.1                                            │
   演示笔记本 192.168.1.20      安卓手机 192.168.1.30…      Gazebo 工作站 192.168.1.40
   服务端 :8080 :9000 :9001     App → 192.168.1.20         桥接 → 192.168.1.20:9000
   MATLAB 车队 → 127.0.0.1
   浏览器 → localhost:8080
```

1. **用自己的路由器**。校园网普遍开启终端隔离，设备之间互相连不上。路由器里关闭"AP 隔离"，
   在 DHCP 设置里给演示笔记本绑定固定 IP（例如 192.168.1.20），手机 App 里填这个地址。没有路由器时用手机开热点，让电脑连上。
2. **放行防火墙**。Windows 第一次运行 Java 时会弹出提示，"专用网络"和"公用网络"都勾选允许。也可以用管理员命令行：

   ```bat
   netsh advfirewall firewall add rule name="MiniDiDi HTTP" dir=in action=allow protocol=TCP localport=8080
   netsh advfirewall firewall add rule name="MiniDiDi TCP"  dir=in action=allow protocol=TCP localport=9000
   netsh advfirewall firewall add rule name="MiniDiDi UDP"  dir=in action=allow protocol=UDP localport=9001
   ```

3. **验证**：手机浏览器打开 `http://192.168.1.20:8080`，能看到地图就说明网络通了。
4. 端口被占用或想改端口：修改 `server/config/default.json` 中的 `http.port`、`devices.tcpPort`、`devices.udpPort`。

## 6. 考核当天检查单

**前一天**

- [ ] 在答辩教室（或相同网络环境）完整彩排一次：一键启动 → 运行 10 分钟 → 检查碰撞计数全为 0
- [ ] 手机充满电；App 已安装最新版本，设置里"自动连接"已打开
- [ ] 准备备份：备用笔记本（已装好 JDK、Python）、演示录像、U 盘里的源码与 APK

**开始前 15 分钟**

- [ ] 接好路由器，笔记本连上，确认 IP 与 App 中填写的一致
- [ ] 笔记本插电源，关闭睡眠和自动更新，关闭不需要的程序
- [ ] 运行 `start_demo`，确认：网页"外部接入"为 5 以上，车辆列表 car-1 … car-5 驱动为 Matlab
- [ ] 打开手机 App，确认顶栏"已接入，驾驶 car-6"、网页上 car-6 的驱动为"手机"；演示的人做一次步长标定
- [ ] 3D 视图在第二块屏幕（或投影）上自动巡游

**演示中**：不碰电脑。需要讲解时只看、只指，不点。

**出问题时**：设备断线会被自动接管，系统不会停；如果服务端本身出问题，关掉所有窗口重新 `start_demo` 即可（约 30 秒恢复）。

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 启动时报"端口被占用" | 已经有一个服务端在运行，关掉它；或在配置中修改端口 |
| 手机 / 另一台电脑连不上 | 是否在同一网段；防火墙是否放行；路由器是否开启了 AP 隔离；换用自己的路由器或手机热点 |
| 网页显示"连接中断" | 服务端没在运行，或地址不对 |
| 控制台中文乱码 | Windows 下先执行 `chcp 65001`；Linux 下 `export LC_ALL=C.UTF-8` |
| 3D 视图黑屏 | 浏览器不支持 WebGL：更新显卡驱动，或在浏览器设置里打开"使用硬件加速" |
| Matlab 车不动 | 看服务端事件日志：设备是否已"接管"数字车、是否收到 permit；先跑 `test_simcar_offline` |
| 手机上的人不动 | App 必须留在前台；检查是否在计步（步数是否增加）；室内请关闭"室外模式" |
| 修改地图后启动报"死路" | 车道格沿箭头方向必须能继续行驶，按报错的坐标检查地图 |

## 8. 版本发布

每次中期、期末提交前在 `main` 分支打标签，作为版本发布记录（变更内容写在 `CHANGELOG.md`）：

```bash
git tag -a v0.8-midterm -m "中期检查版本"
git push origin v0.8-midterm
```

提交给老师的源码附件用 `git archive` 生成，不包含编译产物和日志：

```bash
git archive --format=zip -o MiniDiDi-src.zip HEAD
```
