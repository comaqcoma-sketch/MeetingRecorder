// whisper.cpp ↔ Kotlin 연결 (com.mplan.meetingrecorder.WhisperLib)
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

static std::atomic<int> g_progress{0};
static std::atomic<bool> g_abort{false};

static void on_progress(whisper_context *, whisper_state *, int progress, void *) {
    g_progress.store(progress);
}

static bool on_abort(void *) {
    return g_abort.load();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_getProgress(JNIEnv *, jobject) {
    return g_progress.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_abort(JNIEnv *, jobject) {
    g_abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_resetAbort(JNIEnv *, jobject) {
    g_abort.store(false);
}

// 결과: 세그먼트마다 "시작ms\t끝ms\t텍스트" UTF-8 바이트 배열 (한글 깨짐 방지로 Kotlin에서 디코딩)
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mplan_meetingrecorder_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr,
                                                     jfloatArray samples, jstring lang, jint threads,
                                                     jbyteArray promptBytes, jint beamSize) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return nullptr;

    const char *langC = env->GetStringUTFChars(lang, nullptr);
    std::string language(langC);
    env->ReleaseStringUTFChars(lang, langC);

    // 힌트 문장 (회의 주제·이름·전문용어 → 인식 정확도 향상)
    std::string prompt;
    if (promptBytes) {
        jsize pl = env->GetArrayLength(promptBytes);
        prompt.resize((size_t) pl);
        if (pl > 0) env->GetByteArrayRegion(promptBytes, 0, pl, reinterpret_cast<jbyte *>(&prompt[0]));
    }

    // 빔 서치: 여러 후보 문장을 비교해서 더 정확한 쪽을 고름 (조금 느려짐)
    whisper_full_params wp = whisper_full_default_params(
        beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beamSize > 1) {
        wp.beam_search.beam_size = beamSize;
        wp.greedy.best_of = beamSize;
    }
    if (!prompt.empty()) wp.initial_prompt = prompt.c_str();
    wp.n_threads = threads;
    wp.language = language.c_str();
    wp.translate = false;
    wp.no_context = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.suppress_blank = true;
    wp.progress_callback = on_progress;
    wp.abort_callback = on_abort;

    g_progress.store(0);

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int ret = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (ret != 0) return nullptr;

    int ns = whisper_full_n_segments(ctx);
    jclass byteArrCls = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(ns, byteArrCls, nullptr);
    for (int i = 0; i < ns; i++) {
        long long t0 = (long long) whisper_full_get_segment_t0(ctx, i) * 10;
        long long t1 = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);
        std::string s = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + (text ? text : "");
        jbyteArray b = env->NewByteArray((jsize) s.size());
        env->SetByteArrayRegion(b, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
        env->SetObjectArrayElement(out, i, b);
        env->DeleteLocalRef(b);
    }
    return out;
}
