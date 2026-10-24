package com.example.voicemail

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Minimal blocking Gmail REST client. Call from a background thread. */
class GmailClient(private val token: String) {
    private val http = OkHttpClient()
    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val json = "application/json".toMediaType()

    private fun call(req: Request.Builder): String =
        http.newCall(req.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Gmail ${r.code}: ${body.take(120)}")
            body
        }

    fun listIds(query: String, max: Int): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = JSONObject(call(Request.Builder().url("$base/messages?q=$q&maxResults=$max").get()))
            .optJSONArray("messages") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it).getString("id") }
    }

    /** "Sender: Subject" for one message. */
    fun summary(id: String): String {
        val url = "$base/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject"
        val headers = JSONObject(call(Request.Builder().url(url).get()))
            .getJSONObject("payload").getJSONArray("headers")
        var from = ""
        var subject = ""
        for (i in 0 until headers.length()) {
            val h = headers.getJSONObject(i)
            when (h.getString("name").lowercase()) {
                "from" -> from = h.getString("value")
                "subject" -> subject = h.getString("value")
            }
        }
        val name = from.substringBefore('<').trim().trim('"').ifBlank { from }
        return "$name: ${subject.ifBlank { "(no subject)" }}"
    }

    fun batchModify(ids: List<String>, add: List<String>, remove: List<String>) {
        val body = JSONObject()
            .put("ids", JSONArray(ids))
            .put("addLabelIds", JSONArray(add))
            .put("removeLabelIds", JSONArray(remove))
        call(Request.Builder().url("$base/messages/batchModify").post(body.toString().toRequestBody(json)))
    }

    fun send(to: String, subject: String, text: String) {
        val encSubject = "=?UTF-8?B?" + Base64.encodeToString(subject.toByteArray(), Base64.NO_WRAP) + "?="
        val raw = "To: $to\r\nSubject: $encSubject\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n$text"
        val enc = Base64.encodeToString(raw.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        call(Request.Builder().url("$base/messages/send").post(JSONObject().put("raw", enc).toString().toRequestBody(json)))
    }
}
