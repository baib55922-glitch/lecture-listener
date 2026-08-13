package com.lecturelistener.service

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import com.lecturelistener.asr.ModelManager
import com.lecturelistener.asr.SherpaEngine
import com.lecturelistener.data.ServiceState
import com.lecturelistener.data.SessionRepository
import com.lecturelistener.data.SettingsStore
import com.lecturelistener.llm.AnswerEngine
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * 前台服务：麦克风 → sherpa-onnx Paraformer 离线识别 → 断句 → 问句管线。
 * 息屏/切后台持续运行；通知栏（小米超级岛胶囊）常驻。
 */
class MicListenerService : Service() {

    companion object {
        const val ACTION_START = "com.lecturelistener.action.START"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 100ms @16kHz
        private const val MIN_SEGMENT_CHARS = 3
        private const val TAIL_PADDING_SEC = 0.8f // paraformer 尾部上下文填充

        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, MicListenerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, MicListenerService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running = false
    private var streamThread: Thread? = null
    private var recorder: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统重建服务时 intent 可能为 null，直接退出
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_START) startListening() else stopSelf()
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (running) return
        running = true
        startForeground(NOTIF_ID, NotificationHelper.buildOngoing(this, "正在启动…"))
        scope.launch {
            val settings = SettingsStore.load(this@MicListenerService)
            SessionRepository.setServiceState(ServiceState.LOADING_MODEL)
            SessionRepository.setServiceError(null)

            val result = ModelManager.ensureRecognizer(this@MicListenerService, settings.silenceMs)
            if (!running) return@launch
            if (result.isFailure) {
                SessionRepository.setServiceError(result.exceptionOrNull()?.message ?: "模型加载失败")
                SessionRepository.setServiceState(ServiceState.ERROR)
                stopSelf()
                return@launch
            }

            NotificationHelper.updateOngoing(this@MicListenerService, "正在准备麦克风…")
            val recognizer = result.getOrThrow()
            streamThread = thread(name = "mic-stream", priority = Thread.MAX_PRIORITY) {
                runStream(recognizer)
            }
        }
    }

    private fun runStream(recognizer: OnlineRecognizer) {
        var stream: OnlineStream? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) throw IllegalStateException("设备不支持录音")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, FRAME_SAMPLES * 2),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("麦克风初始化失败")
            recorder = rec

            runCatching { NoiseSuppressor.create(rec.audioSessionId) }
                .getOrNull()
                ?.let { it.enabled = true; noiseSuppressor = it }

            rec.startRecording()

            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lecturelistener:mic")
                .apply { acquire(6 * 60 * 60 * 1000L) }

            stream = recognizer.createStream()
            val buffer = ShortArray(FRAME_SAMPLES)

            SessionRepository.setServiceState(ServiceState.LISTENING)
            NotificationHelper.updateOngoing(this, "正在听课 · 已识别 0 句")

            while (running && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                var text = recognizer.getResult(stream).text
                val isEndpoint = recognizer.isEndpoint(stream)

                if (isEndpoint) {
                    // 流式 paraformer 需要尾部填充以获得最后几个字的右上下文
                    val tail = FloatArray((TAIL_PADDING_SEC * SAMPLE_RATE).toInt())
                    stream.acceptWaveform(tail, sampleRate = SAMPLE_RATE)
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream)
                    }
                    text = recognizer.getResult(stream).text

                    val trimmed = text.trim()
                    if (trimmed.length >= MIN_SEGMENT_CHARS) emit(trimmed)
                    recognizer.reset(stream)
                    SessionRepository.setPartial("")
                } else {
                    SessionRepository.setPartial(text)
                }
            }
        } catch (t: Throwable) {
            if (running) {
                SessionRepository.setServiceError(t.message ?: "识别异常")
                SessionRepository.setServiceState(ServiceState.ERROR)
            }
        } finally {
            runCatching { stream?.release() }
            stream = null
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { noiseSuppressor?.release() }
            noiseSuppressor = null
            wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
            wakeLock = null
            if (running && SessionRepository.serviceState.value != ServiceState.ERROR) {
                SessionRepository.setServiceState(ServiceState.IDLE)
            }
        }
    }

    /** 完整句子产出：写入转写区 + 送入问句管线 + 更新常驻通知 */
    private fun emit(text: String) {
        SessionRepository.appendSegment(text)
        val count = SessionRepository.segments.value.size
        NotificationHelper.updateOngoing(this, "正在听课 · 已识别 $count 句")
        AnswerEngine.onSegment(text)
    }

    override fun onDestroy() {
        running = false
        streamThread?.let { t -> runCatching { t.join(1500) } }
        streamThread = null
        runCatching { recorder?.release() } // 解除 read 阻塞
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
        if (SessionRepository.serviceState.value != ServiceState.ERROR) {
            SessionRepository.setServiceState(ServiceState.IDLE)
        }
        SessionRepository.setPartial("")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
