package com.lecturelistener.detect

/**
 * 问句规则预过滤（零成本，挡住大部分陈述句）。
 * 命中 → 交给 LLM 最终确认；未命中 → 直接丢弃（严格模式除外）。
 */
object QuestionRules {

    private val strong = listOf(
        // 问号
        Regex("[?？]"),
        // 疑问词
        Regex("(什么|怎么|为什么|为何|如何|哪[一几]?[个些里位]?|谁|多少|多长|多久|几|啥|咋|是否|能否|可否|能不能|可不可以|会不会|有没有|是不是|对不对|知不知道|听说过吗)"),
        // 句内/句尾"吗"
        Regex("吗"),
        // 祈使式提问
        Regex("(谁来说说|谁来回答|谁愿意|有人知道吗|大家(想|思考|讨论)一下|说说(你|大家|自己)的(看法|想法|观点|理解|思路)|思考一下|想一下|讲一讲|解释一下|说一下|举个(例|例子))"),
    )

    // 弱信号：句尾"呢"（需句子足够长才采纳）
    private val weak = listOf(Regex("呢[？?。！!、，,\\s]*$"))

    fun matches(text: String): Boolean {
        if (text.isBlank() || text.length < 3) return false
        if (strong.any { it.containsMatchIn(text) }) return true
        return text.length >= 8 && weak.any { it.containsMatchIn(text) }
    }

    /** 本地轻量规范化（用于卡片预览；LLM 会再次规范化） */
    fun normalize(text: String): String {
        var t = text.trim()
        var guard = 0
        while (guard < 6) {
            guard++
            val stripped = t.replaceFirst(
                Regex("^(呃+|啊+|嗯+|那个|这个|就是说|那么|然后|好|来|我们|大家|同学们|各位同学|接下来|请问)"),
                "",
            )
            if (stripped == t || stripped.length < 2) break
            t = stripped
        }
        t = t.replace(Regex("[\\s，,]+"), " ").trim()
        t = t.replace(Regex("[呃啊嗯]+$"), "")
        return t.ifBlank { text.trim() }
    }
}
