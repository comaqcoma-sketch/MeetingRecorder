package com.mplan.llm

/** llama.cpp 네이티브 연결 (src/main/cpp/llama_jni.cpp) */
object LlamaLib {
    init {
        System.loadLibrary("llamajni")
    }

    external fun loadModel(path: String, threads: Int): Long
    external fun freeModel(ptr: Long)
    external fun generate(ptr: Long, prompt: ByteArray, maxTokens: Int, nCtx: Int): ByteArray?
    external fun getProgress(): Int
    external fun abort()
    external fun resetAbort()
}
