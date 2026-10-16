package com.mplan.meetingrecorder

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 변환 모델 선택·다운로드 (앱 전용 저장소에만 보관, 최초 1회만 다운로드) */
enum class WhisperModel(val label: String, val fileName: String, val sizeMb: Int, val desc: String) {
    SMALL("정확 (권장)", "ggml-small-q5_1.bin", 190, "한국어 정확도가 좋음 · 변환 속도 보통"),
    BASE("빠름", "ggml-base-q5_1.bin", 60, "변환이 빠름 · 한국어 정확도 낮음");

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object ModelManager {
    private const val PREFS = "settings"
    private const val KEY_MODEL = "model"

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(ctx: Context, m: WhisperModel) = File(dir(ctx), m.fileName)

    fun isDownloaded(ctx: Context, m: WhisperModel) = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    fun selected(ctx: Context): WhisperModel? {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, null)
        return WhisperModel.entries.firstOrNull { it.name == name }
    }

    fun select(ctx: Context, m: WhisperModel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, m.name).apply()
        // 다른 모델 파일은 지워서 저장 공간 절약
        WhisperModel.entries.filter { it != m }.forEach { file(ctx, it).delete() }
    }

    /** 다운로드. onProgress(0~100). isCancelled()가 true면 중단 */
    fun download(ctx: Context, m: WhisperModel, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val target = file(ctx, m)
        val part = File(target.path + ".part")
        part.delete()
        var url = URL(m.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IllegalStateException("모델 다운로드 실패 (HTTP $code)")
            }
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.sizeMb * 1_048_576L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        if (isCancelled()) throw InterruptedException("취소됨")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("모델 파일 저장 실패")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
