package com.example.voicemail

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class Command(
    val action: String,
    val query: String,
    val to: String,
    val subject: String,
    val body: String
)

/** Turns a spoken sentence into a structured Gmail command. Only the transcript is sent to Claude. */
object ClaudeClient {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun system() = """
You convert a spoken Gmail request into ONE JSON object and nothing else (no prose, no code fences).
Today is ${LocalDate.now()}.
Schema:
{"action":"read_unread|search|mark_read|archive|trash|send|unknown",
 "query":"Gmail search syntax",
 "to":"email address or empty",
 "subject":"",
 "body":""}
Rules:
- read_unread: default query "is:unread in:inbox". Use category:promotions, from:, newer_than:, etc. when the user narrows it.
- search: find emails (e.g. from:swiggy newer_than:7d).
- mark_read, archive, trash: query must target exactly what the user described; always include in:inbox unless told otherwise.
- send: fill to, subject, body. Turn spoken addresses like "john at gmail dot com" into john@gmail.com. Write a clean, polite body from what the user dictated.
- If the request is not about Gmail or is unclear, action is "unknown".
""".trimIndent()

    fun parse(apiKey: String, transcript: String): Command {
        require(apiKey.isNotBlank()) { "Add your Anthropic API key in the app first" }
        val payload = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 500)
            .put("system", system())
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", transcript)))
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Claude API ${r.code}")
            val text = JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
            val j = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            return Command(
                j.optString("action", "unknown"), j.optString("query"),
                j.optString("to"), j.optString("subject"), j.optString("body")
            )
        }
    }
}
