package com.runway.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 双时间域时间戳。relMs 是相对录制起点的毫秒数，回放调度以此为准。 */
data class TimeStamp(
    val utcMs: Long,
    val elapsedNs: Long,
    val relMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("utc_ms", utcMs)
        .put("elapsed_ns", elapsedNs)
        .put("rel_ms", relMs)
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val rom: String,
)

data class AppInfo(
    val version: String,
    val build: String,
)

data class RecordingMeta(
    val startedAt: String,
    val endedAt: String,
    val durationMs: Long,
    val distanceM: Double,
)

data class EnvironmentInfo(
    val weather: String = "",
    val surface: String = "track",
    val wearPosition: String = "hand",
    val satelliteNote: String = "",
)

data class UsageInfo(
    val maxUses: Int = 3,
    val usedCount: Int = 0,
    val lastUsedAt: String = "",
)

/** 素材清单，对应 docs/DATA_SPEC.md 第 2 节。 */
data class SessionManifest(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val createdAt: String,
    val device: DeviceInfo,
    val app: AppInfo,
    val recording: RecordingMeta,
    val counts: Map<String, Int>,
    val environment: EnvironmentInfo = EnvironmentInfo(),
    val usage: UsageInfo = UsageInfo(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("session_id", sessionId)
        .put("created_at", createdAt)
        .put("device", JSONObject()
            .put("manufacturer", device.manufacturer)
            .put("model", device.model)
            .put("sdk", device.sdk)
            .put("rom", device.rom))
        .put("app", JSONObject()
            .put("version", app.version)
            .put("build", app.build))
        .put("recording", JSONObject()
            .put("started_at", recording.startedAt)
            .put("ended_at", recording.endedAt)
            .put("duration_ms", recording.durationMs)
            .put("distance_m", recording.distanceM))
        .put("counts", JSONObject(counts as Map<*, *>))
        .put("environment", JSONObject()
            .put("weather", environment.weather)
            .put("surface", environment.surface)
            .put("wear_position", environment.wearPosition)
            .put("satellite_note", environment.satelliteNote))
        .put("usage", JSONObject()
            .put("max_uses", usage.maxUses)
            .put("used_count", usage.usedCount)
            .put("last_used_at", usage.lastUsedAt))

    companion object {
        fun fromJson(json: JSONObject): SessionManifest {
            val device = json.getJSONObject("device")
            val app = json.getJSONObject("app")
            val recording = json.getJSONObject("recording")
            val environment = json.optJSONObject("environment") ?: JSONObject()
            val usage = json.optJSONObject("usage") ?: JSONObject()
            val countsJson = json.optJSONObject("counts") ?: JSONObject()
            val counts = countsJson.keys().asSequence()
                .associateWith { countsJson.optInt(it) }
            return SessionManifest(
                schemaVersion = json.optInt("schema_version", 1),
                sessionId = json.getString("session_id"),
                createdAt = json.getString("created_at"),
                device = DeviceInfo(
                    manufacturer = device.optString("manufacturer"),
                    model = device.optString("model"),
                    sdk = device.optInt("sdk"),
                    rom = device.optString("rom"),
                ),
                app = AppInfo(
                    version = app.optString("version"),
                    build = app.optString("build"),
                ),
                recording = RecordingMeta(
                    startedAt = recording.optString("started_at"),
                    endedAt = recording.optString("ended_at"),
                    durationMs = recording.optLong("duration_ms"),
                    distanceM = recording.optDouble("distance_m", 0.0),
                ),
                counts = counts,
                environment = EnvironmentInfo(
                    weather = environment.optString("weather"),
                    surface = environment.optString("surface", "track"),
                    wearPosition = environment.optString("wear_position", "hand"),
                    satelliteNote = environment.optString("satellite_note"),
                ),
                usage = UsageInfo(
                    maxUses = usage.optInt("max_uses", 3),
                    usedCount = usage.optInt("used_count", 0),
                    lastUsedAt = usage.optString("last_used_at"),
                ),
            )
        }
    }
}

/** 事件构造器，字段定义见 docs/DATA_SPEC.md 第 3 节。 */
object Events {

    const val TYPE_LOCATION = "location"
    const val TYPE_IMU = "imu"
    const val TYPE_STEP = "step"
    const val TYPE_ACTIVITY = "activity"
    const val TYPE_BARO = "baro"
    const val TYPE_DEVICE = "device"
    const val TYPE_MARKER = "marker"

    fun location(
        t: TimeStamp,
        lat: Double,
        lon: Double,
        alt: Double,
        accuracyH: Float,
        accuracyV: Float,
        speed: Float,
        bearing: Float,
        provider: String,
        satellites: Int,
        snrAvg: Float,
        hdop: Float,
        isMock: Boolean,
    ): JSONObject = JSONObject()
        .put("t", t.toJson())
        .put("type", TYPE_LOCATION)
        .put("lat", lat)
        .put("lon", lon)
        .put("alt", alt)
        .put("acc_h", accuracyH)
        .put("acc_v", accuracyV)
        .put("speed", speed)
        .put("bearing", bearing)
        .put("provider", provider)
        .put("satellites", satellites)
        .put("snr_avg", snrAvg)
        .put("hdop", hdop)
        .put("is_mock", isMock)

    fun imu(t: TimeStamp, sensor: String, values: FloatArray, accuracy: Int): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_IMU)
            .put("sensor", sensor)
            .put("values", JSONArray(values.toList()))
            .put("accuracy", accuracy)

    fun step(t: TimeStamp, counter: Long, delta: Long, detector: Boolean): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_STEP)
            .put("counter", counter)
            .put("delta", delta)
            .put("detector", detector)

    fun activity(t: TimeStamp, activity: String, confidence: Int): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_ACTIVITY)
            .put("activity", activity)
            .put("confidence", confidence)

    fun baro(t: TimeStamp, pressureHpa: Float, altitudeM: Float): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_BARO)
            .put("pressure_hpa", pressureHpa)
            .put("altitude_m", altitudeM)

    fun device(t: TimeStamp, batteryPct: Int, charging: Boolean, screenOn: Boolean, network: String): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_DEVICE)
            .put("battery_pct", batteryPct)
            .put("charging", charging)
            .put("screen_on", screenOn)
            .put("network", network)

    fun marker(t: TimeStamp, name: String, note: String = ""): JSONObject =
        JSONObject()
            .put("t", t.toJson())
            .put("type", TYPE_MARKER)
            .put("name", name)
            .put("note", note)
}

object Ids {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun newSessionId(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = formatter.format(Instant.ofEpochMilli(nowMs))
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        return "$stamp-$suffix"
    }

    fun isoUtc(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
}
