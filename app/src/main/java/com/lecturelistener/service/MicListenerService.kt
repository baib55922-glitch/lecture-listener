package com.lecturelistener.service

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.lecturelistener.asr.ModelManager
import com.lecturelistener.asr.VoskEngine
import com.lecturelistener.audio.AudioUtil
import com.lecturelistener.audio.Segmenter
import com.lecturelistener.data.ServiceState
import com.lecturelistener.data.SessionRepository
import com.lecturelistener.data.SettingsStore
import com.lecturelistener.llm.AnswerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * 前台服务：麦克风 → Vosk 离线识别 → 断句 → 问句管线。
 * 息屏/切后台持续运行；通知栏（小米超级岛胶囊）常驻。
 */
class MicListenerService : Service() {

    companion object {
        const val ACTION_START = "com.lecturelistener.action.START"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100ms @16kHz 16bit mono
        private const val MIN_SEGMENT_CHARS = 3
        private const val MAX_SEGMENT_MS = 25_000L

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
    private var recognizer: Recognizer? = null
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

            val result = ModelManager.ensureLoaded(this@MicListenerService, settings.model)
            if (!running) return@launch
            if (result.isFailure) {
                SessionRepository.setServiceError(result.exceptionOrNull()?.message ?: "模型加载失败")
                SessionRepository.setServiceState(ServiceState.ERROR)
                stopSelf()
                return@launch
            }

            NotificationHelper.updateOngoing(this@MicListenerService, "正在准备麦克风…")
            val model = result.getOrThrow()
            streamThread = thread(name = "mic-stream", priority = Thread.MAX_PRIORITY) {
                runStream(model)
            }
        }
    }

    private fun runStream(model: Model) {
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
                maxOf(minBuf * 2, FRAME_BYTES * 2),
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

            val settings = SettingsStore.load(this)
            var vosk = VoskEngine.create(model)
            recognizer = vosk
            val segmenter = Segmenter(settings.silenceMs.toLong(), MAX_SEGMENT_MS, MIN_SEGMENT_CHARS)
            val frame = ByteArray(FRAME_BYTES)

            // 前 1.5s 校准环境噪声底噪，得到自适应语音阈值
            var calibrated = false
            var floorSum = 0.0
            var floorN = 0
            var threshold = 700.0
            val calibrateUntil = SystemClock.uptimeMillis() + 1500

            SessionRepository.setServiceState(ServiceState.LISTENING)
            NotificationHelper.updateOngoing(this, "正在听课 · 已识别 0 句")

            while (running && !Thread.currentThread().isInterrupted) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val rms = AudioUtil.rms(frame, n)
                val now = SystemClock.uptimeMillis()

                if (!calibrated) {
                    floorSum += rms
                    floorN++
                    if (now >= calibrateUntil && floorN > 0) {
                        val floor = floorSum / floorN
                        threshold = maxOf(300.0, floor * 4.0)
                        calibrated = true
                    }
                }

                val isFinal = vosk.acceptWaveForm(frame, n)
                val text = if (isFinal) {
                    VoskEngine.parseResult(vosk.getResult())
                } else {
                    VoskEngine.parsePartial(vosk.getPartialResult())
                }
                segmenter.notePartial(text)
                SessionRepository.setPartial(text)

                if (calibrated && rms >= threshold) segmenter.noteVoice(now)

                var finalized = false
                if (isFinal) {
                    val t = segmenter.take()
                    if (t.isNotEmpty()) {
                        if (t.length >= MIN_SEGMENT_CHARS) emit(t)
                        vosk = VoskEngine.create(model)
                        recognizer = vosk
                        finalized = true
                    }
                }
                if (!finalized && calibrated && segmenter.shouldFinalize(now)) {
                    val t = segmenter.take()
                    if (t.isNotEmpty() && t.length >= MIN_SEGMENT_CHARS) {
                        emit(t)
                        vosk = VoskEngine.create(model)
                        recognizer = vosk
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                SessionRepository.setServiceError(t.message ?: "录音异常")
                SessionRepository.setServiceState(ServiceState.ERROR)
            }
        } finally {
            runCatching { recognizer?.close() }
            recognizer = null
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
        runCatching { recognizer?.close() }
        recognizer = null
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
