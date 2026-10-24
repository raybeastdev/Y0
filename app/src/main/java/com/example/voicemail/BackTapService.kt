package com.example.voicemail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/**
 * Motorola has no back-tap API, so this listens to the accelerometer and detects two sharp
 * knocks on the back of the phone (strong spike on the Z axis, twice within ~0.4 s).
 */
class BackTapService : Service(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private var overlay: View? = null
    private var threshold = 3f
    private var lastSpike = 0L
    private var firstTap = 0L
    private var lastTrigger = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tap", "Back-tap listener", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, "tap")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceMail is listening for a double back-tap")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, notification, type)

        // Android 15 only lets "display over other apps" start activities from the background
        // while an overlay window is visible, so keep an invisible 1px one.
        if (Settings.canDrawOverlays(this)) {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
        }

        threshold = getSharedPreferences("vm", MODE_PRIVATE).getFloat("tap_threshold", 3f)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        // 5 ms = as fast as the hardware allows; taps are very short.
        sensors.registerListener(this, accel, 5000)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = abs(e.values[0]); val y = abs(e.values[1]); val z = abs(e.values[2])
        val spike = z > threshold && z > 1.6f * x && z > 1.6f * y
        if (!spike) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpike < 60) return          // same knock still ringing
        lastSpike = now
        if (firstTap != 0L && now - firstTap in 100..450) {
            firstTap = 0
            trigger(now)
        } else firstTap = now
    }

    private fun trigger(now: Long) {
        if (now - lastTrigger < 2000 || VoiceActivity.active) return
        lastTrigger = now
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            .vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        sensors.unregisterListener(this)
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        super.onDestroy()
    }
}
