# 跑道助手 1.0（Runway Assistant）

> 运动数据记录与复盘工具。完整记录一次真实跑步的多源数据，并在受控环境中按原始时间轴回放，用于运动数据研究、轨迹复盘与客户端兼容性测试。

![status](https://img.shields.io/badge/status-alpha-orange)
![platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![kotlin](https://img.shields.io/badge/Kotlin-1.9-blue)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 简介

跑道助手把一次真实跑步拆成“可记录、可校验、可回放”的数据资产：

1. **记录**：GNSS、IMU、计步、活动识别、气压、设备状态，统一写入带双时间域的时间轴；
2. **校验**：离线检查时间单调性、步频区间、速度区间、轨迹连续性；
3. **回放**：前奏可循环、正片严格 1:1，按会话接入点播放；
4. **复盘**：输出每次会话的记录表，用于判断数据是否仍然可用。

项目当前处于 **alpha** 阶段：录制端与控制端骨架已完成，特权注入层（HAL / 内核模块）以接口与占位实现接入，等待设备侧联调。

---

## 核心能力

| 能力 | 说明 | 状态 |
|---|---|---|
| 多源录制 | 位置 1Hz、IMU 50–100Hz、计步累计+步事件、活动识别、气压、屏幕/电量 | 骨架完成 |
| 双时间域 | UTC 与 `elapsedRealtimeNanos` 同时落盘，回放时重建为本次开机时基 | 骨架完成 |
| 断点续录 | 进程被杀后从最近检查点恢复 | 规划中 |
| 回放调度 | 前奏循环 + 正片 1:1 + 暂停/继续状态机 | 骨架完成 |
| 会话管理 | 接入延迟预算、结束暂停、复位到起点 | 骨架完成 |
| 素材管理 | 单素材使用次数、轮换与退役记录 | 骨架完成 |
| 注入层 | Location / Sensor / ActivityRecognition 的 HAL 级注入 | 接口就绪，待实现 |
| 内置说明 | “设置 → 关于 → 数据说明”展示数据来源与使用限制 | 完成 |

---

## 系统架构

```
┌──────────────────────────── 跑道助手 App ────────────────────────────┐
│  UI(Compose)  │  SessionController  │  RecordingStore  │  ReplayClock │
└───────┬───────────────┬──────────────────────┬───────────────┬───────┘
        │               │                      │               │
   ┌────▼────┐    ┌─────▼──────┐        ┌──────▼──────┐  ┌─────▼─────┐
   │ Recorder│    │ Session    │        │ 素材库      │  │ Replay    │
   │ Service │    │ Manifest   │        │ (JSONL)     │  │ Scheduler │
   └────┬────┘    └────────────┘        └─────────────┘  └─────┬─────┘
        │                                                       │
   Android Framework                                   ReplayEngine (接口)
   LocationManager / SensorManager / ActivityRecognition        │
                                                    ┌───────────┴───────────┐
                                                    │ MockReplayEngine      │
                                                    │ PrivilegedReplayEngine│──► native 注入层
                                                    └───────────────────────┘
```

- **录制端**只读系统数据，不做任何写入；
- **回放端**通过 `ReplayEngine` 抽象，开发调试用 Mock 实现，正式环境用特权实现；
- **注入层**独立成 `native/` 模块，与 App 通过 JNI 通信，便于单独替换与验收。

---

## 目录结构

```
跑道助手1.0/
├── app/                          # Android 应用（录制端 + 控制端 + UI）
│   └── src/main/java/com/runway/assistant/
│       ├── recorder/             # 前台录制服务与传感器采集
│       ├── replay/               # 回放引擎、调度器、状态机
│       ├── data/                 # 数据模型、素材库、会话清单
│       └── MainActivity.kt       # Compose 界面（首页/录制/回放/关于）
├── native/                       # 特权注入层（HAL / 内核模块接入点）
├── tools/                        # 录制校验、会话检查、示例生成脚本
├── scripts/                      # 构建辅助脚本（Windows 中文路径）
├── samples/                      # 示例素材（脱敏、可公开）
├── docs/                         # 架构、数据规范、操作手册、验收标准
├── gradle/                       # Gradle wrapper
├── .github/                      # Issue/PR 模板与 CI
├── README.md
├── CHANGELOG.md
└── LICENSE
```

---

## 环境要求

| 项 | 要求 |
|---|---|
| Android | 8.0（API 26）及以上 |
| 设备 | 已 root（特权回放需要）；建议单独的实验机 |
| JDK | 17 |
| Gradle | 8.7+（使用 wrapper） |
| Android SDK | compileSdk 34 / targetSdk 34 |
| Python（工具） | 3.8+ |

---

## 快速开始

```bash
# 1. 构建调试包
./gradlew :app:assembleDebug

# 2. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 校验一份录制素材
python tools/validate_recording.py samples/demo

# 4. 查看会话清单
python tools/inspect_session.py samples/demo
```

> Windows 下目录名含中文时，单元测试可能受 Gradle 类路径编码影响；可把仓库放到纯英文路径，或使用 `scripts/build.ps1`（见 [docs/BUILD.md](docs/BUILD.md)）。

> 首次运行需授予定位、身体传感器、活动识别、通知权限；特权回放需先安装并启用注入层（见 `native/README.md`）。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [产品使用说明书](docs/USER_MANUAL.md) | 面向使用者的完整操作手册与设计结论 |
| [架构设计](docs/ARCHITECTURE.md) | 模块划分、数据流、状态机、注入层边界 |
| [数据规范](docs/DATA_SPEC.md) | 录制字段、采样率、时间域、坐标系、文件格式 |
| [会话操作手册](docs/SESSION_SOP.md) | 启动顺序、接入延迟、结束与复位 |
| [验收标准](docs/ACCEPTANCE.md) | DoD、指标定义、记录表模板 |
| [关键决策](docs/DECISIONS.md) | K1–K5 否决项与 S1–S10 标准的结论存档 |
| [路线图](docs/ROADMAP.md) | 里程碑与任务拆解 |
| [FAQ](docs/FAQ.md) | 常见问题与故障排查 |
| [构建与验证](docs/BUILD.md) | 环境、命令、Windows 中文路径说明、验证记录 |
| [目标客户端调研](docs/research/运动世界-测量模块与签到机制调研.md) | 测量模块、签到机制与反作弊面的事实基础 |

---

## 设计约束（摘要）

| 编号 | 结论 |
|---|---|
| K1 | 目标客户端允许“移动中开始”，采用“中途接入”架构 |
| K2 | 设备已 root，环境检测通过 |
| K3 | 注入层覆盖位置、IMU、计步、活动识别全链路 |
| K4 | 开阔场地（操场）下网络定位不拉回轨迹 |
| K5 | 目标版本锁定，素材与标定可复用 |
| S1 | 单素材策略：一份录制、单次回放为主，使用次数 ≤3 |
| S2 | 双时间域 + 坐标系按注入层写死 |
| S4 | 助手先启动、接入延迟 ≤5 分钟、结束即暂停、每会话复位 |
| S9 | 连续 2 次异常即停，回滚到上一个通过验收的配置 |

完整结论见 [docs/DECISIONS.md](docs/DECISIONS.md)。

---

## 已知限制

- 回放采用“中途接入”，目标客户端从素材的**某一秒**开始消费，接入点随机；剩余里程需覆盖目标里程。
- 单素材重复使用会形成跨场次指纹，使用次数与节奏是主要风险控制手段。
- 注入层依赖设备与系统版本，更换设备或系统需重新标定。
- 目标客户端版本锁定后，素材与阈值可复用；升级需整体回归。

---

## 路线图

见 [docs/ROADMAP.md](docs/ROADMAP.md)。当前里程碑：**M1 录制闭环** → **M2 回放闭环** → **M3 注入层联调** → **M4 验收通过**。

---

## 贡献

欢迎提交 Issue 与 PR，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 安全

漏洞与敏感问题请按 [SECURITY.md](SECURITY.md) 的方式私下反馈。

## 许可证

[MIT](LICENSE)

## 免责声明

本项目用于个人学习、运动数据研究与客户端兼容性测试。使用者应自行确认使用场景与当地规定，并对使用行为及后果负责。
