package com.example.voicemail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val auth = GmailAuth(this)
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("vm", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun text(s: String) = TextView(this).also { it.text = s; col.addView(it) }
        fun button(s: String, f: () -> Unit) = Button(this).also { b -> b.text = s; b.setOnClickListener { f() }; col.addView(b) }

        text("VoiceMail\nDouble-tap the back of the phone, then speak a Gmail command.\n")

        val key = EditText(this).apply { hint = "Anthropic API key"; setText(prefs.getString("api_key", "")); setSingleLine() }
        col.addView(key)
        button("Save API key") { prefs.edit().putString("api_key", key.text.toString().trim()).apply(); toast("Saved") }

        button("1. Allow microphone & notifications") {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            perms.launch(list.toTypedArray())
        }
        button("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("3. Connect Gmail") {
            lifecycleScope.launch {
                try { auth.token(); toast("Gmail connected") } catch (e: Exception) { toast("Failed: ${e.message}") }
            }
        }

        text("\nTap sensitivity (left = more sensitive)")
        val bar = SeekBar(this).apply {
            max = 70
            progress = (prefs.getFloat("tap_threshold", 3f) * 10).toInt() - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putFloat("tap_threshold", (p + 10) / 10f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    toast("Stop and restart the listener to apply")
                }
            })
        }
        col.addView(bar)

        button("4. Start back-tap listener") {
            ContextCompat.startForegroundService(this, Intent(this, BackTapService::class.java))
        }
        button("Stop listener") { stopService(Intent(this, BackTapService::class.java)) }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
