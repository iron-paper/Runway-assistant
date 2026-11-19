# 构建与验证

## 1. 环境

| 项 | 版本 |
|---|---|
| JDK | 17（Temurin 17.0.x） |
| Gradle | 8.7（仓库自带 wrapper） |
| Android SDK | compileSdk 34、build-tools 34.0.0 |
| Python（工具） | 3.8+ |

`local.properties` 中的 `sdk.dir` 指向本机 SDK；该文件不入库。

## 2. 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 3. 单元测试

```bash
./gradlew :app:testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

## 4. 素材校验

```bash
python tools/validate_recording.py samples/demo
python tools/inspect_session.py samples/demo
```

## 5. Windows 中文路径说明

仓库目录名含中文（`跑道助手1.0`）时：

- `assembleDebug` 可用（已在 Windows 11 + JDK 17 + Gradle 8.7 验证）；
- `testDebugUnitTest` 可能因 Gradle 测试工作进程的类路径编码问题报
  `ClassNotFoundException`。这是工具链在非 ASCII 路径下的已知限制，与代码无关。

两种处理方式：

1. **推荐**：把仓库放在纯英文路径（如 `D:\dev\runway-assistant`）再构建；
2. **临时**：使用脚本复制到临时英文目录构建，产物回填：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Target both
```

`gradle.properties` 中的 `android.overridePathCheck=true` 只关闭 AGP 的路径检查，不改变上述测试工作进程的限制。

## 6. 验证记录（2026-11-19）

| 项 | 结果 |
|---|---|
| `:app:assembleDebug`（中文路径） | 通过，APK 约 15.6 MB |
| `:app:testDebugUnitTest`（英文路径副本） | 通过，5 个用例 |
| `tools/validate_recording.py samples/demo` | 通过，800 条事件 |
| `tools/inspect_session.py samples/demo` | 通过 |
| XML / JSON 结构校验 | 通过 |

## 7. 注入层

注入层独立构建，见 [native/README.md](../native/README.md)。App 构建不依赖 NDK。
