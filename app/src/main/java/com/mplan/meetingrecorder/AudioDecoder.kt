package com.mplan.meetingrecorder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * 녹음 파일(m4a 등)을 Whisper 입력 형식(16kHz 모노 float)으로 변환.
 * 1시간 회의도 메모리가 넘치지 않도록 일정 길이(chunk)씩 잘라서 넘겨줌.
 */
object AudioDecoder {
    const val TARGET_RATE = 16000

    /** 파일 길이(ms). 실패 시 0 */
    fun durationMs(path: String): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            val i = findAudioTrack(ex)
            if (i < 0) 0L else {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            }
        } catch (_: Exception) { 0L } finally { ex.release() }
    }

    /**
     * @param chunkSeconds 한 번에 넘길 길이(초)
     * @param onChunk (오디오, 시작 위치 ms) → false 반환 시 중단
     */
    fun decode(path: String, chunkSeconds: Int, onChunk: (FloatArray, Long) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = findAudioTrack(ex)
        require(track >= 0) { "오디오 트랙을 찾을 수 없습니다" }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val chunkSize = chunkSeconds * TARGET_RATE
        var chunk = FloatArray(chunkSize)
        var filled = 0
        var emittedTotal = 0L
        var stop = false

        // 선형 보간 리샘플러 상태
        var ratio = srcRate.toDouble() / TARGET_RATE
        var t = 0.0          // 다음 출력 샘플의 원본 위치
        var k = 0L           // 다음 원본 샘플 번호
        var prev = 0f

        fun emit(v: Float) {
            if (stop) return
            chunk[filled++] = v
            if (filled == chunkSize) {
                val startMs = emittedTotal * 1000 / TARGET_RATE
                if (!onChunk(chunk, startMs)) stop = true
                emittedTotal += filled
                chunk = FloatArray(chunkSize)
                filled = 0
            }
        }

        fun push(s: Float) {
            if (k == 0L) {
                prev = s
                while (t <= 0.0) { emit(s); t += ratio }
                k = 1
                return
            }
            while (t <= k) {
                val frac = (t - (k - 1)).toFloat()
                emit(prev + (s - prev) * frac)
                t += ratio
            }
            prev = s
            k++
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && !stop) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        ratio = srcRate.toDouble() / TARGET_RATE
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bb = buf.slice().order(ByteOrder.nativeOrder())
                            if (isFloat) {
                                val fb = bb.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get()
                                    push(sum / channels)
                                }
                            } else {
                                val sb = bb.asShortBuffer()
                                val frames = sb.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += sb.get()
                                    push(sum / (channels * 32768f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            // 마지막 남은 조각 (0.5초 이상일 때만)
            if (!stop && filled > TARGET_RATE / 2) {
                val startMs = emittedTotal * 1000 / TARGET_RATE
                onChunk(chunk.copyOf(filled), startMs)
            }
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
            ex.release()
        }
    }

    private fun findAudioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
