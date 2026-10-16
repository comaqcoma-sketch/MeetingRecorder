package com.mplan.meetingrecorder

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 화자 구분용 목소리 특징 모델 (한국어 포함 다국어 음성에 사용, 최초 1회 다운로드) */
object SpeakerModel {
    const val FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    const val SIZE_MB = 27
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$FILE"
}

/**
 * 화자 구분 (폰 안에서 처리).
 * 문장마다 목소리 특징(임베딩)을 뽑고, 비슷한 목소리끼리 묶어서 A, B, C… 로 표시.
 */
class SpeakerDiarizer(modelPath: String, threads: Int) {

    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(
            model = modelPath, numThreads = threads, debug = false, provider = "cpu"
        )
    )

    /** samples[from, to) 구간의 목소리 특징. 너무 짧으면 주변을 포함해 1.5초로 늘림 */
    fun embed(samples: FloatArray, from: Int, to: Int): FloatArray? {
        var s = from.coerceIn(0, samples.size)
        var e = to.coerceIn(0, samples.size)
        val minLen = AudioDecoder.TARGET_RATE * 3 / 2
        if (e - s < minLen) {
            val c = (s + e) / 2
            s = max(0, c - minLen / 2)
            e = min(samples.size, s + minLen)
        }
        if (e - s < AudioDecoder.TARGET_RATE / 2) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples.copyOfRange(s, e), AudioDecoder.TARGET_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else normalize(extractor.compute(stream))
        } catch (_: Throwable) {
            null
        } finally {
            stream.release()
        }
    }

    fun release() {
        try { extractor.release() } catch (_: Throwable) { }
    }

    companion object {
        private const val THRESHOLD = 0.5f   // 이보다 비슷하면 같은 사람

        private fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n)
            if (n <= 0f) return v
            return FloatArray(v.size) { v[it] / n }
        }

        private fun cos(a: FloatArray, b: FloatArray): Float {
            var d = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na <= 0f || nb <= 0f) 0f else d / (sqrt(na) * sqrt(nb))
        }

        /**
         * 문장별 목소리 특징 → 화자 번호(0=A, 1=B …). 특징이 없는 문장은 앞 문장 화자를 따름.
         * @param numSpeakers 0=자동, 2 이상=참석자 수 고정
         * @param durationsMs 문장 길이 (아주 짧게 튀는 화자 정리에 사용)
         * @return 한 사람뿐이면 null
         */
        fun cluster(embs: List<FloatArray?>, durationsMs: List<Long>, numSpeakers: Int): IntArray? {
            val idx = embs.indices.filter { embs[it] != null }
            val n = idx.size
            if (n < 2) return null

            // 각 문장을 하나의 묶음으로 시작 → 가장 비슷한 묶음끼리 합치기
            val sum = Array(n) { embs[idx[it]]!!.copyOf() }
            val members = Array(n) { mutableListOf(it) }
            val alive = BooleanArray(n) { true }
            val sim = Array(n) { FloatArray(n) }
            for (i in 0 until n) for (j in i + 1 until n) {
                val v = cos(sum[i], sum[j]); sim[i][j] = v; sim[j][i] = v
            }
            var count = n
            val target = if (numSpeakers >= 2) numSpeakers else 0
            while (count > 1) {
                var bi = -1; var bj = -1; var best = -2f
                for (i in 0 until n) {
                    if (!alive[i]) continue
                    val row = sim[i]
                    for (j in i + 1 until n) {
                        if (alive[j] && row[j] > best) { best = row[j]; bi = i; bj = j }
                    }
                }
                if (bi < 0) break
                if (target > 0) { if (count <= target) break }
                else if (best < THRESHOLD) break
                // bj → bi 합치기
                val a = sum[bi]; val b = sum[bj]
                for (k in a.indices) a[k] += b[k]
                members[bi].addAll(members[bj])
                alive[bj] = false
                count--
                for (k in 0 until n) if (alive[k] && k != bi) {
                    val v = cos(a, sum[k]); sim[bi][k] = v; sim[k][bi] = v
                }
            }

            // 자동 모드: 말한 시간이 아주 짧은 묶음(잡음·기침 등)은 가장 비슷한 묶음에 합침
            if (target == 0) {
                val total = idx.sumOf { durationsMs[it] }.coerceAtLeast(1L)
                var changed = true
                while (changed && count > 1) {
                    changed = false
                    for (c in 0 until n) {
                        if (!alive[c]) continue
                        val dur = members[c].sumOf { durationsMs[idx[it]] }
                        if (dur < 8_000 && dur * 20 < total) {
                            var to = -1; var bs = -2f
                            for (k in 0 until n) if (alive[k] && k != c && cos(sum[c], sum[k]) > bs) {
                                bs = cos(sum[c], sum[k]); to = k
                            }
                            if (to >= 0) {
                                for (q in sum[to].indices) sum[to][q] += sum[c][q]
                                members[to].addAll(members[c]); alive[c] = false; count--; changed = true
                            }
                        }
                    }
                }
            }
            if (count <= 1) return null

            // 처음 말한 순서대로 A, B, C…
            val label = IntArray(embs.size) { -1 }
            val clusterOf = IntArray(n)
            for (c in 0 until n) if (alive[c]) members[c].forEach { clusterOf[it] = c }
            val order = HashMap<Int, Int>()
            for (m in 0 until n) {
                val c = clusterOf[m]
                if (c !in order) order[c] = order.size
                label[idx[m]] = order[c]!!
            }
            // 특징 없는 문장은 앞 문장 화자 (맨 앞이면 다음 문장)
            var last = -1
            for (i in label.indices) { if (label[i] >= 0) last = label[i] else if (last >= 0) label[i] = last }
            var next = -1
            for (i in label.indices.reversed()) { if (label[i] >= 0) next = label[i] else label[i] = next }
            return label
        }

        fun letter(i: Int): String = if (i < 0) "" else if (i < 26) ('A' + i).toString() else "S${i + 1}"
    }
}
