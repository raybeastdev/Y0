package com.example.voicemail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Bottom panel: listen -> understand (Claude) -> do it in Gmail. Risky actions need a tap to confirm. */
class VoiceActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object { @Volatile var active = false }

    private val auth = GmailAuth(this)
    private lateinit var status: TextView
    private lateinit var confirmRow: LinearLayout
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var pending: (() -> String)? = null

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else show("Microphone permission is needed.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xEE202124.toInt())
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = "Listening…" }
        root.addView(status)

        confirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        confirmRow.addView(Button(this).apply {
            text = "Confirm"
            setOnClickListener { runPending() }
        })
        confirmRow.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { pending = null; confirmRow.visibility = View.GONE; say("Cancelled.") }
        })
        root.addView(confirmRow)
        root.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })

        setContentView(root)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(MATCH_PARENT, WRAP_CONTENT)

        tts = TextToSpeech(this, this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            listen() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    private fun show(t: String) = runOnUiThread { status.text = t }
    private fun say(t: String) { show(t); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "vm") }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { show("Speech recognition isn't available."); return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) show("Didn't catch that.") else handle(t)
                }
                override fun onError(e: Int) = show("Didn't catch that (code $e). Tap the back again to retry.")
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            )
        }
    }

    private fun handle(transcript: String) = lifecycleScope.launch {
        try {
            show("You said: \"$transcript\"\nWorking…")
            val key = getSharedPreferences("vm", MODE_PRIVATE).getString("api_key", "").orEmpty()
            val cmd = withContext(Dispatchers.IO) { ClaudeClient.parse(key, transcript) }
            val gmail = GmailClient(auth.token())

            when (cmd.action) {
                "read_unread", "search" -> say(withContext(Dispatchers.IO) {
                    val ids = gmail.listIds(cmd.query.ifBlank { "is:unread in:inbox" }, 10)
                    if (ids.isEmpty()) "No emails found."
                    else "Top ${ids.size}:\n" + ids.mapIndexed { i, id -> "${i + 1}. ${gmail.summary(id)}" }.joinToString("\n")
                })

                "mark_read", "archive", "trash" -> {
                    val ids = withContext(Dispatchers.IO) { gmail.listIds(cmd.query, 100) }
                    if (ids.isEmpty()) { say("No emails match that."); return@launch }
                    val (verb, add, remove) = when (cmd.action) {
                        "mark_read" -> Triple("Mark as read", emptyList(), listOf("UNREAD"))
                        "archive" -> Triple("Archive", emptyList(), listOf("INBOX"))
                        else -> Triple("Move to trash", listOf("TRASH"), listOf("INBOX"))
                    }
                    ask("$verb ${ids.size} emails matching: ${cmd.query}?") {
                        gmail.batchModify(ids, add, remove); "Done. ${ids.size} emails updated."
                    }
                }

                "send" -> {
                    if (cmd.to.isBlank()) { say("I didn't get an email address. Please try again."); return@launch }
                    ask("Send to ${cmd.to}\nSubject: ${cmd.subject}\n\n${cmd.body}") {
                        gmail.send(cmd.to, cmd.subject, cmd.body); "Sent."
                    }
                }

                else -> say("Sorry, I didn't understand that as a Gmail request.")
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
        }
    }

    private fun ask(prompt: String, action: () -> String) {
        pending = action
        runOnUiThread { confirmRow.visibility = View.VISIBLE }
        say(prompt)
    }

    private fun runPending() {
        val a = pending ?: return
        pending = null
        confirmRow.visibility = View.GONE
        lifecycleScope.launch {
            val result = try { withContext(Dispatchers.IO) { a() } } catch (e: Exception) { "Error: ${e.message}" }
            say(result)
        }
    }

    override fun onDestroy() {
        active = false
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
