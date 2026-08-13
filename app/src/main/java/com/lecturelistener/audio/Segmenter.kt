package com.lecturelistener.audio

/**
 * 断句状态机：判断"老师这句话是否说完了"。
 *
 * 触发条件（满足其一即断句）：
 * 1. Vosk 端点检测出 final 结果（由调用方在 acceptWaveForm 返回 true 时取走文本）；
 * 2. 静音持续达到 [silenceMs]；
 * 3. 单句超过 [maxSegmentMs] 强制切分。
 */
class Segmenter(
    private val silenceMs: Long,
    private val maxSegmentMs: Long = 25_000L,
    private val minChars: Int = 3,
) {
    private var lastVoice = -1L   // 上次检测到人声的时刻
    private var segStart = -1L    // 当前句开始时刻
    private var text = ""         // 当前累积的最新 partial 文本

    /** 每帧更新最新 partial 文本 */
    fun notePartial(t: String) {
        if (t.isNotBlank()) text = t
    }

    /** 检测到人声（RMS 超阈值）时调用 */
    fun noteVoice(now: Long) {
        if (lastVoice < 0) segStart = now
        lastVoice = now
    }

    /** 是否应该把当前累积文本作为完整句子发出 */
    fun shouldFinalize(now: Long): Boolean {
        if (text.length < minChars) return false
        if (lastVoice < 0) return false
        return (now - lastVoice >= silenceMs) || (now - segStart >= maxSegmentMs)
    }

    /** 取走当前句子文本并复位 */
    fun take(): String {
        val t = text.trim()
        reset()
        return t
    }

    fun reset() {
        lastVoice = -1
        segStart = -1
        text = ""
    }
}
