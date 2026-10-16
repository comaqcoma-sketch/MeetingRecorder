// llama.cpp ↔ Kotlin 연결 (com.mplan.llm.LlamaLib)
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

static std::atomic<bool> g_abort{false};
static std::atomic<int> g_progress{0};

struct Handle {
    llama_model *model;
    int threads;
};

static jbyteArray toBytes(JNIEnv *env, const std::string &s) {
    jbyteArray b = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(b, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return b;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mplan_llm_LlamaLib_loadModel(JNIEnv *env, jobject, jstring path, jint threads) {
    llama_backend_init();
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) return 0;
    auto *h = new Handle{model, threads};
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_llm_LlamaLib_freeModel(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h) return;
    if (h->model) llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_llm_LlamaLib_abort(JNIEnv *, jobject) { g_abort.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_com_mplan_llm_LlamaLib_resetAbort(JNIEnv *, jobject) { g_abort.store(false); }

extern "C" JNIEXPORT jint JNICALL
Java_com_mplan_llm_LlamaLib_getProgress(JNIEnv *, jobject) { return g_progress.load(); }

// prompt(UTF-8 bytes) → 생성 결과(UTF-8 bytes). 실패 시 null
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mplan_llm_LlamaLib_generate(JNIEnv *env, jobject, jlong ptr, jbyteArray promptBytes,
                                    jint maxTokens, jint nCtx) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h || !h->model) return nullptr;
    g_progress.store(0);

    jsize plen = env->GetArrayLength(promptBytes);
    std::string prompt((size_t) plen, '\0');
    env->GetByteArrayRegion(promptBytes, 0, plen, reinterpret_cast<jbyte *>(&prompt[0]));

    const llama_vocab *vocab = llama_model_get_vocab(h->model);

    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true) < 0)
        return nullptr;
    if (n + maxTokens > nCtx) return nullptr;  // 입력이 너무 김 (Kotlin에서 더 잘게 나눔)

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = h->threads;
    cp.n_threads_batch = h->threads;
    llama_context *ctx = llama_init_from_model(h->model, cp);
    if (!ctx) return nullptr;

    std::string out;
    bool ok = true;

    // 1) 입력 읽기 (진행률 0~70%)
    for (int i = 0; i < n && ok; i += 512) {
        if (g_abort.load()) { ok = false; break; }
        int cnt = std::min(512, n - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cnt);
        if (llama_decode(ctx, batch) != 0) ok = false;
        g_progress.store((int) ((long long) (i + cnt) * 70 / n));
    }

    // 2) 답 생성 (진행률 70~100%)
    if (ok) {
        llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        char buf[256];
        for (int i = 0; i < maxTokens; i++) {
            if (g_abort.load()) { ok = false; break; }
            llama_token tok = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
            if (len > 0) out.append(buf, (size_t) len);
            llama_batch b = llama_batch_get_one(&tok, 1);
            if (llama_decode(ctx, b) != 0) break;
            g_progress.store(70 + i * 30 / maxTokens);
        }
        llama_sampler_free(smpl);
    }
    llama_free(ctx);
    if (!ok) return nullptr;
    g_progress.store(100);
    return toBytes(env, out);
}
