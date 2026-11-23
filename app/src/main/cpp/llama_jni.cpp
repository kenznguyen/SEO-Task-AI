#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "SeoTaskAI-Llama";
constexpr int32_t MAX_PROMPT_TOKENS = 4096;

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;

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

extern "C" JNIEXPORT jstring JNICALL
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
        // Decode in bounded batches: n_batch is deliberately capped at 512 for phones.
        for (int32_t offset = 0; offset < token_count; offset += 512) {
            const int32_t count = std::min<int32_t>(512, token_count - offset);
            if (llama_decode(engine->context, llama_batch_get_one(tokens.data() + offset, count)) != 0) {
                throw std::runtime_error("Prompt evaluation failed");
            }
        }

        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
                llama_sampler_init_greedy(), llama_sampler_free);
        if (sampler == nullptr) throw std::runtime_error("Could not initialize sampler");

        std::string output;
        std::vector<char> piece(16 * 1024);
        const int32_t limit = std::clamp(static_cast<int32_t>(max_tokens), 1, 256);
        for (int32_t i = 0; i < limit; ++i) {
            const llama_token token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;

            const int32_t piece_length = llama_token_to_piece(
                    vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            if (piece_length < 0) throw std::runtime_error("Token decoding failed");
            output.append(piece.data(), static_cast<size_t>(piece_length));

            llama_token next = token;
            if (llama_decode(engine->context, llama_batch_get_one(&next, 1)) != 0) {
                throw std::runtime_error("Token evaluation failed");
            }
        }
        return env->NewStringUTF(output.c_str());
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
