package com.example.llama;

public class LlamaBridge {
    static {
        // 按依赖顺序加载
        System.loadLibrary("omp");
        System.loadLibrary("ggml-base");
        System.loadLibrary("ggml-cpu");
        System.loadLibrary("ggml");
        System.loadLibrary("llama");
        System.loadLibrary("llama-android");
    }

    public native boolean initModel(String modelPath, int threads);
    public native String generate(String prompt, int maxTokens);
    public native void freeModel();
}
