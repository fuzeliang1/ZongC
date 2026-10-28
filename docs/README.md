# 文档索引

课程要求文档覆盖"立项 → 需求 → 设计 → 测试 → 部署 → 效果评估"全流程。本目录按 GB/T 8567《计算机软件文档编制规范》的阶段组织，
最终的技术报告就是这些文档的汇编。

新组员先读 **[上手指南](onboarding.md)**：仓库里有什么、课程要求完成情况、怎么跑起来、每个人的分工和第一批任务。

## 按阶段

| 阶段 | 文档 | 内容 |
|---|---|---|
| 立项 | [立项申请书](proposal.md) | 背景、目标（必做 / 挑战）、技术方案、可行性、分工、16 周甘特图、风险与对策 |
| 计划 | [项目计划](plan.md) | 进度表、分工建议、挑战项选择、现场演示要点、向老师确认的问题（已结清） |
| 需求 | [需求规格说明](requirements.md) | 参与者、用例图与用例描述、功能需求（FR）、非功能需求（NFR）、验收标准、需求追踪矩阵 |
| 设计 | [软件设计说明](design.md) | 组件图、部署图、类图、时序图、状态图、活动图、数据设计、关键设计决策 |
| 设计 | [架构设计说明](architecture.md) | 算法细节：交通管控、规划、派单、感知建图、场景脚本、空车调度、图像识别、3D |
| 设计 | [接口协议说明](protocol.md) | 设备接入（TCP / UDP）、浏览器接口（REST / SSE）、地图与场景脚本格式 |
| 实现 | [开发指南](dev-guide.md) | 开发环境、常用命令、目录结构、配置、代码约定、Git 协作 |
| 测试 | [测试计划与测试报告](test-report.md) | 测试层次、94 项单元测试清单、系统集成测试、端到端测试、缺陷记录、已知限制 |
| 部署 | [部署与使用手册](deployment.md) | 软硬件要求、一键演示、各部分单独启动、现场网络、考核当天检查单、常见问题 |
| 评估 | [效果评估报告](evaluation.md) | 指标、实验方法、派单 / 空车调度 / 感知 / 安全 / 图像处理 / 位姿估计结果与分析 |
| 评估 | [评估原始数据](eval/summary.md) | `tools/run_evaluation.py` 自动生成的汇总表和 CSV |
| 发布 | [版本记录](../CHANGELOG.md) | 每个版本新增、修复了什么 |

各模块自己的说明：[手机 App](../phone/README.md)、[Matlab 模拟车](../matlab/README.md)、[Gazebo 接入](../gazebo/README.md)。

## 提交物（`deliverables/`）

| 文件 | 内容 | 重新生成 |
|---|---|---|
| `MiniDiDi_report.docx` / `.pdf` | 技术报告（40 页：立项 → 需求 → 设计 → 算法 → 实现 → 测试 → 评估 → 总结） | 改 `docs/report/report.md`，运行 `python3 tools/build_report.py --pdf`（需要 pandoc、python-docx，PDF 需要 LibreOffice） |
| `MiniDiDi_slides.pptx` | 答辩介绍 PPT：封面 + 正文 5 页 + 结尾 + 附录 4 页（问答备用），正文每页有演讲备注 | 改 `tools/build_slides.js`，`npm install pptxgenjs react-icons react react-dom sharp` 后 `NODE_PATH=…/node_modules node tools/build_slides.js` |
| `MiniDiDi_demo.mp4` | 演示录屏（4 分 41 秒，1280×720）：片头 → 2D 主界面 → 3D 巡游 → 3D 跟随手机车 car-6，全程无人操作 | `python3 tools/record_demo.py --car car-6`（需要 Node.js + Playwright、ffmpeg） |

报告和 PPT 的封面信息、报告第 8.3 节个人总结已填写；真机、MATLAB 实测后的照片和视频可以补进报告第 5、6 章。

## UML 图

源文件在 [`uml/`](uml/)（PlantUML 文本），图片在 [`images/uml/`](images/uml/)。修改源文件后运行：

```bash
python3 tools/render_uml.py            # 全部重画（只需要 JDK；第一次会自动下载 PlantUML）
python3 tools/render_uml.py seq-order  # 只重画一张
python3 tools/render_uml.py --svg      # 同时输出矢量图（放进 Word 不会糊）
```

| 图 | 文件 | 用在 |
|---|---|---|
| 用例图（总体） | `usecase` | 需求 3.1 |
| 用例图（包含与扩展） | `usecase-include` | 需求 3.1 |
| 组件图 | `component` | 立项 3、设计 2.1 |
| 部署图 | `deployment` | 设计 3 |
| 类图：数字世界领域模型 | `class-world` | 设计 4.1 |
| 类图：算法类 | `class-algo` | 设计 4.2 |
| 类图：手机 App | `class-phone` | 设计 4.3 |
| 数据流图：手机位姿估计与走路驾驶 | `pdr` | 设计 4.3 |
| 时序图：订单生命周期 | `seq-order` | 设计 5.1 |
| 时序图：设备接入与断线接管 | `seq-device` | 设计 5.2 |
| 时序图：感知未知障碍 | `seq-perception` | 设计 5.3 |
| 时序图：拍照识别（端云互动） | `seq-vision` | 设计 5.4 |
| 时序图：手机车接入与走路驾驶 | `seq-phone` | 设计 5.5 |
| 状态图：出租车 | `state-vehicle` | 设计 5.6 |
| 状态图：订单与设备连接 | `state-task` | 设计 5.6 |
| 活动图：仿真步 | `activity-step` | 设计 5.7 |
| 活动图：授权延伸 | `activity-permit` | 设计 5.7 |
| 甘特图：16 周进度 | `gantt` | 立项 7 |

画图约定：类图中 `+` 公有、`-` 私有、`~` 包内可见、`#` 受保护；实心菱形 = 组合，空心菱形 = 聚合，
虚线空心三角 = 实现接口，虚线箭头 = 依赖。所有图共用 `uml/_style.iuml` 里的样式（黑白为主，打印和投影都清楚）。

## 评估图表

`images/eval/` 下的柱状图由 `python3 tools/plot_eval.py` 从 `eval/*.csv` 生成（需要 `pip install matplotlib`），
重跑评估实验后记得重新画图。
