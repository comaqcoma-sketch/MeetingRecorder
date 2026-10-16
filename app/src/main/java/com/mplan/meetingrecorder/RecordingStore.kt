package com.mplan.meetingrecorder

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class Recording(
    val path: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val hasText: Boolean      // 텍스트 변환·요약 완료 여부
)

/** 앱 전용 저장소의 녹음·텍스트 파일만 다룸 (다른 앱 파일은 절대 건드리지 않음) */
object RecordingStore {

    fun dir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    private fun base(path: String) = path.substringBeforeLast('.')
    fun transcriptFile(path: String) = File(base(path) + ".transcript.tsv")
    fun summaryFile(path: String) = File(base(path) + ".summary.md")

    fun titleOf(f: File): String {
        val parse = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
        val show = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA)
        return try { show.format(parse.parse(f.nameWithoutExtension)!!) } catch (_: Exception) { f.nameWithoutExtension }
    }

    suspend fun list(context: Context, excludePath: String?): List<Recording> = withContext(Dispatchers.IO) {
        dir(context).listFiles { f -> f.isFile && f.extension == "m4a" && f.absolutePath != excludePath }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { f ->
                Recording(
                    f.absolutePath, titleOf(f), readDuration(f), f.length(),
                    hasText = summaryFile(f.absolutePath).exists() && transcriptFile(f.absolutePath).exists()
                )
            }
    }

    private fun readDuration(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    }

    // ---------- 텍스트 ----------

    fun saveTranscript(context: Context, path: String, segments: List<Segment>) {
        val text = segments.joinToString("\n") {
            "${it.startMs}\t${it.endMs}\t${it.text.replace('\t', ' ').replace('\n', ' ')}"
        }
        transcriptFile(path).writeText(text)
    }

    suspend fun loadTranscript(path: String): List<Segment> = withContext(Dispatchers.IO) {
        val f = transcriptFile(path)
        if (!f.exists()) return@withContext emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split('\t', limit = 3)
            if (p.size == 3) Segment(
                p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: 0L,
                p[2].trim().trimStart('-', '–', '—', ' ').trim()   // 이전 버전 텍스트의 "- " 정리
            ) else null
        }
    }

    suspend fun loadSummary(path: String): String = withContext(Dispatchers.IO) {
        summaryFile(path).takeIf { it.exists() }?.readText() ?: ""
    }

    /** 요약 + 전체 텍스트를 하나의 글로 */
    suspend fun exportText(path: String): String {
        val summary = loadSummary(path)
        val segs = loadTranscript(path)
        return buildString {
            append(summary.trimEnd())
            appendLine(); appendLine()
            appendLine("## 전체 텍스트")
            segs.forEach { appendLine("[${formatElapsed(it.startMs)}] ${it.text}") }
        }
    }

    // ---------- 삭제·공유 ----------

    fun delete(context: Context, rec: Recording): Boolean {
        val f = File(rec.path)
        // 안전장치: 앱 전용 녹음 폴더 안의 파일만 삭제
        if (f.parentFile?.canonicalPath != dir(context).canonicalPath) return false
        transcriptFile(rec.path).delete()
        summaryFile(rec.path).delete()
        return f.delete()
    }

    /** Android 공유 시트로 넘기기만 함 (카톡·메일 등 대상 앱 동작에 관여 X) */
    fun share(context: Context, rec: Recording) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(rec.path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "회의 녹음 ${rec.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "녹음 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "회의록 $title")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "회의록 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 간단한 재생기. 화면을 벗어나면 반드시 release() */
class Player {
    private var mp: MediaPlayer? = null
    var currentPath: String? = null
        private set

    fun toggle(path: String, onEnd: () -> Unit): Boolean {
        if (currentPath == path) { release(); return false }
        return playFrom(path, 0, onEnd)
    }

    /** 지정 위치부터 재생 (텍스트 문장 탭 → 해당 위치 재생) */
    fun playFrom(path: String, positionMs: Long, onEnd: () -> Unit): Boolean {
        if (currentPath == path && mp != null) {
            return try { mp!!.seekTo(positionMs.toInt()); mp!!.start(); true } catch (_: Exception) { false }
        }
        release()
        return try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { release(); onEnd() }
                prepare()
                if (positionMs > 0) seekTo(positionMs.toInt())
                start()
            }
            currentPath = path
            true
        } catch (_: Exception) {
            release(); false
        }
    }

    fun positionMs(): Int = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun release() {
        try { mp?.stop() } catch (_: Exception) { }
        mp?.release()
        mp = null
        currentPath = null
    }
}
