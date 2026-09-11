package com.miles.localllm.operator

object LlamaBridge {
    init {
        System.loadLibrary("omp")
        System.loadLibrary("ggml-base")
        System.loadLibrary("ggml-cpu")
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("llama-android")
    }

    external fun initModel(modelPath: String, threads: Int): Boolean

    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    // 基础生成（单轮，无角色设定），callback 非空时每生成一个 token 回调一次
    external fun generate(prompt: String, maxTokens: Int, callback: TokenCallback?): String

    // 带系统提示词的角色扮演生成（使用模型自带对话模板）
    external fun generateChat(systemPrompt: String, userPrompt: String, maxTokens: Int, callback: TokenCallback?): String

    // 清空上下文（开始新对话/新游戏时调用）
    external fun clearContext()

    // 停止当前生成（采样循环在下一个 token 处退出）
    external fun stopGenerate()

    external fun freeModel()
}
