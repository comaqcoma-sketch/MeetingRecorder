package com.mplan.meetingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val elapsedMs: Long = 0L,
    val level: Float = 0f,
    val filePath: String? = null,
    val error: String? = null,
    val savedCount: Int = 0            // 저장 완료 횟수 (목록 새로고침 신호)
)

/**
 * 녹음 전용 포그라운드 서비스.
 * - 앱 자체 MediaRecorder 사용 (기본 녹음기 앱과 무관)
 * - 앱 전용 저장소(filesDir/recordings)에만 저장
 * - 모든 종료 경로에서 마이크 즉시 반납
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.mplan.meetingrecorder.START"
        const val ACTION_PAUSE = "com.mplan.meetingrecorder.PAUSE"
        const val ACTION_RESUME = "com.mplan.meetingrecorder.RESUME"
        const val ACTION_STOP = "com.mplan.meetingrecorder.STOP"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state.asStateFlow()

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecorderService::class.java).setAction(action)
            if (action == ACTION_START) {
                ContextCompat.startForegroundService(context, intent)
            } else if (_state.value.status != RecStatus.IDLE) {
                context.startService(intent)
            }
        }

        fun clearError() = _state.update { it.copy(error = null) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var accumulatedMs = 0L      // 일시정지 전까지 누적된 시간
    private var segmentStart = 0L       // 현재 구간 시작 시각

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return

        // 포그라운드 전환을 가장 먼저 (5초 제한)
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            _state.update { it.copy(error = "녹음을 시작할 수 없습니다: ${e.message}") }
            stopSelf()
            return
        }

        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val file = File(dir, "$name.m4a")

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            _state.update { it.copy(status = RecStatus.IDLE, error = "마이크를 사용할 수 없습니다. 다른 앱이 사용 중인지 확인하세요.") }
            shutdown()
            return
        }

        recorder = r
        outputFile = file
        accumulatedMs = 0L
        segmentStart = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(status = RecStatus.RECORDING, elapsedMs = 0L, level = 0f,
                filePath = file.absolutePath, error = null)
        }
        startTicker()
    }

    private fun pauseRecording() {
        val r = recorder ?: return
        if (_state.value.status != RecStatus.RECORDING) return
        try {
            r.pause()
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
            _state.update { it.copy(status = RecStatus.PAUSED, elapsedMs = accumulatedMs, level = 0f) }
            updateNotification()
        } catch (_: Exception) { }
    }

    private fun resumeRecording() {
        val r = recorder ?: return
        if (_state.value.status != RecStatus.PAUSED) return
        try {
            r.resume()
            segmentStart = SystemClock.elapsedRealtime()
            _state.update { it.copy(status = RecStatus.RECORDING) }
            updateNotification()
        } catch (_: Exception) { }
    }

    private fun stopRecording() {
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        tickJob?.cancel()

        var saved = false
        if (r != null) {
            try {
                r.stop()
                saved = true
            } catch (_: Exception) {
                // 너무 짧게 녹음된 경우 등 → 깨진 파일 삭제
                file?.delete()
            } finally {
                r.release()   // 마이크 즉시 반납
            }
        }
        _state.update {
            RecState(savedCount = if (saved) it.savedCount + 1 else it.savedCount,
                error = if (r != null && !saved) "녹음이 너무 짧아 저장되지 않았습니다." else null)
        }
        shutdown()
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var lastNotifSec = -1L
            while (isActive) {
                if (_state.value.status == RecStatus.RECORDING) {
                    val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart)
                    val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                    val level = (amp / 32767f).coerceIn(0f, 1f)
                    _state.update { it.copy(elapsedMs = elapsed, level = level) }
                    val sec = elapsed / 1000
                    if (sec != lastNotifSec) {
                        lastNotifSec = sec
                        updateNotification()
                    }
                }
                delay(120)
            }
        }
    }

    // ---------- 알림 ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "녹음 중", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
        }

        val s = _state.value
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(act: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (s.status == RecStatus.PAUSED) "일시정지됨" else "회의 녹음 중"

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(formatElapsed(s.elapsedMs))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (s.status == RecStatus.PAUSED) b.addAction(0, "재개", action(ACTION_RESUME, 2))
        else b.addAction(0, "일시정지", action(ACTION_PAUSE, 1))
        b.addAction(0, "정지·저장", action(ACTION_STOP, 3))
        return b.build()
    }

    private fun updateNotification() {
        if (recorder == null) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        // 앱이 강제로 정리돼도 파일 저장 + 마이크 반납
        if (recorder != null) stopRecording()
        scope.cancel()
        super.onDestroy()
    }
}

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.KOREA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.KOREA, "%02d:%02d", m, s)
}
