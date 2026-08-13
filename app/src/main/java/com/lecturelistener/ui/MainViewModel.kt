package com.lecturelistener.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecturelistener.AppContext
import com.lecturelistener.asr.ModelManager
import com.lecturelistener.data.AppSettings
import com.lecturelistener.data.SessionRepository
import com.lecturelistener.data.SettingsStore
import com.lecturelistener.llm.AnswerEngine
import com.lecturelistener.llm.DeepSeekClient
import com.lecturelistener.service.MicListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    val segments = SessionRepository.segments
    val partial = SessionRepository.partial
    val cards = SessionRepository.cards
    val serviceState = SessionRepository.serviceState
    val serviceError = SessionRepository.serviceError
    val modelState = ModelManager.state

    private val _settings = MutableStateFlow(SettingsStore.load(AppContext.ctx))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data class Result(val ok: Boolean, val message: String) : TestState
    }

    private val _testing = MutableStateFlow<TestState>(TestState.Idle)
    val testing: StateFlow<TestState> = _testing.asStateFlow()

    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
        SettingsStore.save(AppContext.ctx, settings)
    }

    fun startListening(context: Context) {
        MicListenerService.start(context)
    }

    fun stopListening(context: Context) {
        MicListenerService.stop(context)
    }

    fun clearSession() = SessionRepository.clearSession()

    fun deleteCard(id: Long) = SessionRepository.removeCard(id)

    fun retryCard(id: Long) = AnswerEngine.retry(id)

    fun testConnection() {
        val key = _settings.value.apiKey
        if (key.isBlank()) {
            _testing.value = TestState.Result(false, "请先填写 API Key")
            return
        }
        _testing.value = TestState.Running
        viewModelScope.launch {
            val r = DeepSeekClient().testConnection(key)
            _testing.value = TestState.Result(r.ok, if (r.ok) "连接成功 ✓" else "连接失败：${r.error}")
        }
    }

    fun downloadSelectedModel() {
        viewModelScope.launch { ModelManager.prepare(AppContext.ctx, _settings.value.model) }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}
