# 变更记录

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- 项目骨架：Android 应用、注入层接口、工具脚本、文档体系。
- 录制端：前台服务、位置/IMU/计步/活动识别/气压采集、双时间域落盘。
- 回放端：`ReplayEngine` 抽象、Mock 实现、特权实现占位、前奏+正片调度器。
- 会话管理：接入延迟预算、结束暂停、复位。
- 素材管理：单素材使用次数与轮换记录。
- 工具：`validate_recording.py`、`inspect_session.py`。
- 文档：产品使用说明书、架构、数据规范、会话手册、验收标准、决策记录。

### 待办

- 注入层 HAL 实现与联调。
- 断点续录与素材加密存储。
- 跨设备标定工具。

### 验证

- `:app:assembleDebug` 通过（Windows 11 + JDK 17 + Gradle 8.7），APK 约 15.6 MB。
- `:app:testDebugUnitTest` 通过（5 个用例，ASCII 路径验证）。
- `tools/validate_recording.py samples/demo` 通过（800 条事件）。

## [0.1.0] - 2026-11-19

- 初始设计定稿：K1–K5 否决项结论、S1–S10 标准。
