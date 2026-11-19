package com.runway.assistant.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.runway.assistant.data.Events
import com.runway.assistant.data.RecordingStore
import com.runway.assistant.data.TimeStamp
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 统一的数据采集器：注册位置、IMU、计步、活动识别与设备状态，按事件写入素材。
 *
 * 约定：
 * - 所有事件使用双时间域（UTC + 开机纳秒）；
 * - relMs 以 [start] 的时刻为 0；
 * - 录制阶段只读系统数据。
 */
class SessionRecorder(
    private val context: Context,
    private val writer: RecordingStore.Writer,
) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var startElapsedNs: Long = 0
    private var lastStepCounter: Long = -1
    private var totalDistanceM: Double = 0.0
    private var lastLocation: Location? = null
    private var running = false

    private val recentSteps = ArrayDeque<Long>()
    private var lastActivity: String = "unknown"

    fun start() {
        if (running) return
        running = true
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        writer.write(Events.marker(now(), "record_start"))

        registerLocation()
        registerSensors()
        scheduleDeviceSample()
    }

    fun markRunStart() = writer.write(Events.marker(now(), "run_start"))

    fun markRunEnd() = writer.write(Events.marker(now(), "run_end"))

    fun stop() {
        if (!running) return
        running = false
        writer.write(Events.marker(now(), "record_end"))
        runCatching { locationManager.removeUpdates(this) }
        runCatching { sensorManager.unregisterListener(this) }
        handler.removeCallbacksAndMessages(null)
        writer.close()
    }

    fun distanceMeters(): Double = totalDistanceM

    // ---------------------------------------------------------------- 位置

    private fun registerLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper())
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        if (previous != null) {
            totalDistanceM += haversine(previous.latitude, previous.longitude, location.latitude, location.longitude)
        }
        lastLocation = location

        writer.write(
            Events.location(
                t = now(),
                lat = location.latitude,
                lon = location.longitude,
                alt = location.altitude,
                accuracyH = location.accuracy,
                accuracyV = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) location.verticalAccuracyMeters else 0f,
                speed = location.speed,
                bearing = location.bearing,
                provider = location.provider ?: "unknown",
                satellites = location.extras?.getInt("satellites", 0) ?: 0,
                snrAvg = 0f,
                hdop = 0f,
                isMock = isMockLocation(location),
            )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    // ---------------------------------------------------------------- 传感器

    private fun registerSensors() {
        sensorsOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD)
            .forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun sensorsOf(vararg types: Int): List<Sensor> =
        types.toList().mapNotNull { sensorManager.getDefaultSensor(it) }

    override fun onSensorChanged(event: SensorEvent) {
        val t = now()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> writer.write(Events.imu(t, "accelerometer", event.values, event.accuracy))
            Sensor.TYPE_GYROSCOPE -> writer.write(Events.imu(t, "gyroscope", event.values, event.accuracy))
            Sensor.TYPE_MAGNETIC_FIELD -> writer.write(Events.imu(t, "magnetometer", event.values, event.accuracy))
            Sensor.TYPE_PRESSURE -> writer.write(
                Events.baro(t, event.values[0], pressureToAltitude(event.values[0]))
            )
            Sensor.TYPE_STEP_COUNTER -> {
                val counter = event.values[0].toLong()
                val delta = if (lastStepCounter < 0) 0 else (counter - lastStepCounter).coerceAtLeast(0)
                lastStepCounter = counter
                writer.write(Events.step(t, counter, delta, detector = false))
                recordSteps(delta, t)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                writer.write(Events.step(t, lastStepCounter.coerceAtLeast(0), 1, detector = true))
                recordSteps(1, t)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 用步事件做轻量活动识别；正式的 ActivityRecognition 通道在注入层联调时替换。 */
    private fun recordSteps(steps: Long, t: TimeStamp) {
        repeat(steps.coerceAtMost(4).toInt()) { recentSteps.addLast(t.elapsedNs) }
        val cutoff = t.elapsedNs - 5_000_000_000L
        while (recentSteps.isNotEmpty() && recentSteps.first() < cutoff) recentSteps.removeFirst()

        val perMinute = recentSteps.size * 12
        val activity = when {
            perMinute >= 100 -> "running"
            perMinute >= 20 -> "walking"
            else -> "still"
        }
        if (activity != lastActivity) {
            lastActivity = activity
            writer.write(Events.activity(t, activity, confidence = 60))
        }
    }

    // ---------------------------------------------------------------- 设备

    private fun scheduleDeviceSample() {
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                writer.write(
                    Events.device(
                        t = now(),
                        batteryPct = batteryPercent(),
                        charging = isCharging(),
                        screenOn = context.getSystemService(android.os.PowerManager::class.java)
                            ?.isInteractive == true,
                        network = networkType(),
                    )
                )
                handler.postDelayed(this, 30_000L)
            }
        })
    }

    private fun batteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun networkType(): String {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return "none"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "none"
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun now(): TimeStamp {
        val elapsed = SystemClock.elapsedRealtimeNanos()
        return TimeStamp(
            utcMs = System.currentTimeMillis(),
            elapsedNs = elapsed,
            relMs = (elapsed - startElapsedNs) / 1_000_000L,
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isMockLocation(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    private fun pressureToAltitude(hPa: Float): Float =
        android.hardware.SensorManager.getAltitude(android.hardware.SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    @Suppress("unused")
    private fun eventTypeOf(event: JSONObject): String = event.optString("type", "unknown")
}
