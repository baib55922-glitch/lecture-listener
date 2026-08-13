package com.lecturelistener.llm

import com.lecturelistener.AppContext
import com.lecturelistener.data.CardStatus
import com.lecturelistener.data.QuestionCard
import com.lecturelistener.data.SessionRepository
import com.lecturelistener.data.SettingsStore
import com.lecturelistener.detect.QuestionRules
import com.lecturelistener.detect.TextSimilarity
import com.lecturelistener.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 问题 → 答案 管线（独立于前台服务，卡片重试也走这里）。
 * 流程：规则预过滤 → 去重 → LLM 判定 → LLM 答题 → 更新卡片 → 通知提醒
 */
object AnswerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = DeepSeekClient()

    private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L
    private const val SIM_THRESHOLD = 0.85

    /** 服务每切出一个完整句子调用一次 */
    fun onSegment(raw: String) {
        val ctx = AppContext.ctx
        val settings = SettingsStore.load(ctx)
        val candidate = QuestionRules.matches(raw) || settings.strictMode
        if (!candidate) return
        if (isDuplicate(raw)) return
        val card = SessionRepository.addCard(raw)
        scope.launch { process(card) }
    }

    /** 卡片失败后重试 */
    fun retry(cardId: Long) {
        val card = SessionRepository.cards.value.firstOrNull { it.id == cardId } ?: return
        SessionRepository.updateCard(card.copy(status = CardStatus.JUDGING, error = ""))
        scope.launch { process(card.copy(status = CardStatus.JUDGING, error = "")) }
    }

    /** 与已有卡片/上一句相似则跳过（防止同一问题重复出卡） */
    private fun isDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        val cards = SessionRepository.cards.value
        if (cards.any { now - it.time < DEDUPE_WINDOW_MS && TextSimilarity.similarity(raw, it.rawText) >= SIM_THRESHOLD }) {
            return true
        }
        // raw 已经是最后一条转写，取倒数第二条对比
        val prev = SessionRepository.segments.value.dropLast(1).lastOrNull()?.text ?: return false
        return TextSimilarity.similarity(raw, prev) >= SIM_THRESHOLD
    }

    private suspend fun process(initial: QuestionCard) {
        var card = initial
        val settings = SettingsStore.load(AppContext.ctx)

        if (settings.apiKey.isBlank()) {
            card = card.copy(status = CardStatus.FAILED, error = "未设置 DeepSeek API Key（设置页填写）")
            SessionRepository.updateCard(card)
            return
        }

        // 1) LLM 判定
        val judge = client.judgeQuestion(settings.apiKey, card.rawText)
        if (!judge.ok) {
            card = card.copy(status = CardStatus.FAILED, error = "问题判定失败：${judge.error}")
            SessionRepository.updateCard(card)
            return
        }
        if (!judge.isQuestion) {
            SessionRepository.removeCard(card.id) // 误报，撤掉卡片
            return
        }

        // 2) 生成答案
        card = card.copy(question = judge.question, status = CardStatus.GENERATING)
        SessionRepository.updateCard(card)

        var ans = client.answer(settings.apiKey, judge.question)
        if (!ans.ok) ans = client.answer(settings.apiKey, judge.question) // 网络抖动重试一次

        if (ans.ok) {
            card = card.copy(answer = ans.text, status = CardStatus.DONE)
            SessionRepository.updateCard(card)
            NotificationHelper.postQuestionAlert(AppContext.ctx, card, settings.vibrate)
        } else {
            card = card.copy(status = CardStatus.FAILED, error = "答案生成失败：${ans.error}")
            SessionRepository.updateCard(card)
        }
    }
}
