package com.runway.assistant.replay

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** 回放引擎状态。 */
enum class EngineState {
    IDLE,
    PREPARING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/**
 * 回放引擎抽象。
 *
 * 调度器只负责“什么时候送哪一帧”，引擎只负责“怎么送”。替换注入层时，
 * [ReplayScheduler] 不需要改动。
 */
interface ReplayEngine {

    val state: StateFlow<EngineState>

    /** 加载并校验素材，成功后进入 [EngineState.READY]。 */
    suspend fun prepare(sessionDir: File): Result<Unit>

    /** 从素材的 [joinOffsetMs] 处开始注入。 */
    suspend fun start(joinOffsetMs: Long): Result<Unit>

    /** 送出一帧事件，由引擎决定落到哪个系统通道。 */
    suspend fun emit(event: JSONObject): Result<Unit>

    suspend fun pause(): Result<Unit>

    suspend fun resume(): Result<Unit>

    suspend fun stop(): Result<Unit>

    fun release()
}

/** 引擎工厂：根据运行环境选择 Mock 或特权实现。 */
object ReplayEngines {

    fun create(usePrivileged: Boolean, context: android.content.Context): ReplayEngine =
        if (usePrivileged) PrivilegedReplayEngine() else MockReplayEngine(context)
}
