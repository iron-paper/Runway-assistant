# 架构设计

## 1. 目标与边界

跑道助手由两部分组成：

- **录制端**：只读系统数据，把一次跑步完整落盘为素材；
- **回放端**：把素材按时间轴重新注入系统，让目标客户端“看到”这次跑步。

两者共享同一套数据模型，但**运行期严格解耦**：录制结束、素材校验通过后，回放端不再访问录制端。

## 2. 模块划分

```
app/
├── MainActivity.kt Compose 界面：首页、录制、回放、关于
├── recorder/      RecorderService（前台）、SessionRecorder、各源采集器
├── replay/        ReplayEngine 接口、Mock/Privileged 实现、ReplayScheduler、状态机
└── data/          模型、RecordingStore、SessionManifest、素材库
native/            注入层：JNI 入口，向上提供 start/pause/stop/seek
tools/             离线校验与打包
```

### 2.1 录制端

| 组件 | 职责 |
|---|---|
| `RecorderService` | 前台服务，持有 WakeLock，维持采集生命周期 |
| `SessionRecorder` | 统一注册/注销所有数据源，负责写入 JSONL |
| `LocationSource` | `LocationManager` 定位回调，记录原始字段 |
| `SensorSource` | 加速度、陀螺、磁力、气压、计步累计与步事件 |
| `ActivitySource` | `ActivityRecognition` 状态与置信度 |
| `DeviceSource` | 电量、亮屏、充电、网络类型（低频采样） |
| `MarkerWriter` | 写入开始、暂停、继续、结束等会话标记 |

写入策略：每 200 条或每 2 秒 flush 一次；崩溃后从最后一个完整行恢复。

### 2.2 回放端

```
SessionController
   │ 选择素材 + 接入偏移
   ▼
ReplayScheduler ── 前奏(可循环) ──► 正片(1:1)
   │                                    │
   │  状态机：IDLE → READY → PLAYING → PAUSED → ENDED
   ▼
ReplayEngine（接口）
   ├── MockReplayEngine        调试用，Mock Provider，带标记
   └── PrivilegedReplayEngine  正式用，走 native 注入层
```

`ReplayScheduler` 只负责“什么时候送哪一帧”，不关心送到哪里；`ReplayEngine` 只负责“怎么送”，不关心节奏。这样替换注入层时调度逻辑不动。

### 2.3 注入层

`native/` 暴露 6 个入口：

| 方法 | 作用 |
|---|---|
| `nativeInit(config)` | 建立与注入服务的连接，返回句柄 |
| `nativeStart(handle, path, joinOffsetMs)` | 从素材的指定偏移开始回放 |
| `nativePause(handle)` | 冻结输出，保持最后状态 |
| `nativeResume(handle)` | 从暂停点继续 |
| `nativeStop(handle)` | 停止并清理 |
| `nativeStatus(handle)` | 返回注入状态与错误码 |

注入层内部按目标分层实现：GNSS HAL、Sensor HAL、ActivityRecognition 各自独立，互不阻塞；任一通道失败时上报具体错误码，由 App 决定是否中止会话。

## 3. 时间模型

- **录制**：每个事件同时写 `utc_ms` 与 `elapsed_ns`（开机纳秒）。
- **回放**：不复制录制时的绝对时间戳，而是以“本次会话开始”为原点，按素材内的相对偏移重建时间戳，写入当前开机时基。
- **调度**：以 `elapsedRealtimeNanos` 驱动，使用单调时钟，避免系统时间调整影响。

```
素材时间轴:  0 ────── join ──────────────── end
               前奏(循环)   正片(1:1)
本次会话:     t0 ──► t0+Δ ──► t0+Δ+L
```

## 4. 会话状态机

| 状态 | 进入条件 | 允许动作 |
|---|---|---|
| `IDLE` | 无素材或未选择 | 选择素材、校验、录制 |
| `READY` | 注入层就绪、素材校验通过 | 开始（需指定接入偏移） |
| `PLAYING` | 已开始 | 暂停、停止 |
| `PAUSED` | 用户暂停 | 继续、停止 |
| `ENDED` | 手动结束或素材播完 | 复位、导出记录 |

结束语义：**先暂停注入，再结束会话**；结束后注入器停在最后位置，等待复位。

## 5. 数据流

```
系统源 ──► SessionRecorder ──► JSONL ──► validate ──► 素材库
                                                   │
素材库 ──► SessionController ──► ReplayScheduler ──► ReplayEngine ──► 注入层
                                                   │
                                                   └──► 会话记录（CSV/JSON）
```

## 6. 错误处理

| 错误 | 处理 |
|---|---|
| 注入层未就绪 | 禁止开始，提示安装/启用注入层 |
| 素材校验失败 | 禁止开始，列出失败项 |
| 回放中通道断开 | 立即暂停，记录错误码，允许重试一次 |
| 接入延迟超预算 | 提示“剩余里程不足”，要求复位重来 |
| 连续 2 次会话异常 | 按 S9 停止，回滚到上一个通过验收的配置 |

## 7. 安全与隐私

- 素材默认存于应用私有目录，加密密钥由 Keystore 管理；
- 导出素材需二次确认；
- 日志默认不含坐标明文，调试模式需手动开启；
- 会话记录只保留统计字段，不保留原始轨迹。

## 8. 扩展点

- 新增数据源：实现 `Source` 接口并注册到 `SessionRecorder`；
- 新增注入后端：实现 `ReplayEngine` 并注册工厂；
- 新增校验规则：在 `tools/validate_recording.py` 增加规则函数；
- 新增导出格式：实现 `Exporter` 接口。
