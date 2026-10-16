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

/** 내장 AI 요약 모델 (Gemini Nano를 못 쓰는 폰에서 사용) */
object LocalLlm {
    const val NAME = "Qwen2.5 1.5B"
    const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val SIZE_MB = 1100
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"
}

object ModelManager {
    private const val PREFS = "settings"
    private const val KEY_MODEL = "model"
    private const val KEY_AI = "ai_summary"

    /** AI 요약 사용 여부. null = 아직 안 정함 */
    fun aiSummary(ctx: Context): Boolean? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY_AI)) p.getBoolean(KEY_AI, true) else null
    }

    fun setAiSummary(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AI, on).apply()
        if (!on) llmFile(ctx).delete()   // 끄면 1.1GB 모델 삭제해서 공간 확보
    }

    fun llmFile(ctx: Context) = File(dir(ctx), LocalLlm.FILE)
    fun isLlmDownloaded(ctx: Context) = llmFile(ctx).let { it.exists() && it.length() > 100_000_000 }

    fun downloadLlm(ctx: Context, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) =
        downloadUrl(LocalLlm.URL, llmFile(ctx), LocalLlm.SIZE_MB, onProgress, isCancelled)

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
    fun download(ctx: Context, m: WhisperModel, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) =
        downloadUrl(m.url, file(ctx, m), m.sizeMb, onProgress, isCancelled)

    private fun downloadUrl(
        urlStr: String, target: File, sizeMb: Int,
        onProgress: (Int) -> Unit, isCancelled: () -> Boolean
    ) {
        val part = File(target.path + ".part")
        part.delete()
        var url = URL(urlStr)
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
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (sizeMb * 1_048_576L)
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
