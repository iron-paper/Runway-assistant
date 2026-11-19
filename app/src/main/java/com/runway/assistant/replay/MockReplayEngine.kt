package com.runway.assistant.replay

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.runway.assistant.data.Events
import com.runway.assistant.data.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 调试用回放引擎：通过 Mock Provider 注入位置。
 *
 * 注意：该实现会在系统中留下 mock 标记，仅用于开发联调与 UI 验收，
 * 正式会话必须使用 [PrivilegedReplayEngine]。
 */
@Suppress("DEPRECATION")
class MockReplayEngine(private val context: Context) : ReplayEngine {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val _state = MutableStateFlow(EngineState.IDLE)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var events: List<JSONObject> = emptyList()
    private var sessionDir: File? = null
    private var providerAdded = false

    override suspend fun prepare(sessionDir: File): Result<Unit> {
        _state.value = EngineState.PREPARING
        return runCatching {
            this.sessionDir = sessionDir
            events = RecordingStore.readEvents(sessionDir)
            require(events.isNotEmpty()) { "素材为空：$sessionDir" }
            _state.value = EngineState.READY
        }.onFailure { _state.value = EngineState.ERROR }
    }

    override suspend fun start(joinOffsetMs: Long): Result<Unit> = runCatching {
        require(events.isNotEmpty()) { "尚未加载素材" }
        addProvider()
        _state.value = EngineState.PLAYING
    }.onFailure { _state.value = EngineState.ERROR }

    override suspend fun emit(event: JSONObject): Result<Unit> {
        if (event.optString("type") != Events.TYPE_LOCATION) return Result.success(Unit)
        return runCatching {
            if (!providerAdded) addProvider()
            val location = Location(PROVIDER).apply {
                latitude = event.getDouble("lat")
                longitude = event.getDouble("lon")
                altitude = event.optDouble("alt", 0.0)
                accuracy = event.optDouble("acc_h", 5.0).toFloat()
                speed = event.optDouble("speed", 0.0).toFloat()
                bearing = event.optDouble("bearing", 0.0).toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = event.optDouble("acc_v", 5.0).toFloat()
                }
            }
            locationManager.setTestProviderLocation(PROVIDER, location)
        }
    }

    override suspend fun pause(): Result<Unit> = runCatching { _state.value = EngineState.PAUSED }

    override suspend fun resume(): Result<Unit> = runCatching { _state.value = EngineState.PLAYING }

    override suspend fun stop(): Result<Unit> = runCatching {
        removeProvider()
        _state.value = EngineState.ENDED
    }

    override fun release() {
        runCatching { removeProvider() }
        _state.value = EngineState.IDLE
    }

    private fun addProvider() {
        if (providerAdded) return
        locationManager.addTestProvider(
            PROVIDER,
            false, false, false, false,
            true, true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE,
        )
        locationManager.setTestProviderEnabled(PROVIDER, true)
        providerAdded = true
    }

    private fun removeProvider() {
        if (!providerAdded) return
        runCatching { locationManager.setTestProviderEnabled(PROVIDER, false) }
        runCatching { locationManager.removeTestProvider(PROVIDER) }
        providerAdded = false
    }

    companion object {
        private const val PROVIDER = "runway_mock"
    }
}
