# 本地LLM（LocalLLM）

一款在 Android 手机上**完全离线运行**大语言模型的聊天应用。用户自备 GGUF 模型文件，即可在手机 CPU 上进行本地推理，无需联网、无需云端 API，对话数据全部留在设备上。可选配火山引擎豆包 TTS，将模型回复朗读出来。

## 功能特性

### 本地推理（核心）
- 基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) 预编译库，纯 CPU 推理（arm64-v8a），4 线程，上下文 2048 tokens
- **模型导入**：通过系统文件选择器（SAF）或手动输入路径导入 `.gguf` / `.bin` 模型文件，支持列表查看、删除、加载
- **流式输出**：token 实时逐字返回（JNI 层处理半个 UTF-8 汉字的拼接与非法字节清洗）
- **中途停止**：发送按钮在生成过程中变为"停止"，可随时中断
- **角色设定**：可自定义 System Prompt，应用会优先读取 GGUF 内嵌的 jinja chat template 生成对话格式（无模板时回退 ChatML）

### 聊天体验
- Jetpack Compose Material3 界面：聊天气泡、流式"思考中"指示、生成期间屏幕常亮
- **图片轮播**：聊天界面上半部轮播角色图片，支持自动切换、左右滑动、点击查看大图与双指缩放；图片可存放在外部目录，或移入应用私有目录（需密码访问，另有隐私遮罩模式一键全黑）
- **可选语音播报**（需联网）：火山引擎豆包 seed-tts-2.0 双向流式 TTS，按中文句边界攒句朗读，5 种内置音色，支持音量淡入淡出防爆音；API Key 由用户自填，仅保存在本地

## 技术栈

| 项目 | 说明 |
| --- | --- |
| 语言 | Kotlin + Jetpack Compose（UI）/ C++（JNI） |
| 推理引擎 | llama.cpp（预编译 `libllama.so` 等，位于 `app/src/main/jniLibs/arm64-v8a/`） |
| 网络 | OkHttp（TTS WebSocket 直连，手写二进制协议）、Coil（图片加载） |
| 构建 | AGP 9.3.2、Kotlin 2.2.10、minSdk 24 / targetSdk 37、仅 arm64-v8a |

## 使用说明

1. 自行准备一个 **GGUF 格式**的小型 instruct 模型（如 Qwen、Gemma 等的小尺寸量化版），推送到手机
2. 打开应用，通过文件选择器导入模型并加载
3. 开始对话；可在设置中填写 System Prompt、配置 TTS（可选）

> **注意**：当前版本为单轮问答式体验——每轮生成前会清空 KV 缓存，不保留多轮上下文；采样参数为硬编码的 greedy + 重复惩罚，暂不支持温度等参数调节。

## 项目结构

```
app/src/main/
├── java/com/miles/localllm/
│   ├── MainActivity.kt            # 唯一 Activity，屏幕状态机：loader → loading → chat
│   ├── ChatScreen.kt              # 聊天界面 + 角色设定 + 图片轮播 + 隐私遮罩
│   ├── ModelLoaderScreen.kt       # 模型导入 / 选择 / 删除
│   ├── operator/
│   │   ├── ModelManager.kt        # 模型目录管理
│   │   ├── LlamaInference.kt      # 推理调度（单线程 executor、TTS 句子缓冲）
│   │   └── LlamaBridge.kt         # JNI 边界声明
│   └── tts/
│       └── VolcTtsManager.kt      # 豆包流式 TTS（WebSocket 协议、音频播放）
├── cpp/
│   └── llama-android.cpp          # JNI 实现：模型加载 / 生成循环 / 流式回调 / 停止
└── jniLibs/arm64-v8a/             # 预编译 llama.cpp 动态库
```

## 隐私说明

- LLM 推理完全离线，模型文件存储在应用外部私有目录（`Android/data/<包名>/files/models/`），对话不落盘
- 唯一的联网行为是可选的豆包 TTS 服务（`wss://openspeech.bytedance.com`），API Key 仅存于本机 SharedPreferences
- 声明了 `INTERNET`、`ACCESS_NETWORK_STATE`、`RECORD_AUDIO` 权限（录音权限为预留）

## 构建注意事项

- 仓库已包含约 58MB 预编译 llama.cpp so 库，无需自行编译引擎；`app/src/main/cpp/llama.cpp/` 下的源码副本仅供参考，不参与构建
- `app/src/main/cpp/CMakeLists.txt` 中的 llama.cpp 头文件路径为本机绝对路径（`D:/Projects/Android/Build_llama/...`），在其他机器上构建需先修改为本地路径

## 致谢

- [llama.cpp](https://github.com/ggml-org/llama.cpp) —— 本地推理引擎
- 豆包 TTS 协议参考[火山引擎文档](https://docs.volcengine.com/docs/6561/2532486)
