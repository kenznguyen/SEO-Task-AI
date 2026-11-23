#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>

#include "llama.h"

namespace {
constexpr const char * TAG = "SeoTaskAI-Llama";
constexpr int32_t MAX_PROMPT_TOKENS = 4096;
constexpr int32_t MAX_GENERATION_TOKENS = 256;

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancel_requested{false};

    ~Engine() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

std::once_flag backend_once;

void throwJava(JNIEnv * env, const char * message) {
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error != nullptr) env->ThrowNew(error, message);
}

Engine * fromHandle(jlong handle) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
}

void checkCancelled(const Engine * engine) {
    if (engine->cancel_requested.load(std::memory_order_relaxed)) {
        throw std::runtime_error("Inference cancelled");
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kenznguyen_seotaskai_ai_NativeLlamaBindings_nativeLoad(
        JNIEnv * env, jobject, jstring model_path, jint context_tokens, jint threads) {
    if (model_path == nullptr) {
        throwJava(env, "Model path is null");
        return 0;
    }

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    try {
        std::call_once(backend_once, [] { llama_backend_init(); });
        auto engine = std::make_unique<Engine>();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        engine->model = llama_model_load_from_file(path, model_params);
        env->ReleaseStringUTFChars(model_path, path);
        path = nullptr;

        if (engine->model == nullptr) {
            throw std::runtime_error("llama.cpp failed to load GGUF model");
        }

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_tokens);
        context_params.n_batch = std::min<uint32_t>(context_params.n_batch, 512);
        context_params.n_ubatch = std::min<uint32_t>(context_params.n_ubatch, 512);
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        engine->context = llama_init_from_model(engine->model, context_params);
        if (engine->context == nullptr) {
            throw std::runtime_error("llama.cpp failed to create inference context");
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded GGUF with context=%d threads=%d",
                            context_tokens, threads);
        return static_cast<jlong>(reinterpret_cast<intptr_t>(engine.release()));
    } catch (const std::exception & error) {
        if (path != nullptr) env->ReleaseStringUTFChars(model_path, path);
        throwJava(env, error.what());
        return 0;
    } catch (...) {
        if (path != nullptr) env->ReleaseStringUTFChars(model_path, path);
        throwJava(env, "Unknown llama.cpp model-load failure");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kenznguyen_seotaskai_ai_NativeLlamaBindings_nativePrepare(
        JNIEnv * env, jobject, jlong handle) {
    Engine * engine = fromHandle(handle);
    if (engine == nullptr) {
        throwJava(env, "Native model is not initialized");
        return;
    }
    engine->cancel_requested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kenznguyen_seotaskai_ai_NativeLlamaBindings_nativeCancel(
        JNIEnv * env, jobject, jlong handle) {
    Engine * engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_kenznguyen_seotaskai_ai_NativeLlamaBindings_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring prompt, jint max_tokens) {
    Engine * engine = fromHandle(handle);
    if (engine == nullptr || engine->model == nullptr || engine->context == nullptr || prompt == nullptr) {
        throwJava(env, "Native model is not initialized");
        return nullptr;
    }

    const char * input = env->GetStringUTFChars(prompt, nullptr);
    if (input == nullptr) return nullptr;

    try {
        checkCancelled(engine);
        llama_memory_clear(llama_get_memory(engine->context), true);
        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        const int32_t input_length = static_cast<int32_t>(strlen(input));
        std::vector<llama_token> tokens(static_cast<size_t>(input_length) + 8);
        int32_t token_count = llama_tokenize(
                vocab, input, input_length, tokens.data(),
                static_cast<int32_t>(tokens.size()), true, false);
        env->ReleaseStringUTFChars(prompt, input);
        input = nullptr;

        if (token_count < 0) {
            throw std::runtime_error("Prompt tokenization failed");
        }
        if (token_count == 0 || token_count > MAX_PROMPT_TOKENS ||
                static_cast<uint32_t>(token_count) >= llama_n_ctx(engine->context)) {
            throw std::runtime_error("Prompt token count is outside supported bounds");
        }
        tokens.resize(static_cast<size_t>(token_count));
        const auto prefill_start = std::chrono::steady_clock::now();
        for (int32_t offset = 0; offset < token_count; offset += 512) {
            checkCancelled(engine);
            const int32_t count = std::min<int32_t>(512, token_count - offset);
            if (llama_decode(engine->context, llama_batch_get_one(tokens.data() + offset, count)) != 0) {
                throw std::runtime_error("Prompt evaluation failed");
            }
        }
        const auto prefill_end = std::chrono::steady_clock::now();

        // Qwen3 non-thinking defaults: top-k 20, top-p 0.8, temperature 0.7.
        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
                llama_sampler_chain_init(sampler_params), llama_sampler_free);
        if (sampler == nullptr) throw std::runtime_error("Could not initialize sampler chain");
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));

        std::string output;
        std::vector<char> piece(16 * 1024);
        const int32_t limit = std::clamp(static_cast<int32_t>(max_tokens), 1, MAX_GENERATION_TOKENS);
        int32_t generated_tokens = 0;
        const auto decode_start = std::chrono::steady_clock::now();
        for (int32_t i = 0; i < limit; ++i) {
            checkCancelled(engine);
            const llama_token token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;

            const int32_t piece_length = llama_token_to_piece(
                    vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            if (piece_length < 0) throw std::runtime_error("Token decoding failed");
            output.append(piece.data(), static_cast<size_t>(piece_length));
            ++generated_tokens;

            llama_token next = token;
            if (llama_decode(engine->context, llama_batch_get_one(&next, 1)) != 0) {
                throw std::runtime_error("Token evaluation failed");
            }
        }
        checkCancelled(engine);
        const auto decode_end = std::chrono::steady_clock::now();
        const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                prefill_end - prefill_start).count();
        const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                decode_end - decode_start).count();

        jclass result_class = env->FindClass(
                "com/kenznguyen/seotaskai/ai/NativeGenerationResult");
        if (result_class == nullptr) throw std::runtime_error("Cannot find NativeGenerationResult");
        jmethodID constructor = env->GetMethodID(
                result_class, "<init>", "(Ljava/lang/String;IIJJ)V");
        if (constructor == nullptr) throw std::runtime_error("Cannot find inference metrics constructor");
        jstring result_text = env->NewStringUTF(output.c_str());
        if (result_text == nullptr) throw std::runtime_error("Cannot allocate generated text");
        jobject result = env->NewObject(result_class, constructor, result_text,
                static_cast<jint>(token_count), static_cast<jint>(generated_tokens),
                static_cast<jlong>(prefill_ms), static_cast<jlong>(decode_ms));
        env->DeleteLocalRef(result_text);
        env->DeleteLocalRef(result_class);
        return result;
    } catch (const std::exception & error) {
        if (input != nullptr) env->ReleaseStringUTFChars(prompt, input);
        throwJava(env, error.what());
        return nullptr;
    } catch (...) {
        if (input != nullptr) env->ReleaseStringUTFChars(prompt, input);
        throwJava(env, "Unknown llama.cpp inference failure");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kenznguyen_seotaskai_ai_NativeLlamaBindings_nativeFree(
        JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}
