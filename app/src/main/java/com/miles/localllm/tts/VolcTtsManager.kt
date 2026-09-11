package com.miles.localllm.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 火山引擎豆包语音 - 双向流式 TTS（直接走 WebSocket 二进制协议，不依赖官方 SDK）。
 * 协议文档：https://docs.volcengine.com/docs/6561/2532486
 * 鉴权：新版控制台 API Key（X-Api-Key 请求头）。
 * 音频：pcm 24000Hz 单声道 16bit，AudioTrack 直接播放。
 */
class VolcTtsManager(private val context: Context) {

    companion object {
        const val TAG = "MyLocalLLM"

        const val TTS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        const val RESOURCE_ID = "seed-tts-2.0"

        // 采样率/声道/位深，与服务端 audio_params 请求保持一致
        const val SAMPLE_RATE = 24000

        // 协议 event 码
        const val EV_START_CONNECTION = 1
        const val EV_FINISH_CONNECTION = 2
        const val EV_START_SESSION = 100
        const val EV_CANCEL_SESSION = 101
        const val EV_FINISH_SESSION = 102
        const val EV_TASK_REQUEST = 200

        // 默认音色（文档示例音色，已验证可用）
        const val DEFAULT_SPEAKER = "zh_female_vv_uranus_bigtts"
    }

    /** 音色提供方，由调用方注入（读缓存/设置），为空时用 DEFAULT_SPEAKER */
    @Volatile
    var speakerProvider: (() -> String)? = null

    private fun currentSpeaker(): String {
        val s = speakerProvider?.invoke()?.trim().orEmpty()
        return s.ifBlank { DEFAULT_SPEAKER }
    }

    // 连接/会话状态（全部在 WebSocket 回调线程里改，用 @Synchronized 保护）
    private var ws: WebSocket? = null
    private var apiKey = ""
    private var uid = ""
    private var started = false          // start() 成功发起
    private var connectionReady = false  // 已收到 ConnectionStarted
    private var sessionActive = false    // 已发送 StartSession 且尚未 SessionFinished
    private var sessionId = ""
    private var sessionLatch: CountDownLatch? = null
    private val pendingTexts = LinkedBlockingQueue<String>()

    // 音频播放
    private var player: AudioTrack? = null
    private val audioQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    @Volatile private var playerRunning = false
    private var playerThread: Thread? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun start(key: String, userId: String) {
        if (started) {
            Log.d(TAG, "TTS: 已启动，跳过")
            return
        }
        if (key.isBlank()) {
            Log.e(TAG, "TTS: API Key 为空")
            return
        }
        apiKey = key
        uid = userId
        started = true
        connectionReady = false
        sessionActive = false
        // 打印 Key 指纹便于核对（前8位+长度），不打印完整 Key
        Log.d(TAG, "TTS: apiKey 指纹=${key.take(8)}... len=${key.length} 含空白=${key != key.trim()}")

        startPlayer()

        val connectId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(TTS_URL)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Connect-Id", connectId)
            .build()
        Log.d(TAG, "TTS: 正在连接... connectId=$connectId")
        ws = client.newWebSocket(request, WsListener())
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { ws?.send(buildFrame(EV_FINISH_CONNECTION, null, """{"event":2}""")) }
        runCatching { ws?.close(1000, null) }
        ws = null
        connectionReady = false
        sessionActive = false
        pendingTexts.clear()
        sessionLatch?.countDown(); sessionLatch = null
        stopPlayer()
        Log.d(TAG, "TTS: 已停止")
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (!started) return
        synchronized(this) {
            if (!connectionReady) {
                // 连接尚未就绪，先入队，ConnectionStarted 后统一 flush
                pendingTexts.offer(text)
                return
            }
        }
        ensureSessionAndSend(text)
    }

    fun finishSession() {
        synchronized(this) {
            if (!sessionActive) return
            sessionActive = false
            Log.d(TAG, "TTS: 结束会话 sid=$sessionId")
            ws?.send(buildFrame(EV_FINISH_SESSION, sessionId, """{"event":102}"""))
        }
    }

    fun cancel() {
        synchronized(this) {
            pendingTexts.clear()
            if (sessionActive) {
                sessionActive = false
                Log.d(TAG, "TTS: 取消会话 sid=$sessionId")
                ws?.send(buildFrame(EV_CANCEL_SESSION, sessionId, """{"event":101}"""))
            }
        }
        drainAudio()
    }

    // ---------------- 内部逻辑 ----------------

    private fun ensureSessionAndSend(text: String) {
        var sid: String
        synchronized(this) {
            if (!started || ws == null) return
            if (!sessionActive) {
                sessionId = UUID.randomUUID().toString()
                sessionActive = true
                val latch = CountDownLatch(1)
                sessionLatch = latch
                val payload = sessionPayload(sessionId)
                Log.d(TAG, "TTS: 开始会话 sid=$sessionId")
                ws?.send(buildFrame(EV_START_SESSION, sessionId, payload))
                // 等 SessionStarted（最多 10s），期间文本入队
                pendingTexts.offer(text)
                flushPendingAfter(latch)
                return
            }
            sid = sessionId
            // 会话已激活：若还在等 SessionStarted，先入队
            val latch = sessionLatch
            if (latch != null) {
                pendingTexts.offer(text)
                return
            }
        }
        sendTask(sid, text)
    }

    private fun flushPendingAfter(latch: CountDownLatch) {
        // 在异步线程等待，避免阻塞生成线程
        Thread {
            val ok = runCatching { latch.await(10, TimeUnit.SECONDS) }.getOrDefault(false)
            synchronized(this) {
                if (sessionLatch === latch) sessionLatch = null
                if (!ok) Log.w(TAG, "TTS: 等待 SessionStarted 超时")
            }
            flushPending()
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun flushPending() {
        if (!started) return
        val latch = sessionLatch
        if (latch != null) return // 还在等
        val sid = sessionId
        while (true) {
            val t = pendingTexts.poll() ?: break
            sendTask(sid, t)
        }
    }

    @Synchronized
    private fun sendTask(sid: String, text: String) {
        if (!started || ws == null) return
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        val speaker = currentSpeaker()
        val payload = """{"user":{"uid":"$uid"},"event":200,"req_params":{"text":"$escaped","speaker":"$speaker","audio_params":{"format":"pcm","sample_rate":$SAMPLE_RATE}}}"""
        ws?.send(buildFrame(EV_TASK_REQUEST, sid, payload))
    }

    private fun sessionPayload(sid: String): String {
        val speaker = currentSpeaker()
        return """{"user":{"uid":"$uid"},"event":100,"req_params":{"speaker":"$speaker","audio_params":{"format":"pcm","sample_rate":$SAMPLE_RATE}}}"""
    }

    // ---------------- WebSocket 回调 ----------------

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "TTS: 已连接 logid=${response.header("x-tt-logid")}")
            webSocket.send(buildFrame(EV_START_CONNECTION, null, """{"event":1}"""))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 文本帧一般用于异常信息
            Log.w(TAG, "TTS: 文本帧 $text")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "TTS: 连接失败 code=${response?.code} msg=${response?.message}", t)
            synchronized(this@VolcTtsManager) {
                connectionReady = false
                sessionActive = false
                sessionLatch?.countDown(); sessionLatch = null
                pendingTexts.clear()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "TTS: 连接关闭 code=$code reason=$reason")
            synchronized(this@VolcTtsManager) {
                connectionReady = false
                sessionActive = false
            }
        }
    }

    private fun handleFrame(data: ByteArray) {
        if (data.size < 8) return
        val mtype = (data[1].toInt() and 0xFF) shr 4
        when (mtype) {
            0xF -> { // 错误帧：header(4) + errCode(4) + JSON
                val code = readIntBe(data, 4)
                val msg = String(data, 8, data.size - 8, Charsets.UTF_8)
                Log.e(TAG, "TTS: 错误帧 code=$code msg=$msg")
            }
            0xB -> { // 音频帧：header(4) + event(4) + 裸 PCM
                val audio = data.copyOfRange(8, data.size)
                if (audio.isNotEmpty()) audioQueue.offer(applyEdgeFade(audio))
            }
            0x9 -> { // 完整响应帧：header(4) + event(4) + [sid] + size(4) + JSON
                val event = readIntBe(data, 4)
                var off = 8
                // session id（可选，uuid 长度 36）
                if (data.size >= off + 4) {
                    val len = readIntBe(data, off)
                    if (len in 30..40 && data.size >= off + 4 + len + 4) {
                        off += 4 + len
                    }
                }
                if (data.size < off + 4) return
                val plen = readIntBe(data, off)
                val json = String(data, off + 4, minOf(plen, data.size - off - 4), Charsets.UTF_8)
                handleEvent(event, json)
            }
            else -> Log.w(TAG, "TTS: 未知帧类型 mtype=$mtype")
        }
    }

    @Synchronized
    private fun handleEvent(event: Int, json: String) {
        when (event) {
            50 -> { // ConnectionStarted
                connectionReady = true
                Log.d(TAG, "TTS: ConnectionStarted")
                flushPending()
            }
            51 -> Log.e(TAG, "TTS: ConnectionFailed $json")
            150 -> { // SessionStarted
                Log.d(TAG, "TTS: SessionStarted")
                sessionLatch?.countDown(); sessionLatch = null
                flushPending()
            }
            153 -> Log.e(TAG, "TTS: SessionFailed $json")
            350 -> Log.d(TAG, "TTS: 句子合成开始")
            351 -> Log.d(TAG, "TTS: 句子合成结束")
            152 -> { // SessionFinished
                Log.d(TAG, "TTS: SessionFinished")
                sessionActive = false
            }
            else -> Log.d(TAG, "TTS: event=$event $json")
        }
    }

    // ---------------- 二进制帧编解码 ----------------

    // header: [0]=0x11(版本1|头长4) [1]=0x14(整包请求|含event) [2]=0x10(JSON|不压缩) [3]=0
    private fun buildFrame(event: Int, sid: String?, payload: String): ByteString {
        val body = payload.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x11)
        out.write(0x14)
        out.write(0x10)
        out.write(0x00)
        writeIntBe(out, event)
        if (sid != null) {
            val sb = sid.toByteArray(Charsets.UTF_8)
            writeIntBe(out, sb.size)
            out.write(sb)
        }
        writeIntBe(out, body.size)
        out.write(body)
        return ByteString.of(*out.toByteArray())
    }

    private fun readIntBe(d: ByteArray, off: Int): Int {
        return ((d[off].toInt() and 0xFF) shl 24) or
                ((d[off + 1].toInt() and 0xFF) shl 16) or
                ((d[off + 2].toInt() and 0xFF) shl 8) or
                (d[off + 3].toInt() and 0xFF)
    }

    private fun writeIntBe(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    // ---------------- 音频播放 ----------------

    // 对一帧 PCM(16bit 小端) 的头尾做 5ms 线性淡入淡出，消除帧边界跳变造成的爆音。
    // 帧长小于 10ms 时按帧长一半处理，避免淡入淡出重叠。
    private fun applyEdgeFade(frame: ByteArray): ByteArray {
        val fadeSamples = SAMPLE_RATE / 200 // 5ms
        val totalSamples = frame.size / 2
        if (totalSamples < 4) return frame
        val fade = minOf(fadeSamples, totalSamples / 2)
        // 淡入：第 i 个采样乘以 (i+1)/fade
        for (i in 0 until fade) {
            val idx = i * 2
            val s = readPcm16(frame, idx)
            writePcm16(frame, idx, (s * (i + 1) / fade).toInt().toShort())
        }
        // 淡出：倒数第 i 个采样乘以 (i+1)/fade
        for (i in 0 until fade) {
            val idx = (totalSamples - 1 - i) * 2
            val s = readPcm16(frame, idx)
            writePcm16(frame, idx, (s * (i + 1) / fade).toInt().toShort())
        }
        return frame
    }

    private fun readPcm16(b: ByteArray, off: Int): Short {
        return ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort()
    }

    private fun writePcm16(b: ByteArray, off: Int, v: Short) {
        b[off] = (v.toInt() and 0xFF).toByte()
        b[off + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }

    private fun startPlayer() {
        if (playerRunning) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        player = track
        playerRunning = true
        playerThread = Thread {
            // 预缓冲约 1 秒音频再开始写设备，避免句间网络间隙造成断流（嘟嘟声）
            val prebufferBytes = SAMPLE_RATE * 2 // 24000Hz * 16bit * 1s
            // 无数据时补静音，保证 AudioTrack 永不欠载（欠载恢复瞬间就是咔哒声）
            val silence = ByteArray(SAMPLE_RATE * 2 / 10) // 100ms 静音
            val pending = java.io.ByteArrayOutputStream()
            var started = false
            while (playerRunning) {
                val chunk = try {
                    audioQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }
                if (chunk != null && chunk.isNotEmpty()) {
                    pending.write(chunk)
                }
                if (!started) {
                    if (pending.size() >= prebufferBytes) {
                        started = true
                        track.write(pending.toByteArray(), 0, pending.size())
                        pending.reset()
                    }
                } else if (pending.size() > 0) {
                    track.write(pending.toByteArray(), 0, pending.size())
                    pending.reset()
                } else if (chunk == null) {
                    // 队列空且等了 100ms：补一段静音防止欠载
                    track.write(silence, 0, silence.size)
                }
            }
        }.apply {
            isDaemon = true
            name = "tts-player"
            start()
        }
        Log.d(TAG, "TTS: 播放器已启动")
    }

    private fun stopPlayer() {
        playerRunning = false
        playerThread?.interrupt()
        playerThread = null
        audioQueue.clear()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        Log.d(TAG, "TTS: 播放器已停止")
    }

    // 取消时丢掉未播放的音频
    private fun drainAudio() {
        audioQueue.clear()
    }
}
