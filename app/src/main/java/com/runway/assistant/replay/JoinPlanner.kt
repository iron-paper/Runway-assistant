package com.runway.assistant.replay

import com.runway.assistant.data.Events
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 轨迹点（纯数据，便于在 JVM 单测中复用）。 */
data class TrackPoint(
    val relMs: Long,
    val lat: Double,
    val lon: Double,
)

/** 接入规划结果。 */
data class JoinPlan(
    val offsetMs: Long,
    val remainingMeters: Double,
)

/**
 * 接入点规划：保证接入后剩余里程仍能覆盖目标里程。
 *
 * 规则见 docs/SESSION_SOP.md 2.2：剩余里程 ≥ 目标里程 + 余量。
 */
object JoinPlanner {

    const val DEFAULT_MARGIN_M = 300.0

    /** 默认接入点：素材中的 run_start 标记；缺失时取第一个定位点。 */
    fun defaultJoinMs(events: List<JSONObject>): Long {
        events.firstOrNull { it.optString("type") == Events.TYPE_MARKER && it.optString("name") == "run_start" }
            ?.let { return it.getJSONObject("t").getLong("rel_ms") }
        events.firstOrNull { it.optString("type") == Events.TYPE_LOCATION }
            ?.let { return it.getJSONObject("t").getLong("rel_ms") }
        return 0L
    }

    /** 把 location 事件抽取为轨迹点。 */
    fun trackPoints(events: List<JSONObject>): List<TrackPoint> =
        events.asSequence()
            .filter { it.optString("type") == Events.TYPE_LOCATION }
            .map {
                TrackPoint(
                    relMs = it.getJSONObject("t").getLong("rel_ms"),
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                )
            }
            .toList()

    /** 从 [fromMs] 起，按定位点累计剩余里程（米）。 */
    fun remainingMeters(events: List<JSONObject>, fromMs: Long): Double =
        remainingMetersOfPoints(trackPoints(events), fromMs)

    /** 纯函数版本，便于单元测试。 */
    fun remainingMetersOfPoints(points: List<TrackPoint>, fromMs: Long): Double {
        var previous: TrackPoint? = null
        var total = 0.0
        for (point in points) {
            if (point.relMs < fromMs) continue
            if (previous != null) {
                total += haversine(previous.lat, previous.lon, point.lat, point.lon)
            }
            previous = point
        }
        return total
    }

    /** 接入所需的最小剩余里程。 */
    fun requiredMeters(requiredM: Double, marginM: Double = DEFAULT_MARGIN_M): Double =
        requiredM + marginM

    /**
     * 规划接入点。
     *
     * @param requestedMs 期望接入点（默认 [defaultJoinMs]）
     * @param requiredM 目标里程
     * @param maxJoinMs 允许的最大接入偏移（接入延迟预算）
     */
    fun plan(
        events: List<JSONObject>,
        requiredM: Double,
        requestedMs: Long = defaultJoinMs(events),
        marginM: Double = DEFAULT_MARGIN_M,
        maxJoinMs: Long = 5 * 60_000L,
    ): Result<JoinPlan> {
        if (requestedMs < 0) return Result.failure(IllegalArgumentException("接入偏移必须 ≥ 0"))
        if (requestedMs > maxJoinMs) {
            return Result.failure(IllegalStateException("接入偏移 $requestedMs ms 超过预算 $maxJoinMs ms"))
        }
        val remaining = remainingMeters(events, requestedMs)
        val needed = requiredMeters(requiredM, marginM)
        if (remaining < needed) {
            return Result.failure(
                IllegalStateException(
                    "剩余里程不足：剩余 %.0f m，需要 %.0f m（目标 %.0f + 余量 %.0f）".format(
                        remaining, needed, requiredM, marginM,
                    )
                )
            )
        }
        return Result.success(JoinPlan(requestedMs, remaining))
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
