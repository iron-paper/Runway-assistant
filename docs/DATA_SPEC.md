# 数据规范

版本：`schema_version = 1`

## 1. 文件组织

一份素材 = 一个目录：

```
<session_id>/
├── session.json      # 会话清单（元数据、统计、校验和）
├── recording.jsonl   # 逐事件数据流
└── checksum.sha256   # 对前两个文件的校验和
```

`samples/demo/` 提供了一份可直接校验的示例。

## 2. session.json

| 字段 | 类型 | 说明 |
|---|---|---|
| `schema_version` | int | 当前为 1 |
| `session_id` | string | `YYYYMMDD-HHMMSS-<6位随机>` |
| `created_at` | string | ISO-8601 UTC |
| `device` | object | `model`、`manufacturer`、`sdk`、`rom` |
| `app` | object | `version`、`build` |
| `recording` | object | `started_at`、`ended_at`、`duration_ms`、`distance_m` |
| `counts` | object | 各类型事件数量 |
| `environment` | object | `weather`、`surface`、`wear_position`、`satellite_note` |
| `validation` | object | `passed`、`errors[]` |
| `usage` | object | `max_uses`（默认 3）、`used_count`、`last_used_at` |

## 3. recording.jsonl

每行一个 JSON 事件，禁止跨行。公共字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `t.utc_ms` | int | 事件 UTC 毫秒 |
| `t.elapsed_ns` | int | 开机以来纳秒（`SystemClock.elapsedRealtimeNanos()`） |
| `t.rel_ms` | int | 相对录制起点的毫秒，回放以此为准 |
| `type` | string | 事件类型 |

### 3.1 `location`

| 字段 | 单位 | 说明 |
|---|---|---|
| `lat` / `lon` | 度 | **WGS-84 原始坐标**；如需 GCJ-02，另存 `gcj_lat`/`gcj_lon` |
| `alt` | 米 | 椭球高 |
| `acc_h` / `acc_v` | 米 | 水平/垂直精度 |
| `speed` | m/s | 设备上报速度 |
| `bearing` | 度 | 航向 |
| `provider` | string | `gps` / `network` / `fused` |
| `satellites` | int | 参与定位卫星数 |
| `snr_avg` | dB | 平均信噪比 |
| `hdop` | float | 水平精度因子 |
| `is_mock` | bool | 录制时原始值 |

### 3.2 `imu`

| 字段 | 说明 |
|---|---|
| `sensor` | `accelerometer` / `gyroscope` / `magnetometer` / `gravity` / `linear_acceleration` / `rotation_vector` |
| `values` | float 数组，单位按 Android 标准 |
| `accuracy` | 0–3 |

### 3.3 `step`

| 字段 | 说明 |
|---|---|
| `counter` | `TYPE_STEP_COUNTER` 累计值（开机以来） |
| `delta` | 相对上一条的增量 |
| `detector` | 是否同时收到 `TYPE_STEP_DETECTOR` 事件 |

### 3.4 `activity`

| 字段 | 说明 |
|---|---|
| `activity` | `still` / `walking` / `running` / `on_bicycle` / `in_vehicle` / `unknown` |
| `confidence` | 0–100 |

### 3.5 `baro`

| 字段 | 说明 |
|---|---|
| `pressure_hpa` | 百帕 |
| `altitude_m` | 由气压换算的海拔（参考值） |

### 3.6 `device`

| 字段 | 说明 |
|---|---|
| `battery_pct` | 电量 |
| `charging` | 是否充电 |
| `screen_on` | 是否亮屏 |
| `network` | `wifi` / `cellular` / `none` |

### 3.7 `marker`

| 字段 | 说明 |
|---|---|
| `name` | `record_start` / `run_start` / `pause` / `resume` / `run_end` / `record_end` |
| `note` | 可选说明 |

## 4. 坐标系约定

- 落盘统一为 **WGS-84**；
- 回放时按注入层决定输出坐标：
  - 系统层注入 → 输出 WGS-84，由地图 SDK 自行转换；
  - SDK 层注入 → 输出 GCJ-02，使用录制时导出的 `gcj_*` 字段。
- 同一份素材在两种注入层之间切换时，必须重新校验，避免二次转换偏移。

## 5. 校验规则

`tools/validate_recording.py` 检查：

| 规则 | 阈值 |
|---|---|
| 时间单调 | `rel_ms` 严格递增 |
| 定位连续性 | 相邻点速度 ≤ 6 m/s（可配置） |
| 步频区间 | 40–300 步/分钟 |
| 配速区间 | 2–10 分钟/公里 |
| 步数一致 | 累计增量之和 = 末值 − 初值 |
| 字段完整 | 必填字段齐全 |
| 轨迹闭合（可选） | 起点终点距离 ≤ 50m（闭环素材） |

## 6. 版本兼容

- 新增字段：只增不改，旧解析器忽略未知字段；
- 修改语义：提升 `schema_version`，并提供迁移脚本；
- 删除字段：至少保留两个 minor 版本。
