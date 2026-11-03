// JNI bridge between Locus (dev.locus.core.ondevice.gguf.LlamaNative) and llama.cpp.
//
// One loaded model is one Session: the model (memory-mapped weights), one context (its KV cache) and
// the model's chat templates. The prompt is rendered by llama.cpp's own Jinja engine from the GGUF's
// chat template (common/chat.h), with the chat's tools when the model's template takes them. The same
// session constrains tool calls with the template's lazy grammar and parses the reply into content,
// reasoning and tool calls, exactly as llama-server does. See docs/design/llamacpp.md.
//
// Threading: Kotlin holds one lock per loaded model, so load, generate and free never overlap for a
// session. cancel() is the only call made from another thread; it sets an atomic flag that the decode
// loop and llama.cpp's abort callback read.
//
// Strings cross JNI as UTF-8 byte arrays: NewStringUTF takes "modified UTF-8", which mangles emoji.
//
// No C++ exception may leave a JNI function (it would abort the app): llama.cpp's common library
// throws (a grammar that doesn't parse in common_sampler_init, "Unexpected empty grammar stack" from
// the grammar sampler, JSON errors), so each entry point turns whatever escapes into a Java exception.

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
static void __android_log_write(int prio, const char * tag, const char * text) {
    if (prio >= ANDROID_LOG_WARN) fprintf(stderr, "%s: %s", tag, text);
}
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "json.h"
#include "llama.h"
#include "sampling.h"

#define TAG "LocusLlama"

namespace {

// Error kinds, the first line of a LlamaNativeException's message (LlamaNative.Kind in Kotlin).
constexpr const char * KIND_LOAD        = "load";
constexpr const char * KIND_ARCH        = "arch";
constexpr const char * KIND_CORRUPT     = "corrupt";
constexpr const char * KIND_MEMORY      = "memory";
constexpr const char * KIND_CONTEXT     = "context";
constexpr const char * KIND_TEMPLATE    = "template";
constexpr const char * KIND_GENERATE    = "generate";

// The last warnings and errors llama.cpp logged, to explain a failed load in its own words.
std::mutex g_log_mutex;
std::deque<std::string> g_recent_errors;

void log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    if (text == nullptr) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (line.empty()) return;
        g_recent_errors.push_back(line);
        while (g_recent_errors.size() > 12) g_recent_errors.pop_front();
    }
}

void clear_recent_errors() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_recent_errors.clear();
}

std::string recent_errors() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string out;
    for (const auto & line : g_recent_errors) {
        if (!out.empty()) out += "\n";
        out += line;
    }
    return out;
}

bool contains(const std::string & haystack, const char * needle) {
    return haystack.find(needle) != std::string::npos;
}

// Which kind of failure a load was, from what llama.cpp logged (src/llama-model-loader.cpp, llama-arch.cpp).
const char * classify_load_failure(const std::string & log) {
    if (contains(log, "unknown model architecture") || contains(log, "unsupported model architecture")) return KIND_ARCH;
    if (contains(log, "failed to allocate") || contains(log, "out of memory") || contains(log, "unable to allocate") ||
        contains(log, "std::bad_alloc")) return KIND_MEMORY;
    if (contains(log, "invalid magic") || contains(log, "not within the file bounds") || contains(log, "failed to read") ||
        contains(log, "gguf_init_from_file") || contains(log, "corrupted") || contains(log, "invalid ggml type") ||
        contains(log, "tensor data is not within") || contains(log, "wrong number of tensors")) return KIND_CORRUPT;
    return KIND_LOAD;
}

void throw_java(JNIEnv * env, const char * kind, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("dev/locus/core/ondevice/gguf/LlamaNativeException");
    if (cls == nullptr) return; // NoClassDefFoundError is already pending
    std::string text = std::string(kind) + "\n" + message;
    // The message may contain any UTF-8; keep it ASCII-safe for ThrowNew's modified UTF-8.
    std::string safe;
    safe.reserve(text.size());
    for (unsigned char c : text) safe += (c < 0x80) ? static_cast<char>(c) : '?';
    env->ThrowNew(cls, safe.c_str());
}

std::string from_bytes(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

std::string from_jstring(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Length of the longest prefix of s that ends on a complete UTF-8 sequence. A token can end in the
// middle of a multi-byte character; the rest comes with the next token.
size_t complete_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    // Look back at most 3 bytes for the lead byte of an unfinished sequence.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

struct Session {
    llama_model *                model = nullptr;
    llama_context *              ctx   = nullptr;
    const llama_vocab *          vocab = nullptr;
    common_chat_templates_ptr    templates;
    // Tokens whose keys and values are in the KV cache, in order (sequence 0). The next prompt reuses
    // the longest common prefix, so an agent step only prefills what's new.
    std::vector<llama_token>     cached;
    std::atomic<bool>            cancel{false};
    int                          n_batch = 512;
};

bool abort_callback(void * data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

void free_session(Session * s) {
    if (s == nullptr) return;
    s->templates.reset();
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

// What generate() reports on each streamed piece (LlamaNative.Sink in Kotlin).
constexpr jint DELTA_CONTENT   = 0;
constexpr jint DELTA_REASONING = 1;
constexpr jint DELTA_TOOL      = 2;

struct Sink {
    JNIEnv *  env;
    jobject   target;
    jmethodID on_delta;

    bool send(jint kind, const std::string & text) {
        if (text.empty()) return true;
        jbyteArray bytes = to_bytes(env, text);
        if (bytes == nullptr) return false;
        env->CallVoidMethod(target, on_delta, kind, bytes);
        env->DeleteLocalRef(bytes);
        return !env->ExceptionCheck();
    }
};

// Emits what a newly parsed message adds to the last one: new content, new reasoning, the name of
// each tool call as soon as it's known. A partial parse can rewrite earlier text (a "<tool_call>"
// that turned out to be prose); then nothing is sent for that field and the final message is the truth.
struct Streamer {
    std::string content;
    std::string reasoning;
    size_t      tools_announced = 0;

    bool update(Sink & sink, const common_chat_msg & msg) {
        if (msg.reasoning_content.size() > reasoning.size() && msg.reasoning_content.compare(0, reasoning.size(), reasoning) == 0) {
            if (!sink.send(DELTA_REASONING, msg.reasoning_content.substr(reasoning.size()))) return false;
            reasoning = msg.reasoning_content;
        }
        if (msg.content.size() > content.size() && msg.content.compare(0, content.size(), content) == 0) {
            if (!sink.send(DELTA_CONTENT, msg.content.substr(content.size()))) return false;
            content = msg.content;
        }
        while (tools_announced < msg.tool_calls.size() && !msg.tool_calls[tools_announced].name.empty()) {
            // A name is only final once a later call starts or the message is done; announce the
            // earlier ones now and the last one at the end.
            if (tools_announced + 1 == msg.tool_calls.size()) break;
            if (!sink.send(DELTA_TOOL, msg.tool_calls[tools_announced].name)) return false;
            tools_announced++;
        }
        return true;
    }

    bool finish(Sink & sink, const common_chat_msg & msg) {
        if (!update(sink, msg)) return false;
        while (tools_announced < msg.tool_calls.size()) {
            if (!sink.send(DELTA_TOOL, msg.tool_calls[tools_announced].name)) return false;
            tools_announced++;
        }
        return true;
    }
};

// Tokenises the rendered prompt part by part: the template's own text may contain special tokens
// (<|im_start|>), the user's and tools' text may not, so a web page can't forge a turn boundary.
std::vector<llama_token> tokenize_prompt(const Session & s, const common_chat_prompt & prompt, bool add_bos) {
    std::vector<llama_token> tokens;
    if (add_bos) tokens.push_back(llama_vocab_bos(s.vocab));
    for (const auto & part : prompt.parts) {
        if (part.text.empty()) continue;
        auto piece = common_tokenize(s.vocab, part.text, /* add_special */ false, /* parse_special */ !part.is_input);
        tokens.insert(tokens.end(), piece.begin(), piece.end());
    }
    return tokens;
}

double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeInit(JNIEnv * env, jclass, jstring native_lib_dir) {
    try {
        llama_log_set(log_callback, nullptr);
        const std::string dir = from_jstring(env, native_lib_dir);
        // GGML_BACKEND_DL: the CPU backend comes in variants (armv8.0 … armv9.2 with SME); ggml loads
        // every libggml-cpu-*.so in the folder and keeps the best one this CPU supports.
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        std::string info = llama_print_system_info();
        return env->NewStringUTF(info.c_str()); // ASCII only
    } catch (const std::exception & e) {
        throw_java(env, KIND_LOAD, std::string("llama.cpp couldn't start: ") + e.what());
    } catch (...) {
        throw_java(env, KIND_LOAD, "llama.cpp couldn't start.");
    }
    return nullptr;
}

JNIEXPORT jlong JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads, jint n_batch) {
    const std::string path = from_jstring(env, jpath);
    clear_recent_errors();

    auto * s = new Session();
    s->n_batch = std::max(32, static_cast<int>(n_batch));

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                    // CPU only in v1 (docs/design/llamacpp.md)
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP; // weights stay in the page cache, not the app's heap
    try {
        s->model = llama_model_load_from_file(path.c_str(), mp);
    } catch (const std::bad_alloc &) {
        free_session(s);
        throw_java(env, KIND_MEMORY, "Not enough memory to load the model.");
        return 0;
    } catch (const std::exception & e) {
        free_session(s);
        const std::string log = recent_errors() + "\n" + e.what();
        throw_java(env, classify_load_failure(log), log);
        return 0;
    }
    if (s->model == nullptr) {
        const std::string log = recent_errors();
        free_session(s);
        throw_java(env, classify_load_failure(log), log.empty() ? "llama.cpp couldn't open the file." : log);
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = static_cast<uint32_t>(s->n_batch);
    cp.n_ubatch        = static_cast<uint32_t>(std::min(s->n_batch, 512));
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf         = false;
    cp.abort_callback      = abort_callback;
    cp.abort_callback_data = s;
    try {
        s->ctx = llama_init_from_model(s->model, cp);
    } catch (const std::bad_alloc &) {
        s->ctx = nullptr;
    } catch (const std::exception & e) {
        log_callback(GGML_LOG_LEVEL_ERROR, e.what(), nullptr);
        s->ctx = nullptr;
    }
    if (s->ctx == nullptr) {
        const std::string log = recent_errors();
        free_session(s);
        // A context that can't be created is almost always the KV cache not fitting.
        const char * kind = classify_load_failure(log);
        throw_java(env, kind == KIND_LOAD ? KIND_MEMORY : kind, log.empty() ? "Couldn't allocate the model's context." : log);
        return 0;
    }

    try {
        // The GGUF's own template (tokenizer.chat_template); llama.cpp falls back to ChatML without one.
        s->templates = common_chat_templates_init(s->model, "");
    } catch (const std::exception & e) {
        free_session(s);
        throw_java(env, KIND_TEMPLATE, std::string("The model's chat template couldn't be read: ") + e.what());
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

// What the loaded model is, as JSON: its context, its template's abilities as llama.cpp detected them
// by rendering it, and whether the GGUF had a template at all.
} // extern "C"

namespace {

jbyteArray info_json(JNIEnv * env, Session * s) {
    common_json out = common_json::object();
    out["n_ctx"]       = static_cast<int64_t>(llama_n_ctx(s->ctx));
    out["n_ctx_train"] = static_cast<int64_t>(llama_model_n_ctx_train(s->model));
    out["has_template"] = common_chat_templates_was_explicit(s->templates.get());
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    out["desc"] = std::string(desc);
    try {
        out["caps"] = common_chat_templates_get_caps(s->templates.get());
    } catch (const std::exception &) {
        out["caps"] = common_json::object();
    }
    return to_bytes(env, out.dump_safe());
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeInfo(JNIEnv * env, jclass, jlong handle) {
    try {
        return info_json(env, reinterpret_cast<Session *>(handle));
    } catch (const std::exception & e) {
        throw_java(env, KIND_LOAD, std::string("Couldn't read the model's details: ") + e.what());
    } catch (...) {
        throw_java(env, KIND_LOAD, "Couldn't read the model's details.");
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
}

// Runs one assistant turn. request: {"messages": [...OpenAI chat messages], "tools": [...OpenAI tools],
// "enable_thinking": bool, "temperature": number}. Streams deltas to sink.onDelta(kind, utf8) and
// returns the parsed turn as JSON: content, reasoning_content, tool_calls[{name, arguments}], stop
// ("eos", "length", "context", "cancelled"), prompt_tokens, reused_tokens, generated_tokens,
// prefill_ms, decode_ms, and parse_error when the reply couldn't be parsed (content is then the raw text).
} // extern "C"

namespace {

jbyteArray generate(JNIEnv * env, Session * s, jbyteArray jrequest, jint max_tokens, jint n_threads, jobject jsink) {
    s->cancel.store(false, std::memory_order_relaxed);
    clear_recent_errors();

    jclass sink_cls = env->GetObjectClass(jsink);
    Sink sink{env, jsink, env->GetMethodID(sink_cls, "onDelta", "(I[B)V")};
    if (sink.on_delta == nullptr) return nullptr;

    if (n_threads > 0) llama_set_n_threads(s->ctx, n_threads, n_threads);

    common_chat_templates_inputs inputs;
    float temperature = 0.6f;
    try {
        const auto request = common_json::parse(from_bytes(env, jrequest));
        inputs.messages = common_chat_msgs_parse_oaicompat(request.at("messages"));
        if (request.contains("tools") && request.at("tools").is_array() && !request.at("tools").empty()) {
            inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
            inputs.parallel_tool_calls = request.value("parallel_tool_calls", true);
        }
        inputs.enable_thinking  = request.value("enable_thinking", false);
        temperature             = request.value("temperature", 0.6);
    } catch (const std::exception & e) {
        throw_java(env, KIND_GENERATE, std::string("Bad request: ") + e.what());
        return nullptr;
    }
    inputs.use_jinja             = true;
    inputs.add_generation_prompt = true;
    inputs.reasoning_format      = COMMON_REASONING_FORMAT_AUTO;
    inputs.add_bos               = llama_vocab_get_add_bos(s->vocab);
    inputs.add_eos               = false;

    std::unique_ptr<common_chat_session> chat;
    try {
        chat = std::make_unique<common_chat_session>(s->templates.get(), s->vocab, inputs);
    } catch (const std::exception & e) {
        throw_java(env, KIND_TEMPLATE, std::string("The model's chat template failed: ") + e.what());
        return nullptr;
    }

    const std::vector<llama_token> prompt = tokenize_prompt(*s, chat->prompt(), inputs.add_bos);
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    if (prompt.empty()) {
        throw_java(env, KIND_GENERATE, "The prompt is empty.");
        return nullptr;
    }
    if (static_cast<int>(prompt.size()) >= n_ctx - 16) {
        throw_java(env, KIND_CONTEXT, "The conversation (" + std::to_string(prompt.size()) + " tokens) doesn't fit the model's " +
                   std::to_string(n_ctx) + "-token context.");
        return nullptr;
    }

    // Reuse the KV cache for the longest common prefix with what it already holds.
    llama_memory_t mem = llama_get_memory(s->ctx);
    size_t n_past = 0;
    while (n_past < s->cached.size() && n_past < prompt.size() && s->cached[n_past] == prompt[n_past]) n_past++;
    if (n_past == prompt.size()) n_past--; // the last prompt token is decoded again for its logits
    if (n_past > 0) {
        bool ok = llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_past), -1);
        // Sliding-window caches drop old positions; a prefix is only usable if the window still holds it
        // (the same check as llama-server's "forcing full prompt re-processing").
        const int n_swa = llama_model_n_swa(s->model);
        if (ok && n_swa > 0) {
            const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
            const llama_pos threshold = std::max<llama_pos>(0, static_cast<llama_pos>(n_past) - n_swa);
            if (pos_min > threshold) ok = false;
        }
        if (!ok) n_past = 0;
    }
    if (n_past == 0) llama_memory_clear(mem, true);
    s->cached.assign(prompt.begin(), prompt.begin() + static_cast<long>(n_past));
    const size_t reused = n_past;

    common_params_sampling sp;
    sp.temp  = temperature;
    sp.top_k = 40;
    sp.top_p = 0.95f;
    sp.min_p = 0.05f;
    chat->apply_sampling(sp);
    common_sampler_ptr smpl(common_sampler_init(s->model, sp));
    if (!smpl) {
        throw_java(env, KIND_TEMPLATE, "llama.cpp couldn't build the tool-call grammar for this model's template.");
        return nullptr;
    }

    std::string stop = "eos";
    const double t_prefill = now_ms();
    for (size_t i = n_past; i < prompt.size(); i += static_cast<size_t>(s->n_batch)) {
        const int n = static_cast<int>(std::min(prompt.size() - i, static_cast<size_t>(s->n_batch)));
        const int rc = llama_decode(s->ctx, llama_batch_get_one(const_cast<llama_token *>(prompt.data() + i), n));
        if (rc == 2 || s->cancel.load()) {
            // Aborted mid-prefill: what was processed is in the cache but untracked; start clean next time.
            llama_memory_clear(mem, true);
            s->cached.clear();
            stop = "cancelled";
            break;
        }
        if (rc != 0) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            const std::string log = recent_errors();
            throw_java(env, rc == 1 ? KIND_CONTEXT : KIND_GENERATE,
                       "llama_decode failed (" + std::to_string(rc) + ") while reading the prompt." + (log.empty() ? "" : "\n" + log));
            return nullptr;
        }
        s->cached.insert(s->cached.end(), prompt.begin() + static_cast<long>(i), prompt.begin() + static_cast<long>(i + n));
    }
    const double prefill_ms = now_ms() - t_prefill;

    Streamer streamer;
    common_chat_input pending;     // pieces not yet fed: they end inside a UTF-8 character
    std::string generated;         // everything fed so far, to look for the template's stop strings
    int n_generated = 0;
    std::string parse_error;
    const auto & stops = chat->additional_stops();
    const double t_decode = now_ms();

    auto feed = [&](const common_chat_input & chunk) -> bool {
        try {
            const auto & msg = chat->feed(chunk);
            return streamer.update(sink, msg);
        } catch (const std::exception & e) {
            // A partial parse can fail on half a tool call; the final parse decides.
            parse_error = e.what();
            return !env->ExceptionCheck();
        }
    };

    if (stop != "cancelled") {
        while (true) {
            if (s->cancel.load()) { stop = "cancelled"; break; }
            if (n_generated >= max_tokens) { stop = "length"; break; }
            if (static_cast<int>(s->cached.size()) >= n_ctx - 1) { stop = "context"; break; }

            const llama_token id = common_sampler_sample(smpl.get(), s->ctx, -1);
            common_sampler_accept(smpl.get(), id, true);
            if (llama_vocab_is_eog(s->vocab, id)) break;
            n_generated++;

            // Control tokens are only rendered when the template's parser needs them (<tool_call> in
            // models where it's a special token), as llama-server does.
            const bool special = sp.preserved_tokens.count(id) > 0;
            pending.append(common_token_to_piece(s->ctx, id, special), id);

            bool stopped_by_string = false;
            const size_t complete = complete_utf8_prefix(pending.text);
            if (complete == pending.size()) {
                common_chat_input chunk = pending;
                // A template stop string ends the turn; it isn't part of the reply.
                const std::string candidate = generated + chunk.text;
                for (const auto & st : stops) {
                    if (st.empty()) continue;
                    const size_t at = candidate.find(st, generated.size() > st.size() ? generated.size() - st.size() : 0);
                    if (at != std::string::npos) {
                        const size_t keep = at > generated.size() ? at - generated.size() : 0;
                        chunk.truncate(keep);
                        stopped_by_string = true;
                        break;
                    }
                }
                pending = common_chat_input();
                generated += chunk.text;
                if (!chunk.empty() && !feed(chunk)) { stop = "cancelled"; break; }
            }
            if (stopped_by_string) break;

            llama_token next = id;
            const int rc = llama_decode(s->ctx, llama_batch_get_one(&next, 1));
            if (rc == 2 || s->cancel.load()) { stop = "cancelled"; break; }
            if (rc != 0) { stop = rc == 1 ? "context" : "error"; break; }
            s->cached.push_back(id);
        }
    }
    const double decode_ms = now_ms() - t_decode;
    if (env->ExceptionCheck()) return nullptr; // the sink threw (the Kotlin flow was cancelled)

    common_chat_msg msg;
    try {
        msg = chat->finish(pending);
    } catch (const std::exception & e) {
        parse_error = e.what();
        msg = common_chat_msg();
        msg.role    = "assistant";
        msg.content = generated + pending.text;
    }
    if (!streamer.finish(sink, msg) && env->ExceptionCheck()) return nullptr;

    common_json out = common_json::object();
    out["content"]           = msg.content;
    out["reasoning_content"] = msg.reasoning_content;
    common_json calls = common_json::array();
    for (const auto & tc : msg.tool_calls) {
        common_json call = common_json::object();
        call["name"]      = tc.name;
        call["arguments"] = tc.arguments;
        calls.push_back(call);
    }
    out["tool_calls"]       = calls;
    out["stop"]             = stop;
    out["prompt_tokens"]    = static_cast<int64_t>(prompt.size());
    out["reused_tokens"]    = static_cast<int64_t>(reused);
    out["generated_tokens"] = static_cast<int64_t>(n_generated);
    out["prefill_ms"]       = prefill_ms;
    out["decode_ms"]        = decode_ms;
    out["format"]           = std::string(common_chat_format_name(chat->format()));
    if (!parse_error.empty()) out["parse_error"] = parse_error;
    return to_bytes(env, out.dump_safe());
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_dev_locus_core_ondevice_gguf_LlamaNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jbyteArray jrequest, jint max_tokens, jint n_threads, jobject jsink) {
    auto * s = reinterpret_cast<Session *>(handle);
    try {
        return generate(env, s, jrequest, max_tokens, n_threads, jsink);
    } catch (const std::bad_alloc &) {
        throw_java(env, KIND_MEMORY, "Ran out of memory while generating.");
    } catch (const std::exception & e) {
        throw_java(env, KIND_GENERATE, e.what());
    } catch (...) {
        throw_java(env, KIND_GENERATE, "llama.cpp failed while generating.");
    }
    // The failure may have come between a decode and its bookkeeping: start the next turn from a clean cache.
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    return nullptr;
}

} // extern "C"
