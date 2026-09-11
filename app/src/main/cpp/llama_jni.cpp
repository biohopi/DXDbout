#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct NativeModelWrapper {
    llama_model* model = nullptr;
    const llama_vocab* vocab = nullptr;
    std::string model_path;
};

struct NativeContextWrapper {
    NativeModelWrapper* model_wrapper = nullptr;
    llama_context* ctx = nullptr;
    int n_ctx = 2048;
    int n_threads = 4;
};

extern "C" {

JNIEXPORT void JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeInitBackend(JNIEnv* env, jclass clazz) {
    LOGI("Initializing llama backend");
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeLoadModel(
    JNIEnv* env, jclass clazz, jstring jmodel_path, jboolean use_vulkan, jint n_gpu_layers) {

    const char* path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGI("Loading model from: %s, Vulkan: %d, GPU layers: %d", path, use_vulkan, n_gpu_layers);

    llama_model_params mparams = llama_model_default_params();
    if (use_vulkan) {
        mparams.n_gpu_layers = n_gpu_layers > 0 ? n_gpu_layers : 99;
    } else {
        mparams.n_gpu_layers = 0;
    }

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jmodel_path, path);

    if (!model) {
        LOGE("Failed to load llama model from file");
        return 0;
    }

    auto* wrapper = new NativeModelWrapper();
    wrapper->model = model;
    wrapper->vocab = llama_model_get_vocab(model);
    wrapper->model_path = path ? path : "";

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jlong JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeCreateContext(
    JNIEnv* env, jclass clazz, jlong model_handle, jint n_ctx, jint n_threads) {

    auto* wrapper = reinterpret_cast<NativeModelWrapper*>(model_handle);
    if (!wrapper || !wrapper->model) {
        LOGE("Invalid model wrapper handle");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.offload_kqv = true;

    llama_context* ctx = llama_init_from_model(wrapper->model, cparams);
    if (!ctx) {
        LOGE("Failed to create context from model");
        return 0;
    }

    auto* ctx_wrapper = new NativeContextWrapper();
    ctx_wrapper->model_wrapper = wrapper;
    ctx_wrapper->ctx = ctx;
    ctx_wrapper->n_ctx = cparams.n_ctx;
    ctx_wrapper->n_threads = cparams.n_threads;

    LOGI("Context created successfully. n_ctx=%d, n_threads=%d", cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(ctx_wrapper);
}

JNIEXPORT jint JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeGenerateStream(
    JNIEnv* env, jclass clazz, jlong ctx_handle, jstring jprompt, jint max_tokens,
    jfloat temp, jfloat top_p, jfloat repeat_penalty, jobject callback) {

    auto* ctx_wrapper = reinterpret_cast<NativeContextWrapper*>(ctx_handle);
    if (!ctx_wrapper || !ctx_wrapper->ctx || !ctx_wrapper->model_wrapper) {
        LOGE("Invalid context wrapper handle");
        return -1;
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID is_canceled_method = env->GetMethodID(callback_class, "isCanceled", "()Z");

    if (!on_token_method) {
        LOGE("Could not find onToken callback method");
        return -1;
    }

    const char* prompt_str = env->GetStringUTFChars(jprompt, nullptr);
    const llama_vocab* vocab = ctx_wrapper->model_wrapper->vocab;
    llama_context* ctx = ctx_wrapper->ctx;

    int n_tokens_alloc = strlen(prompt_str) + 16;
    std::vector<llama_token> tokens(n_tokens_alloc);
    int n_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), true, true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), true, true);
    }

    env->ReleaseStringUTFChars(jprompt, prompt_str);

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt");
        return -1;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (repeat_penalty > 1.001f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (top_p < 1.0f && top_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }
    if (temp > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1337));

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_tokens - 1);
    }
    batch.n_tokens = n_tokens;

    if (llama_decode(ctx, batch) != 0) {
        LOGE("llama_decode prompt failed");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return -1;
    }

    int generated_count = 0;
    int n_past = n_tokens;

    while (generated_count < max_tokens) {
        if (is_canceled_method) {
            jboolean canceled = env->CallBooleanMethod(callback, is_canceled_method);
            if (canceled) {
                LOGI("Generation canceled by user callback");
                break;
            }
        }

        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char buf[256];
        int n_chars = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            std::string piece_str(buf, n_chars);
            jstring jpiece = env->NewStringUTF(piece_str.c_str());
            env->CallVoidMethod(callback, on_token_method, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        generated_count++;

        batch.token[0] = new_token;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        batch.n_tokens = 1;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("llama_decode step failed at token %d", generated_count);
            break;
        }
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    return generated_count;
}

JNIEXPORT void JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeFreeContext(
    JNIEnv* env, jclass clazz, jlong ctx_handle) {

    auto* ctx_wrapper = reinterpret_cast<NativeContextWrapper*>(ctx_handle);
    if (ctx_wrapper) {
        if (ctx_wrapper->ctx) {
            llama_free(ctx_wrapper->ctx);
        }
        delete ctx_wrapper;
        LOGI("Context freed");
    }
}

JNIEXPORT void JNICALL
Java_com_turbodns_gguf_chat_engine_LlamaNative_nativeFreeModel(
    JNIEnv* env, jclass clazz, jlong model_handle) {

    auto* wrapper = reinterpret_cast<NativeModelWrapper*>(model_handle);
    if (wrapper) {
        if (wrapper->model) {
            llama_model_free(wrapper->model);
        }
        delete wrapper;
        LOGI("Model freed");
    }
}

} // extern "C"
