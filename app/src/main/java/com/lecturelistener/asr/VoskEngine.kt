package com.lecturelistener.asr

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Vosk 离线识别薄封装 */
object VoskEngine {

    const val SAMPLE_RATE = 16000f

    fun create(model: Model): Recognizer = Recognizer(model, SAMPLE_RATE)

    /** 解析 final 结果 JSON：{"text": "..."} */
    fun parseResult(json: String): String = parseText(json, "text")

    /** 解析 partial 结果 JSON：{"partial": "..."} */
    fun parsePartial(json: String): String = parseText(json, "partial")

    private fun parseText(json: String, key: String): String = try {
        JSONObject(json.ifBlank { "{}" }).optString(key).orEmpty().trim()
    } catch (t: Throwable) {
        ""
    }
}
