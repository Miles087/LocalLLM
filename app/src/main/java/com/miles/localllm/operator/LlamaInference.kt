package com.miles.localllm.operator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.miles.localllm.tts.VolcTtsManager
import java.util.concurrent.Executors

class LlamaInference (private val context: Context) {
    companion object {
        const val TAG = "MyLocalLLM"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_TTS_API_KEY = "tts_api_key"
        private const val KEY_TTS_SPEAKER = "tts_speaker"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        // TTS 攒够这么多字才开始送读，避免首包碎片导致卡顿/杂音
        private const val MIN_TTS_CHARS = 10
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()
    var isModelLoaded = false
        private set

    // 角色扮演的系统提示词，空串表示不使用；写入时自动缓存到本地
    @Volatile
    var systemPrompt: String = ""
        set(value) {
            field = value
            prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()
        }

    // 声音开关：开启后流式语音播报模型输出（接 TTS 用）
    @Volatile
    var soundEnabled: Boolean = false

    // TTS 鉴权配置（新版控制台使用 API Key），写入时自动缓存到本地
    @Volatile
    var ttsApiKey: String = ""
        set(value) {
            field = value
            prefs.edit().putString(KEY_TTS_API_KEY, value).apply()
        }

    // TTS 音色（speaker id），写入时自动缓存到本地
    @Volatile
    var ttsSpeaker: String = ""
        set(value) {
            field = value
            prefs.edit().putString(KEY_TTS_SPEAKER, value).apply()
        }

    init {
        // 启动时恢复缓存的设置
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        ttsApiKey = prefs.getString(KEY_TTS_API_KEY, "") ?: ""
        ttsSpeaker = prefs.getString(KEY_TTS_SPEAKER, "") ?: ""
        Log.d(TAG, "LlamaInference: 缓存恢复 system=${systemPrompt.isNotBlank()}, apiKey=${ttsApiKey.isNotBlank()}")
    }

    private val tts = VolcTtsManager(context).apply {
        speakerProvider = { ttsSpeaker.ifBlank { VolcTtsManager.DEFAULT_SPEAKER } }
    }
    private val ttsUid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    // TTS 待发送缓冲：攒到句号等边界再发，服务端切句返回
    private val ttsBuffer = StringBuilder()

    fun setSoundEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            if (ttsApiKey.isBlank()) {
                toast("请先在「设定」中填写 TTS 的 API Key")
                soundEnabled = false
                return false
            }
            // 引擎初始化需要在主线程进行（Demo 均在主线程调用，内部可能依赖主线程 Looper）
            Handler(Looper.getMainLooper()).post {
                tts.start(ttsApiKey, ttsUid)
            }
        } else {
            // 不能用 executor：生成任务可能正占着单线程 executor，stop 会排队导致声音停不下来
            Thread { tts.stop() }.apply { isDaemon = true; name = "tts-stop" }.start()
            ttsBuffer.setLength(0)
        }
        soundEnabled = enabled
        Log.d(TAG, "setSoundEnabled: $enabled")
        return enabled
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun initModel(callback: (Boolean) -> Unit) {
        Log.d(TAG, "initModel: 开始加载模型")
        executor.execute {
            val path = ModelManager.getModelPath(context)
            Log.d(TAG, "initModel: 模型路径=$path")
            val start = System.currentTimeMillis()
            val success = LlamaBridge.initModel(path, 4)
            Log.d(TAG, "initModel: LlamaBridge.initModel 返回 success=$success, 耗时=${System.currentTimeMillis() - start}ms")
            isModelLoaded = success
            toast(if (success) "模型加载完成" else "模型加载失败")
            Handler(Looper.getMainLooper()).post {
                callback(success)
            }
        }
    }

    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        onDelta: (String) -> Unit = {},
        callback: (String) -> Unit
    ) {
        if (!isModelLoaded) {
            Log.w(TAG, "generate: 模型未加载，直接返回错误")
            callback("错误：模型未加载")
            return
        }

        Log.d(TAG, "generate: 开始生成, prompt=$prompt, maxTokens=$maxTokens")
        toast("开始生成")

        executor.execute {
            try {
                val start = System.currentTimeMillis()

                // 流式回调：native 在后台线程回调，汇总后切主线程刷新界面
                val sb = StringBuilder()
                ttsBuffer.setLength(0)
                val streamCb = LlamaBridge.TokenCallback { piece ->
                    sb.append(piece)
                    val snapshot = sb.toString()
                    Handler(Looper.getMainLooper()).post {
                        onDelta(snapshot)
                    }
                    // 声音开启时按句子边界送 TTS；攒够 MIN_TTS_CHARS 个字才开始送，避免碎片文本
                    if (soundEnabled) {
                        ttsBuffer.append(piece)
                        if (ttsBuffer.length >= MIN_TTS_CHARS) {
                            val boundary = ttsBuffer.indexOfLast { it in "。！？!?；;\n" }
                            if (boundary >= 0) {
                                tts.sendText(ttsBuffer.substring(0, boundary + 1))
                                ttsBuffer.delete(0, boundary + 1)
                            }
                        }
                    }
                }

                // 设置了系统提示词就走角色扮演模板，否则用简单提示词
                val sys = systemPrompt
                val result = if (sys.isNotBlank()) {
                    Log.d(TAG, "generate: 使用角色扮演模式, systemPrompt=$sys")
                    LlamaBridge.generateChat(sys, prompt, maxTokens, streamCb)
                } else {
                    val simplePrompt = "User: $prompt\nAssistant:"
                    LlamaBridge.generate(simplePrompt, maxTokens, streamCb)
                }

                Log.d(TAG, "generate: LlamaBridge.generate 返回, 耗时=${System.currentTimeMillis() - start}ms, 结果=$result")

                // 声音：冲刷剩余文本并结束本轮合成
                if (soundEnabled) {
                    if (ttsBuffer.isNotEmpty()) {
                        tts.sendText(ttsBuffer.toString())
                        ttsBuffer.setLength(0)
                    }
                    tts.finishSession()
                }

                toast("生成结束")
                Handler(Looper.getMainLooper()).post {
                    callback(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "generate: 生成异常", e)
                if (soundEnabled) tts.cancel()
                toast("生成异常: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    callback("异常: ${e.message}")
                }
            }
        }
    }

    fun stopGenerate() {
        Log.d(TAG, "stopGenerate: 请求停止生成")
        LlamaBridge.stopGenerate()
    }

    fun release() {
        tts.stop()
        executor.execute {
            LlamaBridge.freeModel()
        }
    }
}