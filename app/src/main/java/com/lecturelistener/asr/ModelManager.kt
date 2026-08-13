package com.lecturelistener.asr

import android.content.Context
import com.lecturelistener.data.SettingsStore
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Paraformer 双语模型管理：encoder.int8.onnx / decoder.int8.onnx / tokens.txt
 * 三个文件逐个下载（hf-mirror 国内镜像优先），全部就绪后创建识别器。
 *
 * 模型目录：getExternalFilesDir/models/paraformer-zh/（应用专属目录，无需存储权限）
 */
object ModelManager {

    sealed interface ModelState {
        data object Idle : ModelState
        data class Downloading(val pct: Int) : ModelState
        data object Ready : ModelState
        data class Error(val message: String) : ModelState
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedSilenceMs: Int = -1

    const val MODEL_NAME = "paraformer-zh"

    private val files = listOf(
        SherpaEngine.FILE_ENCODER,
        SherpaEngine.FILE_DECODER,
        SherpaEngine.FILE_TOKENS,
    )

    /** 下载源前缀（顺序尝试）：hf-mirror 国内镜像优先，官方 HF 兜底 */
    private val defaultBases = listOf(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main",
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models/$MODEL_NAME")

    /** 三个模型文件是否齐全 */
    fun isReady(ctx: Context): Boolean {
        val d = modelDir(ctx)
        return files.all { f -> File(d, f).isFile && File(d, f).length() > 0 }
    }

    /** 准备模型：已有则通过；否则多源下载 */
    suspend fun prepare(ctx: Context): Result<Unit> =
        mutex.withLock {
            if (isReady(ctx)) {
                _state.value = ModelState.Ready
                return Result.success(Unit)
            }
            downloadAll(ctx)
        }

    /** 确保识别器已创建（服务启动时调用；同参数直接复用） */
    suspend fun ensureRecognizer(ctx: Context, silenceMs: Int): Result<OnlineRecognizer> =
        mutex.withLock {
            if (recognizer != null && loadedSilenceMs == silenceMs) {
                _state.value = ModelState.Ready
                return Result.success(recognizer!!)
            }
            val prepared = downloadAll(ctx)
            if (prepared.isFailure) {
                return Result.failure(prepared.exceptionOrNull() ?: IOException("模型不可用"))
            }
            val rec = try {
                withContext(Dispatchers.IO) {
                    SherpaEngine.createRecognizer(modelDir(ctx), silenceMs)
                }
            } catch (t: Throwable) {
                _state.value = ModelState.Error("识别器初始化失败：${t.message ?: "未知错误"}")
                return Result.failure(t)
            }
            recognizer?.let { runCatching { it.release() } }
            recognizer = rec
            loadedSilenceMs = silenceMs
            _state.value = ModelState.Ready
            Result.success(rec)
        }

    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedSilenceMs = -1
    }

    /** 逐个下载缺失的模型文件（多源 fallback，总进度百分比） */
    private suspend fun downloadAll(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val custom = SettingsStore.load(ctx).modelBaseUrl.trim().trimEnd('/')
        val bases = if (custom.isNotBlank()) listOf(custom) + defaultBases else defaultBases
        val dir = modelDir(ctx)
        dir.mkdirs()

        val missing = files.filter { f -> !File(dir, f).isFile || File(dir, f).length() <= 0 }
        if (missing.isEmpty()) {
            _state.value = ModelState.Ready
            return@withContext Result.success(Unit)
        }

        // 先探测总大小（用于进度），探测失败则按文件数百分比
        var totalBytes = 0L
        val sizes = HashMap<String, Long>()
        for (f in missing) {
            var got = 0L
            for (base in bases) {
                got = probeSize("$base/$f") ?: 0L
                if (got > 0) break
            }
            if (got > 0) {
                sizes[f] = got
                totalBytes += got
            }
        }

        var doneBytes = 0L
        var doneFiles = 0
        for (f in missing) {
            val target = File(dir, f)
            var ok = false
            val errors = StringBuilder()
            for (base in bases) {
                val url = "$base/$f"
                try {
                    _state.value = ModelState.Downloading(progress(doneBytes, doneFiles, missing.size, totalBytes))
                    val request = Request.Builder().url(url).build()
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("空响应")
                        val tmp = File(dir, "$f.part")
                        tmp.delete()
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    doneBytes += n
                                    _state.value = ModelState.Downloading(
                                        progress(doneBytes, doneFiles, missing.size, totalBytes),
                                    )
                                }
                            }
                        }
                        if (tmp.length() <= 0) throw IOException("下载内容为空")
                        tmp.renameTo(target)
                    }
                    ok = true
                    break
                } catch (t: Throwable) {
                    errors.append("$url → ${t.message}\n")
                }
            }
            if (!ok) {
                _state.value = ModelState.Error("模型下载失败：\n$errors")
                return@withContext Result.failure(IOException("模型下载失败：$errors"))
            }
            doneFiles++
        }

        if (isReady(ctx)) {
            _state.value = ModelState.Ready
            Result.success(Unit)
        } else {
            _state.value = ModelState.Error("模型文件不完整，请重试")
            Result.failure(IOException("模型文件不完整"))
        }
    }

    private fun progress(doneBytes: Long, doneFiles: Int, totalFiles: Int, totalBytes: Long): Int {
        val pct = if (totalBytes > 0) {
            (doneBytes * 100 / totalBytes).toInt()
        } else {
            (doneFiles * 100 / totalFiles).toInt()
        }
        return pct.coerceIn(0, 99)
    }

    private fun probeSize(url: String): Long? {
        return try {
            val request = Request.Builder().url(url).head().build()
            http.newCall(request).execute().use { resp ->
                val len = resp.body?.contentLength() ?: -1
                if (resp.isSuccessful && len > 0) len else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}
