#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <android/log.h>
#include "llama.h"
// #include "common.h"  // 暂时不需要

#define LOG_TAG "MyLocalLLM"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局实例
static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

// 停止生成标志：由 stopGenerate 设置，采样循环每步检查
static std::atomic_bool g_stop_flag{false};

// 返回从 s[0] 开始的完整 UTF-8 字符长度；0 表示非法首字节；(size_t)-1 表示首字节合法但序列被截断
static size_t utf8_char_len(const char* s, size_t avail) {
    unsigned char c = (unsigned char) s[0];
    if (c < 0x80) return 1;
    size_t need;
    if ((c & 0xE0) == 0xC0) need = 2;
    else if ((c & 0xF0) == 0xE0) need = 3;
    else if ((c & 0xF8) == 0xF0) need = 4;
    else return 0;
    if (avail < need) return (size_t) -1;
    for (size_t j = 1; j < need; j++) {
        if (((unsigned char) s[j] & 0xC0) != 0x80) return 0;
    }
    return need;
}

// 把不完整/非法的字节替换为 U+FFFD，保证结果能通过 NewStringUTF
static std::string sanitize_utf8(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        size_t n = utf8_char_len(in.c_str() + i, in.size() - i);
        if (n == (size_t) -1) {   // 结尾被截断
            out += "\xEF\xBF\xBD";
            break;
        } else if (n == 0) {      // 非法字节
            out += "\xEF\xBF\xBD";
            i += 1;
        } else {
            out.append(in, i, n);
            i += n;
        }
    }
    return out;
}

// 从回调对象中解析 onToken 方法
static jmethodID resolve_on_token(JNIEnv* env, jobject callback, const char* log_prefix) {
    if (!callback) return nullptr;
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("%s: onToken method not found", log_prefix);
    }
    return onToken;
}

// 采样解码循环：tokenize -> 逐 token 解码采样，支持流式回调，返回完整生成文本
static std::string run_generate(JNIEnv* env, const std::string& prompt_text, int max_tokens,
                                jobject callback, jmethodID onToken, const char* log_prefix) {
    const llama_vocab* vocab = llama_model_get_vocab(model);
    g_stop_flag.store(false);

    std::vector<llama_token> tokens(llama_n_ctx(ctx));
    int n_tokens = llama_tokenize(
            vocab,
            prompt_text.c_str(),
            prompt_text.length(),
            tokens.data(),
            tokens.size(),
            true,
            false
    );

    if (n_tokens < 0) {
        LOGE("%s: tokenize failed, n_tokens=%d", log_prefix, n_tokens);
        return "";
    }
    tokens.resize(n_tokens);
    LOGD("%s: tokenize ok, n_tokens=%d, n_ctx=%d", log_prefix, n_tokens, llama_n_ctx(ctx));

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // 重复惩罚：防止小模型陷入文本循环（llama.cpp 常用默认值）
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string result;
    std::string utf8_pending;  // 不完整的 UTF-8 尾部，等下一个 piece 补齐
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());

    for (int i = 0; i < max_tokens; i++) {
        if (g_stop_flag.load()) {
            LOGD("%s: stopped by user at step %d", log_prefix, i);
            break;
        }
        if (i == 0) LOGD("%s: first llama_decode ...", log_prefix);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("%s: llama_decode failed at step %d", log_prefix, i);
            break;
        }
        if (i == 0) LOGD("%s: first llama_decode done", log_prefix);

        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

        if (llama_token_is_eog(vocab, new_token)) {
            LOGD("%s: EOG token at step %d", log_prefix, i);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, false);
        if (n > 0) {
            result.append(buf, n);
            // 流式回调：只发送完整的 UTF-8 字符，半个汉字先攒着等下一个 piece
            if (onToken) {
                utf8_pending.append(buf, n);
                size_t emit_len = 0;
                while (emit_len < utf8_pending.size()) {
                    size_t cl = utf8_char_len(utf8_pending.c_str() + emit_len,
                                              utf8_pending.size() - emit_len);
                    if (cl == (size_t) -1) break;  // 末尾不完整，等下一个 piece
                    if (cl == 0) break;            // 非法字节，交给 sanitize 兜底
                    emit_len += cl;
                }
                if (emit_len > 0) {
                    std::string piece = utf8_pending.substr(0, emit_len);
                    utf8_pending.erase(0, emit_len);
                    jstring jpiece = env->NewStringUTF(piece.c_str());
                    env->CallVoidMethod(callback, onToken, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) {
                        LOGE("%s: callback threw exception, aborting at step %d", log_prefix, i);
                        env->ExceptionDescribe();
                        env->ExceptionClear();
                        break;
                    }
                }
            }
        }
        if (i < 5 || (i % 32) == 0) {
            LOGD("%s: step=%d, token=%d, piece=%.*s", log_prefix, i, new_token, n > 0 ? n : 0, buf);
        }

        batch = llama_batch_get_one(&new_token, 1);
    }

    llama_sampler_free(smpl);
    LOGD("%s: loop finished, result_len=%zu", log_prefix, result.size());

    // 循环结束时可能还残留半个字符：回调一个替换符，与最终文本保持一致
    if (onToken && !utf8_pending.empty()) {
        jstring jpiece = env->NewStringUTF("\xEF\xBF\xBD");
        env->CallVoidMethod(callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);
        utf8_pending.clear();
    }

    // 最终返回的完整字符串也做一次清洗，防止 NewStringUTF 崩溃
    return sanitize_utf8(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miles_localllm_operator_LlamaBridge_initModel(
        JNIEnv* env, jobject thiz, jstring model_path, jint n_threads) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("initModel: enter, path=%s, n_threads=%d", path, n_threads);

    llama_backend_init();
    LOGD("initModel: llama_backend_init done");

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    LOGD("initModel: calling llama_load_model_from_file ...");
    model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("initModel: llama_load_model_from_file returned null");
        return JNI_FALSE;
    }
    LOGD("initModel: model loaded");

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = n_threads;

    ctx = llama_new_context_with_model(model, ctx_params);
    LOGD("initModel: llama_new_context_with_model -> %s", ctx ? "ok" : "null");
    return ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miles_localllm_operator_LlamaBridge_generate(
        JNIEnv* env, jobject thiz, jstring prompt, jint max_tokens, jobject callback) {

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGD("generate: enter, prompt=%s, max_tokens=%d", prompt_str, max_tokens);

    if (!model || !ctx) {
        LOGE("generate: model or ctx is null (model=%p, ctx=%p)", (void*)model, (void*)ctx);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("模型未加载");
    }

    jmethodID onToken = resolve_on_token(env, callback, "generate");

    // 清空上下文，避免上一轮残留的 KV 污染本轮生成
    llama_memory_clear(llama_get_memory(ctx), true);

    std::string result = run_generate(env, prompt_str, max_tokens, callback, onToken, "generate");

    LOGD("generate: result=%s", result.c_str());
    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTF(result.c_str());
}

// 角色扮演生成：system prompt + user prompt，使用模型自带对话模板
extern "C" JNIEXPORT jstring JNICALL
Java_com_miles_localllm_operator_LlamaBridge_generateChat(
        JNIEnv* env, jobject thiz, jstring system_prompt, jstring user_prompt,
        jint max_tokens, jobject callback) {

    if (!model || !ctx) {
        LOGE("generateChat: model or ctx is null");
        return env->NewStringUTF("模型未加载");
    }

    const char* system_str = system_prompt ? env->GetStringUTFChars(system_prompt, nullptr) : nullptr;
    const char* user_str = env->GetStringUTFChars(user_prompt, nullptr);
    LOGD("generateChat: enter, system=%s, user=%.200s, max_tokens=%d",
         system_str ? system_str : "", user_str, max_tokens);

    // 读取模型自带的对话模板（jinja），没有则让 llama_chat_apply_template 用默认 ChatML
    std::string tmpl;
    {
        std::vector<char> meta_buf(16384);
        int n = llama_model_meta_val_str(model, "tokenizer.chat_template", meta_buf.data(), meta_buf.size());
        if (n > 0) {
            tmpl.assign(meta_buf.data(), n);
            LOGD("generateChat: using model chat template, len=%d", n);
        } else {
            LOGD("generateChat: no chat template in model metadata, fallback to default");
        }
    }

    // 组装消息列表
    std::vector<llama_chat_message> messages;
    if (system_str && strlen(system_str) > 0) {
        messages.push_back({"system", system_str});
    }
    messages.push_back({"user", user_str});

    // 应用对话模板，结尾附加 assistant 起始标记
    std::vector<char> tmpl_buf(16384);
    int32_t len = llama_chat_apply_template(
            tmpl.empty() ? nullptr : tmpl.c_str(),
            messages.data(), messages.size(),
            true,
            tmpl_buf.data(), tmpl_buf.size());

    if (len < 0) {
        LOGE("generateChat: apply template failed, ret=%d", len);
        if (system_str) env->ReleaseStringUTFChars(system_prompt, system_str);
        env->ReleaseStringUTFChars(user_prompt, user_str);
        return env->NewStringUTF("应用对话模板失败");
    }
    tmpl_buf.resize(len);
    std::string full_prompt(tmpl_buf.data(), len);
    LOGD("generateChat: formatted prompt:\n%s", full_prompt.c_str());

    if (system_str) env->ReleaseStringUTFChars(system_prompt, system_str);
    env->ReleaseStringUTFChars(user_prompt, user_str);

    // 清空上下文，确保 system prompt 和本轮对话干净生效
    llama_memory_clear(llama_get_memory(ctx), true);

    jmethodID onToken = resolve_on_token(env, callback, "generateChat");
    std::string result = run_generate(env, full_prompt, max_tokens, callback, onToken, "generateChat");

    LOGD("generateChat: result=%.500s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// 停止生成：设置标志位，采样循环在下一个 token 处退出
extern "C" JNIEXPORT void JNICALL
Java_com_miles_localllm_operator_LlamaBridge_stopGenerate(JNIEnv* env, jobject thiz) {
    LOGD("stopGenerate: flag set");
    g_stop_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_miles_localllm_operator_LlamaBridge_freeModel(JNIEnv* env, jobject thiz) {
    if (ctx) llama_free(ctx);
    if (model) llama_free_model(model);
    llama_backend_free();
}

// 清空上下文 - 开始新对话时调用
extern "C" JNIEXPORT void JNICALL
Java_com_miles_localllm_operator_LlamaBridge_clearContext(JNIEnv* env, jobject thiz) {
    if (ctx) {
        llama_memory_clear(llama_get_memory(ctx), true);
    }
}
