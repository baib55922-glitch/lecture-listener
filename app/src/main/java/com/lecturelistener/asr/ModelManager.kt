package com.lecturelistener.asr

import android.content.Context
import com.lecturelistener.data.AppSettings
import com.lecturelistener.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Vosk 模型管理：内置小模型解包、大模型下载（带进度）、加载。
 *
 * 模型目录：getExternalFilesDir/models/<模型名>/（应用专属目录，无需存储权限）
 */
object ModelManager {

    sealed interface ModelState {
        data object Idle : ModelState
        data class Downloading(val pct: Int) : ModelState
        data object Unzipping : ModelState
        data object Ready : ModelState
        data class Error(val message: String) : ModelState
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var model: Model? = null

    @Volatile
    private var loadedName: String? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 模型下载源列表（按顺序尝试，第一个连通的生效）。
     * alphacephei 官方源国内访问不稳定，备选 GitHub 镜像与 ghproxy 加速代理。
     */
    private val mirrorBases = listOf(
        "https://alphacephei.com/vosk/models",
        "https://raw.githubusercontent.com/kercre123/vosk-models/master",
        "https://mirror.ghproxy.com/https://raw.githubusercontent.com/kercre123/vosk-models/master",
        "https://ghproxy.net/https://raw.githubusercontent.com/kercre123/vosk-models/master",
        "https://gh-proxy.com/https://raw.githubusercontent.com/kercre123/vosk-models/master",
    )

    fun modelDir(ctx: Context, name: String): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models/$name")

    /** 模型目录是否完整（含 am/conf 子目录） */
    fun isReady(ctx: Context, name: String): Boolean {
        val d = modelDir(ctx, name)
        return d.isDirectory && File(d, "am").isDirectory && File(d, "conf").isDirectory
    }

    /** APK assets 里是否打包了该模型 */
    fun hasBundled(ctx: Context, name: String): Boolean = try {
        ctx.assets.list("models/$name")?.isNotEmpty() == true
    } catch (t: Throwable) {
        false
    }

    /** 准备模型：本地已有则直接通过；否则从内置资源解包或下载 */
    suspend fun prepare(ctx: Context, name: String): Result<Unit> =
        mutex.withLock { prepareLocked(ctx, name) }

    /** 确保指定模型已加载（服务启动时调用；已加载同名模型则直接复用） */
    suspend fun ensureLoaded(ctx: Context, name: String): Result<Model> =
        mutex.withLock {
            if (model != null && loadedName == name) {
                _state.value = ModelState.Ready
                return Result.success(model!!)
            }
            val prepared = prepareLocked(ctx, name)
            if (prepared.isFailure) {
                return Result.failure(prepared.exceptionOrNull() ?: IOException("模型不可用"))
            }
            val dir = modelDir(ctx, name)
            val m = try {
                withContext(Dispatchers.IO) { Model(dir.absolutePath) }
            } catch (t: Throwable) {
                _state.value = ModelState.Error("模型加载失败：${t.message ?: "未知错误"}")
                return Result.failure(t)
            }
            // 切换模型时释放旧模型内存（此刻服务尚未创建识别器，关闭是安全的）
            val old = model
            if (old != null && loadedName != name) {
                runCatching { old.close() }
            }
            model = m
            loadedName = name
            _state.value = ModelState.Ready
            Result.success(m)
        }

    /** 已持锁版本：内部使用，调用方需保证互斥 */
    private suspend fun prepareLocked(ctx: Context, name: String): Result<Unit> {
        if (isReady(ctx, name)) {
            _state.value = ModelState.Ready
            return Result.success(Unit)
        }
        return if (name == AppSettings.MODEL_SMALL && hasBundled(ctx, name)) {
            copyFromAssets(ctx, name)
        } else {
            downloadLocked(ctx, name)
        }
    }

    /** 从 APK assets 递归复制内置模型到外部目录 */
    private suspend fun copyFromAssets(ctx: Context, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                _state.value = ModelState.Downloading(0)
                val dest = modelDir(ctx, name)
                dest.mkdirs()
                val root = "models/$name"

                fun copyItem(path: String) {
                    // AssetManager 不区分目录/文件：尝试按文件打开，失败则视为目录
                    val input = try { ctx.assets.open(path) } catch (t: Throwable) { null }
                    if (input != null) {
                        val rel = path.removePrefix("$root/")
                        val f = File(dest, rel)
                        f.parentFile?.mkdirs()
                        input.use { src -> f.outputStream().use { out -> src.copyTo(out) } }
                    } else {
                        val children = ctx.assets.list(path) ?: emptyArray()
                        children.forEach { copyItem("$path/$it") }
                    }
                }

                copyItem(root)
                if (isReady(ctx, name)) {
                    _state.value = ModelState.Ready
                    Result.success(Unit)
                } else {
                    _state.value = ModelState.Error("内置模型解包不完整，请重新下载")
                    Result.failure(IOException("内置模型解包不完整"))
                }
            } catch (t: Throwable) {
                _state.value = ModelState.Error("内置模型解包失败：${t.message ?: "未知错误"}")
                Result.failure(t)
            }
        }

    /** 下载 zip 并解压（多源自动切换 + 进度） */
    private suspend fun downloadLocked(ctx: Context, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val custom = SettingsStore.load(ctx).modelBaseUrl.trim().trimEnd('/')
            val bases = if (custom.isNotBlank()) listOf(custom) + mirrorBases else mirrorBases
            val destDir = modelDir(ctx, name)
            val tmpZip = File(destDir.parentFile, "$name.zip.part")
            try {
                tmpZip.parentFile?.mkdirs()

                var downloaded = false
                val errors = StringBuilder()
                for (base in bases) {
                    val url = "$base/$name.zip"
                    try {
                        _state.value = ModelState.Downloading(0)
                        val request = Request.Builder().url(url).build()
                        http.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("空响应")
                            val total = body.contentLength()
                            var done = 0L
                            val buf = ByteArray(64 * 1024)
                            tmpZip.delete() // 清掉上一个失败源的残留
                            body.byteStream().use { input ->
                                tmpZip.outputStream().use { out ->
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        out.write(buf, 0, n)
                                        done += n
                                        if (total > 0) {
                                            val pct = (done * 95 / total).toInt().coerceIn(0, 95)
                                            _state.value = ModelState.Downloading(pct)
                                        }
                                    }
                                }
                            }
                        }
                        downloaded = true
                        break
                    } catch (t: Throwable) {
                        errors.append("$url → ${t.message}\n")
                        tmpZip.delete()
                    }
                }
                if (!downloaded) {
                    throw IOException("所有下载源均不可用：\n$errors")
                }

                _state.value = ModelState.Unzipping
                destDir.mkdirs()
                unzip(tmpZip, destDir)
                tmpZip.delete()

                if (!isReady(ctx, name)) {
                    _state.value = ModelState.Error("模型文件不完整，请删除后重试")
                    Result.failure(IOException("模型文件不完整"))
                } else {
                    _state.value = ModelState.Ready
                    Result.success(Unit)
                }
            } catch (t: Throwable) {
                tmpZip.delete()
                _state.value = ModelState.Error(t.message ?: "下载失败")
                Result.failure(t)
            }
        }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("/") || name.contains("..")) continue // zip-slip 防护
                val target = File(destDir, name)
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zis.copyTo(out) }
                zis.closeEntry()
            }
        }
    }
}
