package com.runway.assistant.replay

import com.runway.assistant.data.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 回放调度器：前奏循环 + 正片 1:1。
 *
 * - 前奏：接入点之前的一段（默认 60 秒）循环播放，用于让客户端完成定位预热；
 * - 正片：从接入点开始严格按素材时间轴播放，暂停时冻结。
 */
class ReplayScheduler(
    private val engine: ReplayEngine,
    private val events: List<JSONObject>,
) {

    private var preRollJob: Job? = null
    private var mainJob: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    fun eventCount(): Int = events.size

    /** 播放前奏，循环 [preRollMs] 窗口，直到调用 [beginMain]。 */
    fun startPreRoll(scope: CoroutineScope, joinMs: Long, preRollMs: Long = 60_000) {
        stopped = false
        preRollJob?.cancel()
        preRollJob = scope.launch {
            val windowStart = (joinMs - preRollMs).coerceAtLeast(0)
            val window = events.filter { relMs(it) in windowStart until joinMs }
            if (window.isEmpty()) return@launch
            while (!stopped) {
                var previous = windowStart
                for (event in window) {
                    if (stopped) return@launch
                    val t = relMs(event)
                    val wait = (t - previous).coerceAtLeast(0)
                    if (wait > 0) delay(wait)
                    engine.emit(event)
                    previous = t
                }
                delay(200)
            }
        }
    }

    /** 从接入点开始播放正片。 */
    fun beginMain(scope: CoroutineScope, joinMs: Long) {
        stopped = false
        preRollJob?.cancel()
        mainJob?.cancel()
        mainJob = scope.launch {
            val tail = events.filter { relMs(it) >= joinMs }
            if (tail.isEmpty()) return@launch
            var previous = joinMs
            for (event in tail) {
                if (stopped) return@launch
                while (paused && !stopped) delay(50)
                val t = relMs(event)
                val wait = (t - previous).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                engine.emit(event)
                previous = t
            }
            engine.stop()
        }
    }

    fun pause() {
        paused = true
        preRollJob?.cancel()
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        stopped = true
        paused = false
        preRollJob?.cancel()
        mainJob?.cancel()
    }

    private fun relMs(event: JSONObject): Long =
        if (event.optString("type") == Events.TYPE_MARKER) {
            event.getJSONObject("t").getLong("rel_ms")
        } else {
            event.getJSONObject("t").getLong("rel_ms")
        }
}
