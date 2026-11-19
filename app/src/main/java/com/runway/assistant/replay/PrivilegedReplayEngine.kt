package com.runway.assistant.replay

import com.runway.assistant.data.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 正式回放引擎：通过 JNI 调用注入层（见 native/）。
 *
 * 注入层负责把事件落到 GNSS HAL、Sensor HAL 与 ActivityRecognition 通道，
 * 本类只做参数校验、状态维护与错误码透传。
 */
class PrivilegedReplayEngine : ReplayEngine {

    private val _state = MutableStateFlow(EngineState.IDLE)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var events: List<JSONObject> = emptyList()
    private var handle: Long = 0L

    override suspend fun prepare(sessionDir: File): Result<Unit> {
        _state.value = EngineState.PREPARING
        if (!nativeAvailable) {
            _state.value = EngineState.ERROR
            return Result.failure(IllegalStateException("注入层未安装：lib runwy 缺失"))
        }
        return runCatching {
            events = RecordingStore.readEvents(sessionDir)
            require(events.isNotEmpty()) { "素材为空：$sessionDir" }
            sessionDirPath = sessionDir.absolutePath
            _state.value = EngineState.READY
        }.onFailure { _state.value = EngineState.ERROR }
    }

    override suspend fun start(joinOffsetMs: Long): Result<Unit> {
        if (!nativeAvailable) return Result.failure(IllegalStateException("注入层未安装"))
        return runCatching {
            handle = nativeInit("""{"mode":"replay"}""")
            check(handle != 0L) { "nativeInit 失败" }
            val code = nativeStart(handle, sessionDirPath, joinOffsetMs)
            check(code == 0) { "nativeStart 失败，错误码 $code" }
            _state.value = EngineState.PLAYING
        }.onFailure { _state.value = EngineState.ERROR }
    }

    override suspend fun emit(event: JSONObject): Result<Unit> {
        if (handle == 0L) return Result.failure(IllegalStateException("引擎未启动"))
        val code = nativeEmit(handle, event.toString())
        return if (code == 0) {
            Result.success(Unit)
        } else {
            _state.value = EngineState.ERROR
            Result.failure(IllegalStateException("nativeEmit 失败，错误码 $code"))
        }
    }

    override suspend fun pause(): Result<Unit> = runCatching {
        check(handle != 0L) { "引擎未启动" }
        check(nativePause(handle) == 0) { "nativePause 失败" }
        _state.value = EngineState.PAUSED
    }

    override suspend fun resume(): Result<Unit> = runCatching {
        check(handle != 0L) { "引擎未启动" }
        check(nativeResume(handle) == 0) { "nativeResume 失败" }
        _state.value = EngineState.PLAYING
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        if (handle != 0L) {
            runCatching { nativeStop(handle) }
            handle = 0L
        }
        _state.value = EngineState.ENDED
    }

    override fun release() {
        if (handle != 0L) {
            runCatching { nativeStop(handle) }
            handle = 0L
        }
        _state.value = EngineState.IDLE
    }

    private var sessionDirPath: String = ""

    private companion object {
        val nativeAvailable: Boolean = runCatching { System.loadLibrary("runway") }.isSuccess

        @JvmStatic external fun nativeInit(config: String): Long

        @JvmStatic external fun nativeStart(handle: Long, path: String, joinOffsetMs: Long): Int

        @JvmStatic external fun nativeEmit(handle: Long, eventJson: String): Int

        @JvmStatic external fun nativePause(handle: Long): Int

        @JvmStatic external fun nativeResume(handle: Long): Int

        @JvmStatic external fun nativeStop(handle: Long): Int

        @JvmStatic external fun nativeStatus(handle: Long): String
    }
}
