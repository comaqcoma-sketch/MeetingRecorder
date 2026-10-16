package com.mplan.meetingrecorder

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.mplan.llm.LlamaLib
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 회의 요약 (모두 폰 안에서 처리, 무료).
 * 1순위: Gemini Nano (지원 폰만, 다운로드 없음)
 * 2순위: 내장 AI Qwen2.5 1.5B (모든 폰, 최초 1회 1.1GB 다운로드)
 * 둘 다 실패하면 호출한 쪽에서 기본(규칙) 요약 사용
 */
class AiSummarizer(
    private val ctx: Context,
    private val isCancelled: () -> Boolean,
    private val onPhase: (String, Int) -> Unit
) {
    /** 엔진 공통 형태: 프롬프트 → 답 */
    private interface Engine {
        val name: String
        val chunkChars: Int
        suspend fun generate(prompt: String, maxTokens: Int): String?
        fun close() {}
    }

    // ---------- Gemini Nano ----------

    private class NanoEngine : Engine {
        override val name = "Gemini Nano"
        override val chunkChars = 2200      // 입력 한도 약 4,000토큰
        private val model = Generation.getClient()

        suspend fun prepare(): Boolean {
            return try {
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> true
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        // 기기에 Gemini Nano 받기 (구글이 관리, 앱 저장소 사용 안 함). 최대 10분 대기
                        withTimeoutOrNull(10 * 60 * 1000L) {
                            model.download().collect { st ->
                                if (st is DownloadStatus.DownloadFailed) throw IllegalStateException("download failed")
                            }
                        }
                        model.checkStatus() == FeatureStatus.AVAILABLE
                    }
                    else -> false
                }
            } catch (_: Throwable) { false }
        }

        override suspend fun generate(prompt: String, maxTokens: Int): String? = try {
            val resp = model.generateContent(generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                maxOutputTokens = maxTokens
            })
            resp.candidates.firstOrNull()?.text
        } catch (_: Throwable) { null }

        override fun close() { try { model.close() } catch (_: Throwable) { } }
    }

    // ---------- 내장 AI (llama.cpp) ----------

    private class LocalEngine(private val handle: Long) : Engine {
        override val name = "내장 AI (${LocalLlm.NAME})"
        override val chunkChars = 2000      // 컨텍스트 4,096토큰 안에 들어가도록
        override suspend fun generate(prompt: String, maxTokens: Int): String? {
            val chat = "<|im_start|>system\n$SYSTEM<|im_end|>\n" +
                    "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return LlamaLib.generate(handle, chat.toByteArray(Charsets.UTF_8), maxTokens, 4096)
                ?.toString(Charsets.UTF_8)
        }
        override fun close() { LlamaLib.freeModel(handle) }
    }

    /** 결과: (마크다운 본문, 사용한 엔진 이름). 실패 시 null */
    suspend fun summarize(segments: List<Segment>): Pair<String, String>? {
        val text = segments.joinToString("\n") { it.text.trim() }.trim()
        if (text.length < 30) return null

        // 1) Gemini Nano
        onPhase("AI 요약 준비 중", 0)
        val nano = NanoEngine()
        if (nano.prepare()) {
            val r = try { process(nano, text) } finally { nano.close() }
            if (r != null) return r to nano.name
        } else nano.close()
        if (isCancelled()) return null

        // 2) 내장 AI
        if (!ModelManager.isLlmDownloaded(ctx)) {
            val label = "AI 요약 모델 다운로드 (최초 1회, ${LocalLlm.SIZE_MB}MB)"
            onPhase(label, 0)
            ModelManager.downloadLlm(ctx, { p -> onPhase(label, p) }, isCancelled)
        }
        if (isCancelled()) return null
        onPhase("AI 요약 엔진 준비 중", 0)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        LlamaLib.resetAbort()
        val handle = LlamaLib.loadModel(ModelManager.llmFile(ctx).absolutePath, threads)
        if (handle == 0L) {
            ModelManager.llmFile(ctx).delete()   // 손상 파일이면 다음에 다시 받기
            return null
        }
        val local = LocalEngine(handle)
        return try { process(local, text)?.let { it to local.name } } finally { local.close() }
    }

    fun cancel() { try { LlamaLib.abort() } catch (_: Throwable) { } }

    /** 긴 회의: 부분별 메모(map) → 합쳐서 최종 정리(reduce) */
    private suspend fun process(engine: Engine, text: String): String? {
        var parts = split(text, engine.chunkChars)
        if (parts.size == 1) {
            onPhase("AI 요약 중", 10)
            return engine.generate(finalPrompt(parts[0], isNotes = false), 700)?.let { clean(it) }
        }
        var round = 0
        while (true) {
            round++
            val notes = ArrayList<String>()
            for ((i, p) in parts.withIndex()) {
                if (isCancelled()) return null
                onPhase("AI 요약 중 (${i + 1}/${parts.size}${if (round > 1) " · ${round}차" else ""})",
                    i * 90 / parts.size)
                val n = engine.generate(notesPrompt(p), 400) ?: return null
                notes += n.trim()
            }
            val joined = notes.joinToString("\n\n")
            if (joined.length <= engine.chunkChars || round >= 3) {
                if (isCancelled()) return null
                onPhase("AI 요약 마무리 중", 92)
                val input = if (joined.length > engine.chunkChars) joined.take(engine.chunkChars) else joined
                return engine.generate(finalPrompt(input, isNotes = true), 700)?.let { clean(it) }
            }
            parts = split(joined, engine.chunkChars)
        }
    }

    private fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.lines()) {
            if (sb.length + line.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (line.length > max) {
                line.chunked(max).forEach { c -> if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }; out += c }
            } else sb.append(line).append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    /** 모델이 형식을 조금 어겨도 화면에 잘 보이게 정리 */
    private fun clean(s: String): String? {
        val t = s.replace("```markdown", "").replace("```", "").replace("**", "").trim()
        if (t.length < 10) return null
        return t.lines().joinToString("\n") { line ->
            val l = line.trimEnd()
            when {
                l.startsWith("### ") -> "## " + l.removePrefix("### ")
                l.startsWith("* ") -> "- " + l.removePrefix("* ")
                l.startsWith("• ") -> "- " + l.removePrefix("• ")
                else -> l
            }
        }
    }

    companion object {
        private const val SYSTEM =
            "너는 회의록을 정리하는 비서다. 반드시 한국어로 쓰고, 주어진 내용에 있는 사실만 사용한다. 추측해서 지어내지 않는다."

        private fun notesPrompt(chunk: String) = """
            다음은 회의 녹음을 음성 인식으로 받아 적은 글의 일부다. 오타가 있을 수 있다.

            <<<
            $chunk
            >>>

            이 부분의 중요한 내용을 짧은 메모로 정리해라. 형식:
            논의: (핵심 내용 2~5줄)
            결정: (결정된 것. 없으면 없음)
            할 일: (누가 무엇을 언제까지. 없으면 없음)
        """.trimIndent()

        private fun finalPrompt(input: String, isNotes: Boolean) = """
            다음은 ${if (isNotes) "회의 내용을 부분별로 정리한 메모" else "회의 녹음을 음성 인식으로 받아 적은 글(오타가 있을 수 있음)"}다.

            <<<
            $input
            >>>

            이 회의의 회의록을 아래 형식 그대로 작성해라. 해당 내용이 없으면 "- 없음"이라고 써라.

            ## 한 줄 요약
            (회의 전체를 한 문장으로)

            ## 핵심 내용
            - (중요한 논의 내용 3~7개)

            ## 결정사항
            - (회의에서 정해진 것)

            ## 할 일
            - [ ] (담당자: 할 일, 기한)
        """.trimIndent()
    }
}
