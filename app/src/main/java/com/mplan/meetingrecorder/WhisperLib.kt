package com.mplan.meetingrecorder

/** whisper.cpp 네이티브 연결 (app/src/main/cpp/whisper_jni.cpp) */
object WhisperLib {
    init {
        System.loadLibrary("whisperjni")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    /** @param prompt 인식 힌트(UTF-8) @param beamSize 1=빠름, 2 이상=빔 서치(정확) */
    external fun transcribe(
        ptr: Long, samples: FloatArray, lang: String, threads: Int, prompt: ByteArray, beamSize: Int
    ): Array<ByteArray>?
    external fun getProgress(): Int
    external fun abort()
    external fun resetAbort()
}

/** @param speaker 화자 번호 (0=A, 1=B …, -1=구분 없음) */
data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: Int = -1) {
    /** "A: 내용" 형태 (화자 구분이 있을 때) */
    val labeled get() = if (speaker >= 0) "${SpeakerDiarizer.letter(speaker)}: $text" else text
}
