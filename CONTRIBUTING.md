# 贡献指南

感谢参与跑道助手。提交代码前请阅读以下约定。

## 开发环境

- JDK 17
- Android Studio Hedgehog 及以上
- Android SDK 34
- Python 3.8+（`tools/` 下的脚本）

## 分支模型

| 分支 | 用途 |
|---|---|
| `main` | 可发布、通过验收的版本 |
| `feat/<topic>` | 新功能 |
| `fix/<topic>` | 缺陷修复 |
| `docs/<topic>` | 文档更新 |

提交信息遵循 Conventional Commits：`feat(recorder): 增加气压采样`、`fix(replay): 修正暂停后时间轴偏移`。

## 代码规范

- Kotlin 使用官方风格，4 空格缩进，禁止通配符导入；
- 所有对外 API 写 KDoc；
- 传感器与定位回调统一走 `SessionRecorder`，禁止在 UI 层直接注册监听；
- 时间戳一律使用双时间域（UTC + `elapsedRealtimeNanos`），禁止混用；
- 新增字段必须同步更新 `docs/DATA_SPEC.md` 与 `tools/validate_recording.py`。

## 提交前检查

```bash
./gradlew :app:testDebugUnitTest
python tools/validate_recording.py samples/demo/recording.jsonl
```

## Pull Request

- 一个 PR 只做一件事；
- 描述中说明：改了什么、为什么改、如何验证；
- 涉及录制格式或回放时序的变更，必须在 PR 中附验收记录。

## Issue

使用模板提交，附上：设备型号、系统版本、App 版本、复现步骤、日志片段（脱敏）。
