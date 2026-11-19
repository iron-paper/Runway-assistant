package com.runway.assistant.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.runway.assistant.MainActivity
import com.runway.assistant.R
import com.runway.assistant.data.AppInfo
import com.runway.assistant.data.DeviceInfo
import com.runway.assistant.data.EnvironmentInfo
import com.runway.assistant.data.Ids
import com.runway.assistant.data.RecordingMeta
import com.runway.assistant.data.RecordingStore
import com.runway.assistant.data.SessionManifest
import java.io.File

/**
 * 录制前台服务。
 *
 * 通过 Intent action 控制：
 * - [ACTION_START] 开始新录制
 * - [ACTION_STOP] 停止录制并生成清单
 * - [ACTION_MARK_RUN_START] / [ACTION_MARK_RUN_END] 打点
 */
class RecorderService : Service() {

    private var recorder: SessionRecorder? = null
    private var sessionDir: File? = null
    private var sessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_MARK_RUN_START -> recorder?.markRunStart()
            ACTION_MARK_RUN_END -> recorder?.markRunEnd()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        val id = Ids.newSessionId()
        val dir = RecordingStore.sessionDir(this, id).apply { mkdirs() }
        sessionId = id
        sessionDir = dir

        val startedAt = System.currentTimeMillis()
        val writer = RecordingStore.Writer(dir) { counts, duration ->
            SessionManifest(
                sessionId = id,
                createdAt = Ids.isoUtc(startedAt),
                device = DeviceInfo(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    sdk = Build.VERSION.SDK_INT,
                    rom = Build.DISPLAY,
                ),
                app = AppInfo(
                    version = packageManager.getPackageInfo(packageName, 0).versionName,
                    build = "debug",
                ),
                recording = RecordingMeta(
                    startedAt = Ids.isoUtc(startedAt),
                    endedAt = Ids.isoUtc(startedAt + duration),
                    durationMs = duration,
                    distanceM = recorder?.distanceMeters() ?: 0.0,
                ),
                counts = counts,
                environment = EnvironmentInfo(surface = "track"),
            )
        }

        val sessionRecorder = SessionRecorder(this, writer)
        recorder = sessionRecorder
        sessionRecorder.start()

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopRecording() {
        recorder?.stop()
        recorder = null
        RecordingStore.writeChecksum(sessionDir ?: return)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_recorder_title))
            .setContentText(getString(R.string.notif_recorder_text))
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_recorder), NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.runway.assistant.action.START_RECORD"
        const val ACTION_STOP = "com.runway.assistant.action.STOP_RECORD"
        const val ACTION_MARK_RUN_START = "com.runway.assistant.action.MARK_RUN_START"
        const val ACTION_MARK_RUN_END = "com.runway.assistant.action.MARK_RUN_END"

        private const val CHANNEL_ID = "recorder"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action)

        fun start(context: Context) =
            context.startForegroundService(intent(context, ACTION_START))

        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        fun markRunStart(context: Context) = context.startService(intent(context, ACTION_MARK_RUN_START))

        fun markRunEnd(context: Context) = context.startService(intent(context, ACTION_MARK_RUN_END))
    }
}
