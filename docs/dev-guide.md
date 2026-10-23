# 开发指南

## 1. 环境

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 17 或更新（推荐 21） | 服务端。只需要 JDK，不需要 Maven |
| Python | 3.8 或更新 | 模拟设备、地图生成（只用标准库） |
| MATLAB | R2020b 或更新 | Matlab 模拟车，不需要工具箱 |
| Android Studio | 2024.2 或更新（自带 JDK） | 手机 App：打开 `phone/` 文件夹。手机需安卓 8.0 以上 |
| 浏览器 | Chrome / Edge / Firefox 新版 | 网页前端 |
| IntelliJ IDEA（可选） | 社区版即可 | 打开 `server/pom.xml` 导入工程 |

检查 JDK：命令行输入 `java -version` 和 `javac -version`，版本应为 17 以上。

## 2. 常用命令

在仓库根目录执行：

| 做什么 | Windows | Linux / macOS |
|---|---|---|
| 编译服务端 | `scripts\build.bat` | `scripts/build.sh` |
| 启动服务端 | `scripts\start_server.bat` | `scripts/start_server.sh` |
| 一键演示（服务端 + 5 辆模拟车 + 1 部模拟手机） | `scripts\start_demo.bat` | `scripts/start_demo.sh` |
| 离线对比实验 | `scripts\experiment.bat --minutes 30 --seeds 1,2,3` | `scripts/experiment.sh --minutes 30 --seeds 1,2,3` |
| 单元测试 | `cd server` 后 `mvn test` | 同左 |
| 手机 App 单元测试 | `cd phone` 后 `gradlew.bat test` | `cd phone && ./gradlew test` |
| 手机端到端仿真（先启动服务端） | `scripts\phone_sim.bat` | `scripts/phone_sim.sh` |
| 模拟设备 | `python tools\mock_devices.py --cars 5` | `python3 tools/mock_devices.py --cars 5` |
| 生成地图 | `python tools\gen_map.py -o server\maps\my.json` | `python3 tools/gen_map.py -o server/maps/my.json` |

服务端启动后，浏览器打开 http://localhost:8080 。同一局域网的其他电脑、手机使用控制台打印的"局域网地址"。

**IntelliJ IDEA**：File → Open 选择 `server/pom.xml`，以工程方式打开。运行 `com.zongc.dw.DwServer`，工作目录设为 `server`。单元测试在 `server/src/test/java` 下，右键运行即可。

国内下载 Maven 依赖慢时，在 `~/.m2/settings.xml` 中配置阿里云镜像：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

## 3. 目录结构

```
ZongC/
├── server/                 数字世界服务端（Java）
│   ├── src/main/java/com/zongc/dw/   源代码（各包职责见 docs/architecture.md）
│   ├── src/test/java/                单元测试（JUnit 4）
│   ├── config/default.json           运行配置：车数、到达率、派单策略、端口……
│   ├── maps/city-5x4.json            地图
│   ├── lib/gson-2.13.1.jar           唯一的第三方依赖（随仓库提供，离线也能编译）
│   └── pom.xml                       Maven / IDEA 工程文件
├── web/                    网页前端（原生 HTML/CSS/JS，无需构建）
├── matlab/                 Matlab 模拟车
├── phone/                  安卓手机 App（Android Studio 打开此文件夹，说明见 phone/README.md）
│   ├── app/src/main/java/com/zongc/phone/        界面、传感器、卫星定位
│   ├── app/src/main/java/com/zongc/phone/core/   纯 Java 核心：位姿估计、网络（可在电脑上测试）
│   ├── app/src/test/java/                        单元测试、传感器模拟器、端到端仿真 PhoneSim
│   └── analysis/plot_phone_log.m                 MATLAB 分析手机记录的数据
├── tools/                  模拟设备、地图生成器
├── scripts/                编译、启动、演示、实验脚本
└── docs/                   架构、协议、计划、开发指南
```

## 4. 配置

`server/config/default.json` 的主要字段：

| 字段 | 默认值 | 说明 |
|---|---|---|
| taxis | 8 | 数字车数量（PPT 要求至少 5） |
| tasks.ratePerMin | 6 | 订单到达率（单/分钟），界面上也可以调 |
| dispatch.strategy | HUNGARIAN | 派单策略：GREEDY / HUNGARIAN |
| timeScale | 1.0 | 仿真倍速；接入实时设备时必须为 1 |
| seed | 20261023 | 随机种子，相同种子 + 相同配置可复现 |
| vehicle.* | | 车身半径、最高速度、加减速度、转弯限速 |
| traffic.* | | 授权前视格数、转弯代价、优先通行与绕行时间 |
| devices.* | | 端口、心跳超时、广播频率、最多出租车数 |

可以复制一份配置做实验，启动时用 `--config` 指定，例如 `scripts/start_server.sh --config server/config/demo.json`。

## 5. 代码约定

- 标识符用英文，注释和文档用中文。每个类开头写清楚"它负责什么"。
- `World` 及其下属对象只在仿真线程中访问，网络和 HTTP 线程通过 `SimulationEngine.submit/call` 提交操作，不要直接改世界状态。
- 新增功能尽量放在 `world`、`plan`、`traffic`、`dispatch` 这些不依赖网络的包里，并配上单元测试。
- 修改协议时同步更新 `docs/protocol.md` 和 `tools/mock_devices.py`。

## 6. Git 协作流程

1. `main` 分支始终保持"能编译、测试通过、能演示"。
2. 每个功能新建分支，例如 `feature/matlab-mpc`、`feature/phone-app`、`fix/xxx`。
3. 完成后发起 Pull Request，至少一位组员审查后再合并。合并前在本地跑一遍 `mvn test` 和一键演示。
4. 提交信息写清楚做了什么，例如"Matlab 模拟车：速度环加入积分项，减小稳态误差"。
5. 每次中期、期末提交前打标签（tag），例如 `v0.8-midterm`，作为"版本发布记录"。

课程要求用 gitee 私有库：可以在 gitee 上用"从 GitHub/GitLab 导入仓库"做镜像，或者本地同时添加两个远程仓库，一起推送。

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 启动时报"端口被占用" | 已经有一个服务端在运行，关掉它；或在配置中修改端口 |
| 另一台电脑 / 手机连不上 | 检查是否在同一局域网；Windows 防火墙放行 Java 或 8080、9000、9001 端口；校园网可能开启了终端隔离，换用自己的路由器或手机热点 |
| 网页一直显示"连接中断" | 服务端没在运行，或浏览器地址不对 |
| 控制台中文乱码 | Windows 下执行 `chcp 65001` 后再启动，或在 IDEA 中运行 |
| 模拟车不动 | 检查服务端事件日志：设备是否已"接管"数字车；是否收到 permit |
| 地图修改后启动报"死路" | 车道格沿箭头方向必须能继续行驶，按报错的坐标检查地图 |
