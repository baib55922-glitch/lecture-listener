package com.lecturelistener.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话级状态仓库（单例）。
 * 前台服务写入，UI 订阅；仅保留本次会话，不落盘。
 */
object SessionRepository {

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _cards = MutableStateFlow<List<QuestionCard>>(emptyList())
    val cards: StateFlow<List<QuestionCard>> = _cards.asStateFlow()

    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

    private var idCounter = 0L

    private fun nextId(): Long = ++idCounter

    fun setServiceState(state: ServiceState) {
        _serviceState.value = state
    }

    fun setServiceError(message: String?) {
        _serviceError.value = message
    }

    fun setPartial(text: String) {
        _partial.value = text
    }

    fun appendSegment(text: String) {
        _segments.update { list ->
            (list + TranscriptSegment(nextId(), text, System.currentTimeMillis()))
                .let { if (it.size > 300) it.drop(it.size - 300) else it }
        }
    }

    fun addCard(rawText: String): QuestionCard {
        val card = QuestionCard(
            id = nextId(),
            rawText = rawText,
            question = rawText,
            answer = "",
            error = "",
            status = CardStatus.JUDGING,
            time = System.currentTimeMillis(),
        )
        _cards.update { it + card }
        return card
    }

    fun updateCard(card: QuestionCard) {
        _cards.update { list -> list.map { if (it.id == card.id) card else it } }
    }

    fun removeCard(id: Long) {
        _cards.update { list -> list.filterNot { it.id == id } }
    }

    fun clearSession() {
        _segments.value = emptyList()
        _partial.value = ""
        _cards.value = emptyList()
    }
}
