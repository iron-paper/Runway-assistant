# 注入层（native）

注入层负责把素材事件落到目标客户端真正读取的系统通道。当前仓库只提供 **JNI 契约与骨架**，具体实现由设备侧模块完成。

## 1. 职责边界

| 通道 | Android 层 | 注入层职责 |
|---|---|---|
| 定位 | `gnss` HAL → `LocationManager` | 回放经纬度、海拔、精度、速度、航向、卫星状态与 NMEA |
| 传感器 | `sensors` HAL → `SensorManager` | 回放加速度、陀螺、磁力、气压、计步累计值与步事件 |
| 活动识别 | `ActivityRecognition` | 回放步行/跑步/静止状态与置信度 |

原则：

- 注入必须发生在 **framework 以下**，App 侧不出现 mock 标记；
- 时间戳使用当前开机时基重建，禁止透传录制时的绝对时间；
- 计步累计值需按“当前真实基线 + 素材增量”重建；
- 任一通道失败立即返回错误码，由 App 决定是否中止会话。

## 2. JNI 契约

```cpp
long nativeInit(String config);                       // 返回非零句柄
int  nativeStart(long handle, String path, long joinOffsetMs);
int  nativeEmit(long handle, String eventJson);
int  nativePause(long handle);
int  nativeResume(long handle);
int  nativeStop(long handle);
String nativeStatus(long handle);
```

错误码：

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| -1 | 参数错误 |
| -2 | 未实现（当前骨架） |
| -3 | 通道未就绪 |
| -4 | 素材读取失败 |
| -5 | 注入服务断开 |

## 3. 构建

注入层独立于 App 构建，避免 App 构建链路依赖 NDK：

```bash
# 需要 Android NDK（r26+）与 CMake 3.22+
cmake -B native/build \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-26
cmake --build native/build

# 生成的 librunway.so 由设备侧模块加载；App 侧通过 JNI 名称绑定
```

正式实现建议拆为独立模块（Magisk 模块 / 内核模块 / 自定义 ROM 的一部分），App 只保留 JNI 接口。

## 4. 实现路线

1. **Mock 阶段**：`MockReplayEngine` 验证 UI 与会话流程；
2. **HAL 阶段**：替换 GNSS 与 Sensor HAL，先只回放位置；
3. **全链路**：补齐 IMU、计步、活动识别；
4. **标定**：对齐精度、卫星状态、噪声分布；
5. **验收**：按 `docs/ACCEPTANCE.md` 走 M3。

## 5. 注意事项

- 不同 ROM 的 HAL 接口版本不同，需按设备适配；
- 计步与传感器在部分厂商 ROM 上由独立服务提供，注入点要覆盖；
- 注入层升级后必须重新跑验收，素材与参数可能失效。
