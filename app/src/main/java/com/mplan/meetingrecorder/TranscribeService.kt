package com.mplan.meetingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

data class TranscribeState(
    val path: String? = null,      // 변환 중인 녹음 파일
    val phase: String = "",
    val progress: Int = 0,         // 0~100
    val error: String? = null,
    val doneCount: Int = 0         // 완료 횟수 (목록 새로고침 신호)
)

/**
 * 녹음 → 텍스트 → 요약을 폰 안에서 처리하는 포그라운드 서비스.
 * 화면을 꺼도 계속 진행되며, 녹음 파일은 외부로 전송되지 않음.
 * (인터넷은 최초 1회 모델 다운로드에만 사용)
 */
class TranscribeService : Service() {

    companion object {
        const val ACTION_START = "com.mplan.meetingrecorder.TRANSCRIBE"
        const val ACTION_CANCEL = "com.mplan.meetingrecorder.TRANSCRIBE_CANCEL"
        const val EXTRA_PATH = "path"
        const val EXTRA_SUMMARY_ONLY = "summary_only"

        private const val CHANNEL_ID = "transcribe"
        private const val NOTIF_ID = 2001
        private const val CHUNK_SECONDS = 300   // 5분씩 나눠 처리 (메모리 절약)

        private val _state = MutableStateFlow(TranscribeState())
        val state: StateFlow<TranscribeState> = _state.asStateFlow()

        /** @param summaryOnly true면 텍스트 변환은 건너뛰고 요약만 다시 만듦 */
        fun start(context: Context, path: String, summaryOnly: Boolean = false) {
            if (_state.value.path != null) return  // 한 번에 하나씩
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscribeService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_PATH, path).putExtra(EXTRA_SUMMARY_ONLY, summaryOnly)
            )
        }

        fun cancel(context: Context) {
            if (_state.value.path == null) return
            context.startService(Intent(context, TranscribeService::class.java).setAction(ACTION_CANCEL))
        }

        fun clearError() = _state.update { it.copy(error = null) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false
    @Volatile private var chunkStart = 0L   // 현재 처리 중인 구간 시작(ms)
    @Volatile private var chunkLen = 0L     // 현재 구간 길이(ms)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path == null || job?.isActive == true) return START_NOT_STICKY
                try {
                    ServiceCompat.startForeground(
                        this, NOTIF_ID, buildNotification("준비 중", 0),
                        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                    )
                } catch (e: Exception) {
                    _state.update { it.copy(error = "변환을 시작할 수 없습니다: ${e.message}") }
                    stopSelf()
                    return START_NOT_STICKY
                }
                cancelled = false
                _state.update { it.copy(path = path, phase = "준비 중", progress = 0, error = null) }
                val summaryOnly = intent.getBooleanExtra(EXTRA_SUMMARY_ONLY, false)
                job = scope.launch { process(path, summaryOnly) }
            }
            ACTION_CANCEL -> {
                cancelled = true
                try { WhisperLib.abort() } catch (_: Throwable) { }
                try { com.mplan.llm.LlamaLib.abort() } catch (_: Throwable) { }
            }
            else -> if (job?.isActive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setPhase(phase: String, progress: Int) {
        val s = _state.value
        if (s.phase == phase && s.progress == progress) return
        _state.update { it.copy(phase = phase, progress = progress) }
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(phase, progress))
        } catch (_: SecurityException) { }
    }

    private suspend fun process(path: String, summaryOnly: Boolean) {
        // 화면이 꺼져도 CPU가 멈추지 않도록 (최대 6시간, 끝나면 즉시 해제)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeetingRecorder:transcribe")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }

        var ctxPtr = 0L
        var error: String? = null
        var success = false
        try {
            val totalMs = AudioDecoder.durationMs(path).coerceAtLeast(1L)
            val segments: List<Segment>
            if (summaryOnly) {
                segments = RecordingStore.loadTranscript(path)
                if (segments.isEmpty()) throw IllegalStateException("변환된 텍스트가 없습니다.")
            } else {
                val model = ModelManager.selected(this) ?: WhisperModel.SMALL

                // 1) 변환 모델 (최초 1회)
                if (!ModelManager.isDownloaded(this, model)) {
                    val label = "변환 모델 다운로드 (최초 1회, ${model.sizeMb}MB)"
                    setPhase(label, 0)
                    ModelManager.download(this, model, { p -> setPhase(label, p) }, { cancelled })
                }
                if (cancelled) throw InterruptedException()

                // 2) 모델 불러오기
                setPhase("변환 엔진 준비 중", 0)
                ctxPtr = WhisperLib.initContext(ModelManager.file(this, model).absolutePath)
                if (ctxPtr == 0L) {
                    ModelManager.file(this, model).delete()  // 손상된 파일이면 다음에 다시 받도록
                    throw IllegalStateException("변환 모델을 불러오지 못했습니다. 다시 시도해 주세요.")
                }
                WhisperLib.resetAbort()

                // 3) 음성 → 텍스트 (5분 단위)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val list = ArrayList<Segment>()
                chunkStart = 0L
                chunkLen = 0L
                val poller = scope.launch {
                    while (isActive) {
                        val p = WhisperLib.getProgress().coerceIn(0, 100)
                        val overall = ((chunkStart + chunkLen * p / 100) * 100 / totalMs).toInt().coerceIn(0, 99)
                        setPhase("텍스트 변환 중", overall)
                        delay(700)
                    }
                }
                try {
                    AudioDecoder.decode(path, CHUNK_SECONDS) { samples, startMs ->
                        if (cancelled) return@decode false
                        chunkStart = startMs
                        chunkLen = samples.size * 1000L / AudioDecoder.TARGET_RATE
                        val raw = WhisperLib.transcribe(ctxPtr, samples, "ko", threads)
                        if (cancelled) return@decode false
                        if (raw == null) throw IllegalStateException("텍스트 변환 중 오류가 발생했습니다.")
                        raw.forEach { bytes ->
                            val parts = String(bytes, Charsets.UTF_8).split('\t', limit = 3)
                            if (parts.size == 3) {
                                val text = cleanText(parts[2])
                                if (text.isNotEmpty() && list.lastOrNull()?.text != text) {
                                    list += Segment(
                                        startMs + (parts[0].toLongOrNull() ?: 0L),
                                        startMs + (parts[1].toLongOrNull() ?: 0L),
                                        text
                                    )
                                }
                            }
                        }
                        true
                    }
                } finally {
                    poller.cancel()
                }
                if (cancelled) throw InterruptedException()
                // 변환 엔진은 바로 해제 → AI 요약에 메모리 양보
                WhisperLib.freeContext(ctxPtr)
                ctxPtr = 0L
                RecordingStore.saveTranscript(this, path, list)
                segments = list
            }

            // 4) 요약: AI(Gemini Nano → 내장 AI) → 실패하면 기본(규칙) 요약
            val title = RecordingStore.titleOf(File(path))
            var body: String? = null
            var engineName = "기본 요약 (AI 아님)"
            if (ModelManager.aiSummary(this) == true) {
                val ai = AiSummarizer(this, { cancelled }, ::setPhase)
                val r = try { ai.summarize(segments) } catch (_: InterruptedException) {
                    throw InterruptedException()
                } catch (_: Throwable) { null }
                if (cancelled) throw InterruptedException()
                if (r != null) { body = r.first; engineName = r.second }
            }
            setPhase("요약 저장 중", 99)
            val summary = if (body != null) {
                "# 회의 요약 — $title\n회의 길이: ${formatElapsed(totalMs)} · 요약: $engineName\n\n$body\n"
            } else {
                Summarizer.toMarkdown(title, totalMs, Summarizer.summarize(segments))
                    .replaceFirst("\n", "\n요약: $engineName\n")
            }
            RecordingStore.summaryFile(path).writeText(summary)
            success = true
        } catch (_: InterruptedException) {
            error = null  // 사용자가 취소
        } catch (e: OutOfMemoryError) {
            error = "메모리가 부족합니다. 다른 앱을 닫고 다시 시도하거나 '빠름' 모델을 사용해 보세요."
        } catch (e: Throwable) {
            error = e.message ?: "변환 중 오류가 발생했습니다."
        } finally {
            if (ctxPtr != 0L) WhisperLib.freeContext(ctxPtr)
            try { wakeLock?.release() } catch (_: Exception) { }
            wakeLock = null
            _state.update {
                TranscribeState(
                    doneCount = if (success) it.doneCount + 1 else it.doneCount,
                    error = error
                )
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Whisper가 무음 구간에 만드는 잡음 표시 제거 */
    private fun cleanText(t: String): String {
        val s = t.trim()
        if (s.isEmpty()) return ""
        if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("(") && s.endsWith(")"))) return ""
        if (s == "..." || s == "…") return ""
        return s
    }

    private fun buildNotification(phase: String, progress: Int): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "텍스트 변환", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); setShowBadge(false)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelPi = PendingIntent.getService(
            this, 11, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("회의 텍스트 변환")
            .setContentText("$phase · $progress%")
            .setProgress(100, progress, false)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "취소", cancelPi)
            .build()
    }

    override fun onDestroy() {
        cancelled = true
        try { WhisperLib.abort() } catch (_: Throwable) { }
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) { }
        super.onDestroy()
    }
}
