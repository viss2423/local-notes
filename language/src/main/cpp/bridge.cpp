#include <jni.h>
#include <llama.h>
#include <string>
#include <vector>
#include <memory>
#include <stdexcept>
#include <algorithm>
#include <atomic>

static std::atomic<bool> stopped{false};
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Language_requestStop(JNIEnv *, jobject) { stopped.store(true); }
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Language_resetStop(JNIEnv *, jobject) { stopped.store(false); }

static void fail(JNIEnv *env, const char *text) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_localnotes_Language_open(JNIEnv *env, jobject, jstring path) {
    llama_backend_init();
    const char *value = env->GetStringUTFChars(path, nullptr);
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    auto *model = llama_model_load_from_file(value, params);
    env->ReleaseStringUTFChars(path, value);
    if (!model) fail(env, "Unable to load the summary model. Delete it in Setup and download it again.");
    return reinterpret_cast<jlong>(model);
}
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Language_close(JNIEnv *, jobject, jlong ptr) {
    llama_model_free(reinterpret_cast<llama_model *>(ptr));
}

/** Holds a partial UTF-8 sequence so Java only ever receives complete characters. */
static size_t complete_utf8(const std::string &s) {
    size_t i = s.size(), back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : 4;
            return back + 1 >= need ? s.size() : i - 1;
        }
        --i; ++back;
    }
    return s.size();
}

/**
 * Generates a reply to an already chat-formatted prompt. Each completed piece of text is passed to
 * listener.onText(byte[]) (UTF-8) so the app can show the summary while it is written; returning false stops early.
 */
static jbyteArray bytes(JNIEnv *env, const std::string &s) {
    auto out = env->NewByteArray(s.size());
    env->SetByteArrayRegion(out, 0, s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_localnotes_Language_generate(JNIEnv *env, jobject, jlong ptr, jbyteArray input,
                                                                           jint maxTokens, jint threads, jobject listener) {
    try {
        auto *model = reinterpret_cast<llama_model *>(ptr);
        auto *vocab = llama_model_get_vocab(model);
        std::string prompt(env->GetArrayLength(input), '\0');
        env->GetByteArrayRegion(input, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 7000) throw std::runtime_error("Summary input is too long for the model; the transcript is kept.");
        std::vector<llama_token> tokens(count);
        llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
        auto params = llama_context_default_params();
        params.n_ctx = std::max(2048, count + maxTokens + 64);
        params.n_batch = 512;
        params.n_ubatch = 512;
        params.n_threads = std::max(1, (int) threads);
        params.n_threads_batch = std::max(1, (int) threads);
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model, params), llama_free);
        if (!ctx) throw std::runtime_error("Not enough memory for the summary model.");
        llama_set_abort_callback(ctx.get(), [](void *) { return stopped.load(); }, nullptr);
        for (int offset = 0; offset < count; offset += 512) {
            if (stopped.load()) throw std::runtime_error("Stopped");
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(512, count - offset));
            if (llama_decode(ctx.get(), batch)) throw std::runtime_error("Summary prompt evaluation failed.");
        }
        jmethodID onText = listener ? env->GetMethodID(env->GetObjectClass(listener), "onText", "([B)Z") : nullptr;
        // Pure greedy decoding can fall into verbatim loops on noisy transcripts. Low temperature keeps the
        // notes faithful; a mild repeat penalty and DRY (which targets repeated token sequences) break loops.
        auto chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.05f, 0.0f, 0.0f));
        static const char *breakers[] = {"\n", ":", "\"", "*"};
        llama_sampler_chain_add(chain, llama_sampler_init_dry(vocab, 0.8f, 1.75f, 2, 512, breakers, 4));
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(1234));
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(chain, llama_sampler_free);
        std::string result, pending;
        for (int i = 0; i < maxTokens; ++i) {
            if (stopped.load()) throw std::runtime_error("Stopped");
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            char piece[256];
            int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (n < 0) throw std::runtime_error("Token conversion failed.");
            pending.append(piece, n);
            size_t ready = complete_utf8(pending);
            if (ready > 0) {
                std::string out = pending.substr(0, ready);
                pending.erase(0, ready);
                result += out;
                if (onText) {
                    jbyteArray text = bytes(env, out);
                    jboolean more = env->CallBooleanMethod(listener, onText, text);
                    env->DeleteLocalRef(text);
                    if (env->ExceptionCheck()) return nullptr;
                    if (!more) break; // The app saw a repeating line; keep what was written so far.
                }
            }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx.get(), batch)) throw std::runtime_error("Summary generation failed.");
        }
        result += pending;
        return bytes(env, result);
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
