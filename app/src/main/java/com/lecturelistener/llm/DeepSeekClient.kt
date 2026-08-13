package com.lecturelistener.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 通用 API 结果 */
data class ApiResult(val ok: Boolean, val text: String = "", val error: String = "")

/** 问题判定结果 */
data class JudgeResult(val ok: Boolean, val isQuestion: Boolean = false, val question: String = "", val error: String = "")

/**
 * DeepSeek API 客户端（OpenAI 兼容 /chat/completions）。
 * 职责：① 判断老师的话是不是问题；② 生成简明答案；③ 测试连接。
 */
class DeepSeekClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 判定一句话是否是向学生提出的问题 */
    suspend fun judgeQuestion(apiKey: String, sentence: String): JudgeResult =
        withContext(Dispatchers.IO) {
            val system = "你是课堂监听助手。判断老师刚说的这句话是否是一个\u201c向学生提出的问题\u201d。" +
                "判断标准：是疑问句，或带有提问意图的祈使句（如\u201c谁来说说\u201d\u201c大家思考一下\u201d）。" +
                "输出严格 JSON：{\"is_question\": true或false, \"question\": \"规范化后的问题文本\"}。" +
                "规范化要求：去掉口头语、重复词、语气词（如\u201c呃\u201d\u201c那个\u201d\u201c对不对啊\u201d），" +
                "合并成一句通顺的问题，保留原意；若 is_question 为 false，question 输出空字符串。"
            val body = chatBody(
                system = system,
                user = "老师刚说的话：\n「$sentence」",
                temperature = 0.1,
                maxTokens = 150,
                jsonMode = true,
            )
            val r = call(apiKey, body)
            if (!r.ok) {
                JudgeResult(false, error = r.error)
            } else {
                val obj = try { JSONObject(extractJson(r.text)) } catch (t: Throwable) { null }
                val isQ = obj?.optBoolean("is_question", false) ?: false
                val q = obj?.optString("question", "")?.trim().orEmpty()
                JudgeResult(true, isQ, q.ifEmpty { sentence.trim() }, "")
            }
        }

    /** 生成简明答案 */
    suspend fun answer(apiKey: String, question: String): ApiResult =
        withContext(Dispatchers.IO) {
            val system = "你是大学课堂上的学生助手。老师刚刚提出了一个问题，你正在站起来回答。\n" +
                "要求：\n" +
                "1. 直接给出答案本身，可附一句简短理由；\n" +
                "2. 全文不超过 120 字；\n" +
                "3. 不要称呼任何人，不要任何客套语（\u201c好的\u201d\u201c我来回答\u201d\u201c谢谢\u201d），" +
                "不要\u201c根据材料\u201d\u201c我认为\u201d之类的套话，开口就是答案；\n" +
                "4. 问题若信息不全，给出最合理的推测答案，不要反问；\n" +
                "5. 术语保留英文缩写（如 API、GDP），其余用中文。"
            val body = chatBody(
                system = system,
                user = question,
                temperature = 0.3,
                maxTokens = 300,
                jsonMode = false,
            )
            call(apiKey, body)
        }

    /** 测试 API Key 是否可用；ok=true 表示连通 */
    suspend fun testConnection(apiKey: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = chatBody(
                system = "你是连接测试助手。",
                user = "回复两个字：正常",
                temperature = 0.0,
                maxTokens = 8,
                jsonMode = false,
            )
            call(apiKey, body)
        }

    private fun chatBody(system: String, user: String, temperature: Double, maxTokens: Int, jsonMode: Boolean): JSONObject {
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                },
            )
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    private fun call(apiKey: String, body: JSONObject): ApiResult {
        return try {
            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    } catch (t: Throwable) {
                        null
                    }
                    ApiResult(false, error = "HTTP ${resp.code}: ${msg ?: raw.take(80)}")
                } else {
                    val content = try {
                        JSONObject(raw).getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .optString("content").orEmpty()
                    } catch (t: Throwable) {
                        ""
                    }
                    if (content.isBlank()) ApiResult(false, error = "响应内容为空")
                    else ApiResult(true, content)
                }
            }
        } catch (e: IOException) {
            ApiResult(false, error = e.message ?: "网络错误")
        } catch (t: Throwable) {
            ApiResult(false, error = t.message ?: "未知错误")
        }
    }

    private fun extractJson(s: String): String {
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        return if (i >= 0 && j > i) s.substring(i, j + 1) else s
    }
}
