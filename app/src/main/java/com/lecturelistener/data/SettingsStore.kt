package com.lecturelistener.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 应用设置 */
data class AppSettings(
    val apiKey: String = "",
    val model: String = MODEL_SMALL,
    val silenceMs: Int = 1200,          // 断句静音阈值（毫秒）
    val strictMode: Boolean = false,    // 严格判定：所有句子送 LLM
    val vibrate: Boolean = true,        // 识别到问题时震动提醒
    val modelBaseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val MODEL_SMALL = "vosk-model-small-cn-0.22"
        const val MODEL_LARGE = "vosk-model-cn-0.22"
        const val DEFAULT_BASE_URL = "https://alphacephei.com/vosk/models"
    }
}

/**
 * 设置存储：优先 EncryptedSharedPreferences（API Key 加密），
 * 个别机型密钥库异常时回退到普通 SharedPreferences。
 */
object SettingsStore {

    private const val PREFS = "settings"
    private const val SECURE_PREFS = "secure_settings"

    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_SILENCE = "silence_ms"
    private const val KEY_STRICT = "strict_mode"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_BASE_URL = "model_base_url"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        val p = try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            // 密钥库异常（个别国产 ROM）→ 回退明文存储，保证功能可用
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        cached = p
        return p
    }

    fun load(ctx: Context): AppSettings {
        val p = prefs(ctx)
        return AppSettings(
            apiKey = p.getString(KEY_API, "") ?: "",
            model = p.getString(KEY_MODEL, AppSettings.MODEL_SMALL) ?: AppSettings.MODEL_SMALL,
            silenceMs = p.getInt(KEY_SILENCE, 1200),
            strictMode = p.getBoolean(KEY_STRICT, false),
            vibrate = p.getBoolean(KEY_VIBRATE, true),
            modelBaseUrl = p.getString(KEY_BASE_URL, AppSettings.DEFAULT_BASE_URL)
                ?: AppSettings.DEFAULT_BASE_URL,
        )
    }

    fun save(ctx: Context, settings: AppSettings) {
        prefs(ctx).edit()
            .putString(KEY_API, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_SILENCE, settings.silenceMs)
            .putBoolean(KEY_STRICT, settings.strictMode)
            .putBoolean(KEY_VIBRATE, settings.vibrate)
            .putString(KEY_BASE_URL, settings.modelBaseUrl)
            .apply()
    }
}
