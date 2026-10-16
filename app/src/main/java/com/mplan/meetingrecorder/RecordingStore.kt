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
    val sizeBytes: Long
)

/** 앱 전용 저장소의 녹음 파일만 다룸 (다른 앱 파일은 절대 건드리지 않음) */
object RecordingStore {

    fun dir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    suspend fun list(context: Context, excludePath: String?): List<Recording> = withContext(Dispatchers.IO) {
        val parse = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
        val show = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA)
        dir(context).listFiles { f -> f.isFile && f.extension == "m4a" && f.absolutePath != excludePath }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { f ->
                val title = try { show.format(parse.parse(f.nameWithoutExtension)!!) } catch (_: Exception) { f.nameWithoutExtension }
                Recording(f.absolutePath, title, readDuration(f), f.length())
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

    fun delete(context: Context, rec: Recording): Boolean {
        val f = File(rec.path)
        // 안전장치: 앱 전용 녹음 폴더 안의 파일만 삭제
        if (f.parentFile?.canonicalPath != dir(context).canonicalPath) return false
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
}

/** 간단한 재생기. 화면을 벗어나면 반드시 release() */
class Player {
    private var mp: MediaPlayer? = null
    var currentPath: String? = null
        private set

    fun toggle(path: String, onEnd: () -> Unit): Boolean {
        if (currentPath == path) { release(); return false }
        release()
        return try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { release(); onEnd() }
                prepare()
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
