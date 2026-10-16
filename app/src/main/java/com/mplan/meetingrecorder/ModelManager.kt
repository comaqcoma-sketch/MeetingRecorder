package com.mplan.meetingrecorder

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 변환 모델 선택·다운로드 (앱 전용 저장소에만 보관, 최초 1회만 다운로드) */
enum class WhisperModel(val label: String, val fileName: String, val sizeMb: Int, val desc: String) {
    TURBO("최고 정확", "ggml-large-v3-turbo-q5_0.bin", 547, "발음 인식이 가장 정확함 · 변환이 느림 · RAM 6GB 이상 폰"),
    SMALL("보통", "ggml-small-q5_1.bin", 190, "정확도·속도 중간 · 대부분의 폰"),
    BASE("빠름", "ggml-base-q5_1.bin", 60, "변환이 빠름 · 한국어 정확도 낮음 · 저사양 폰");

    /** 빔 서치 크기 (정확도↑ 속도↓) */
    val beam get() = when (this) { TURBO -> 5; SMALL -> 3; BASE -> 1 }

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/** 내장 AI 요약 모델 (Gemini Nano를 못 쓰는 폰에서 사용) */
object LocalLlm {
    const val NAME = "Qwen2.5 1.5B"
    const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val SIZE_MB = 1100
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"
}

/** 폰 사양 확인 (여러 사람이 다양한 폰에서 쓰므로) */
object Device {
    fun totalRamGb(ctx: Context): Double {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    /** 내장 AI(1.1GB)를 무리 없이 돌릴 수 있는지: RAM 약 6GB 이상 */
    fun canRunLocalLlm(ctx: Context) = totalRamGb(ctx) >= 5.0

    fun freeStorageMb(ctx: Context): Long = android.os.StatFs(ctx.filesDir.path).availableBytes / 1_048_576
}

enum class SpeechLang(val code: String, val label: String, val desc: String) {
    KO("ko", "한국어", "한국어 회의 (가장 정확)"),
    AUTO("auto", "자동 감지", "영어 등 다른 언어 회의도 인식 · 한국어는 '한국어' 선택이 더 정확");
}

object ModelManager {
    private const val PREFS = "settings"
    private const val KEY_MODEL = "model"
    private const val KEY_AI = "ai_summary"
    private const val KEY_LANG = "lang"
    private const val KEY_WORDS = "words"
    private const val KEY_SPK = "speakers"

    /** 화자 구분: -1=끔, 0=자동, 2 이상=참석자 수 */
    fun speakers(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SPK, 0)

    fun setSpeakers(ctx: Context, n: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SPK, n).apply()
        if (n < 0) speakerFile(ctx).delete()
    }

    fun speakerFile(ctx: Context) = File(dir(ctx), SpeakerModel.FILE)
    fun isSpeakerDownloaded(ctx: Context) = speakerFile(ctx).let { it.exists() && it.length() > 1_000_000 }

    fun downloadSpeaker(ctx: Context, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        checkSpace(ctx, SpeakerModel.SIZE_MB)
        downloadUrl(SpeakerModel.URL, speakerFile(ctx), SpeakerModel.SIZE_MB, onProgress, isCancelled)
    }

    /** 폰 사양에 맞는 기본 변환 모델 */
    fun recommended(ctx: Context) = if (Device.canRunLocalLlm(ctx)) WhisperModel.TURBO else WhisperModel.SMALL

    /** 자주 나오는 단어 (이름·회사명·전문용어) */
    fun words(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WORDS, "") ?: ""

    fun setWords(ctx: Context, w: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORDS, w.trim().take(200)).apply()
    }

    /** Whisper 힌트 문장: 한국어 문장 스타일 + 사용자 단어 */
    fun speechPrompt(ctx: Context): String {
        val w = words(ctx)
        val base = if (lang(ctx) == SpeechLang.KO) "다음은 한국어 회의 녹음입니다. 안녕하세요, 회의를 시작하겠습니다." else ""
        return if (w.isBlank()) base else "$base 주요 단어: $w."
    }

    fun lang(ctx: Context): SpeechLang {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)
        return SpeechLang.entries.firstOrNull { it.name == v } ?: SpeechLang.KO
    }

    fun setLang(ctx: Context, l: SpeechLang) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, l.name).apply()
    }

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

    fun downloadLlm(ctx: Context, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        checkSpace(ctx, LocalLlm.SIZE_MB)
        downloadUrl(LocalLlm.URL, llmFile(ctx), LocalLlm.SIZE_MB, onProgress, isCancelled)
    }

    private fun checkSpace(ctx: Context, sizeMb: Int) {
        val need = sizeMb + 300L
        val free = Device.freeStorageMb(ctx)
        if (free < need) throw IllegalStateException("저장 공간이 부족합니다. ${need}MB가 필요한데 ${free}MB 남아 있습니다.")
    }

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
        checkSpace(ctx, m.sizeMb)
        downloadUrl(m.url, file(ctx, m), m.sizeMb, onProgress, isCancelled)
    }

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
