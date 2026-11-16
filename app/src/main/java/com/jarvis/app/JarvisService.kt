package com.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class JarvisService : Service() {

    private var porcupine: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val busy = AtomicBoolean(false)

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (level < 0) return
            val pct = level * 100 / scale
            val cutoff = Brain.prefs(ctx).getInt("cutoff", 30)
            if (pct <= cutoff && !charging) {
                notifyMsg("Jarvis paused: battery is $pct%. Open Jarvis and tap Start after charging.")
                say("Jarvis", "Paused, battery is $pct percent.")
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }

        val key = Brain.prefs(this).getString("pico", "") ?: ""
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(this, PorcupineManagerCallback { onWake() })
            porcupine?.start()
            say("Jarvis", "Listening.")
        } catch (e: Exception) {
            notifyMsg("Wake word failed: ${e.message}")
            say("Jarvis", "Wake word failed: ${e.message}")
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onWake() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                porcupine?.stop()
                try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Exception) {}
                val wav = Recorder.record()
                if (wav != null) {
                    val text = Brain.transcribe(this, wav)
                    if (text.isNotBlank()) {
                        say("You", text)
                        val reply = Brain.handle(this, text)
                        if (reply.isNotBlank()) { say("Jarvis", reply); speak(reply) }
                    }
                }
            } catch (e: Exception) {
                say("Jarvis", "Error: ${e.message}")
            } finally {
                try { porcupine?.start() } catch (_: Exception) {}
                busy.set(false)
            }
        }.start()
    }

    private fun speak(s: String) { if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "j") }

    private fun say(who: String, text: String) {
        sendBroadcast(Intent("com.jarvis.app.MSG").setPackage(packageName)
            .putExtra("who", who).putExtra("text", text))
    }

    private fun notifyMsg(msg: String) {
        val n = android.app.Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis").setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
