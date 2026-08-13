package com.lecturelistener.data

/** 一条已完成的转写片段 */
data class TranscriptSegment(
    val id: Long,
    val text: String,
    val time: Long,
)

/** 问题卡片状态 */
enum class CardStatus {
    JUDGING,    // 正在让 LLM 确认是否是问题
    GENERATING, // 已确认，正在生成答案
    DONE,       // 答案已生成
    FAILED,     // 生成失败，可重试
}

/** 页面底部的问题卡片 */
data class QuestionCard(
    val id: Long,
    val rawText: String,      // 老师原话（未规范化）
    val question: String,     // LLM 规范化后的问题（卡片标题）
    val answer: String,       // 答案正文
    val error: String,        // 失败原因
    val status: CardStatus,
    val time: Long,
)

/** 监听服务状态 */
enum class ServiceState {
    IDLE,          // 未运行
    LOADING_MODEL, // 正在加载识别模型
    LISTENING,     // 正在监听
    ERROR,         // 出错（见 serviceError）
}
