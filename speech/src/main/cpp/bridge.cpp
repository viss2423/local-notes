#include <jni.h>
#include <whisper.h>
#include <string>
#include <vector>
#include <sstream>
#include <stdexcept>
#include <atomic>
#include <algorithm>

static std::atomic<bool> stopped{false};
struct Progress { JNIEnv *env; jobject listener; jmethodID method; };
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Speech_requestStop(JNIEnv *, jobject) { stopped.store(true); }
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Speech_resetStop(JNIEnv *, jobject) { stopped.store(false); }

static void fail(JNIEnv *env, const char *text) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_localnotes_Speech_open(JNIEnv *env, jobject, jstring path) {
    const char *value = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(value, params);
    env->ReleaseStringUTFChars(path, value);
    if (!ctx) fail(env, "Unable to load Whisper model. Import a compatible ggml English model.");
    return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT void JNICALL Java_dev_localnotes_Speech_close(JNIEnv *, jobject, jlong ptr) {
    whisper_free(reinterpret_cast<whisper_context *>(ptr));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_localnotes_Speech_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jobject listener, jint threads, jint audioContext) {
    try {
        const auto length = env->GetArrayLength(audio);
        std::vector<float> samples(length);
        env->GetFloatArrayRegion(audio, 0, length, samples.data());
        auto *ctx = reinterpret_cast<whisper_context *>(ptr);
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::max(1, std::min(4, static_cast<int>(threads)));
        params.language = "en";
        params.translate = false;
        params.no_context = true;
        // Live previews pass an encoder context sized to the clip; 0 keeps Whisper's full 30 s window.
        if (audioContext > 0) params.audio_ctx = std::min(1500, static_cast<int>(audioContext));
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.print_special = false;
        Progress progress{env, listener, env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(I)V")};
        if (!progress.method) return nullptr;
        params.progress_callback = [](whisper_context *, whisper_state *, int percent, void *data) {
            auto *p = static_cast<Progress *>(data);
            p->env->CallVoidMethod(p->listener, p->method, percent);
        };
        params.progress_callback_user_data = &progress;
        params.abort_callback = [](void *) { return stopped.load(); };
        if (stopped.load()) throw std::runtime_error("Processing stopped by user");
        if (whisper_full(ctx, params, samples.data(), length) != 0)
            throw std::runtime_error("Transcription failed; saved audio has been retained.");
        if (stopped.load()) throw std::runtime_error("Processing stopped by user");
        std::ostringstream output;
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
            std::string text = whisper_full_get_segment_text(ctx, i);
            for (auto &ch : text) if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' ';
            output << whisper_full_get_segment_t0(ctx, i) * 10 << '\t'
                   << whisper_full_get_segment_t1(ctx, i) * 10 << '\t' << text << '\n';
        }
        auto result = output.str();
        auto bytes = env->NewByteArray(result.size());
        env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
