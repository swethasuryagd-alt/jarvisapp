package com.jarvis.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class JarvisService : Service() {

    @Volatile private var running = false
    private var model: Model? = null
    companion object {
        @Volatile var active = false
    }

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
                say("Status", "Paused, battery is $pct percent.")
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\", wait for the beep, then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        running = true
        active = true
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        Thread { runLoop() }.start()
    }

    private fun runLoop() {
        try {
            say("Status", "Loading wake word model...")
            val dir = File(filesDir, "model")
            if (!File(dir, ".done").exists()) {
                dir.deleteRecursively()
                copyAssets("model", dir)
                File(dir, ".done").writeText("1")
            }
            model = Model(dir.absolutePath)
        } catch (e: Throwable) {
            say("Status", "Model failed: ${e.message}")
            notifyMsg("Wake word model failed: ${e.message}")
            stopSelf()
            return
        }
        say("Status", "Listening. Say Jarvis.")
        var fails = 0
        while (running) {
            var heard = false
            try {
                heard = listenForWake()
                fails = 0
            } catch (e: Throwable) {
                fails++
                if (fails >= 5) {
                    say("Status", "Mic error: ${e.message}")
                    notifyMsg("Mic error: ${e.message}")
                    stopSelf()
                    break
                }
                try { Thread.sleep(2000) } catch (_: Exception) {}
            }
            if (!running) break
            if (heard) handleCommand()
        }
        try { model?.close() } catch (_: Exception) {}
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (_: Exception) {}
            return
        }
        dest.mkdirs()
        for (f in list) copyAssets("$path/$f", File(dest, f))
    }

    @SuppressLint("MissingPermission")
    private fun listenForWake(): Boolean {
        val m = model ?: return false
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("mic busy")
        }
        val rec = Recognizer(m, 16000f)
        val buf = ByteArray(4096)
        val wake = Regex("jarvis|jervis|jarvas|jarvice|harvis")
        ar.startRecording()
        try {
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("mic read error $n")
                if (n == 0) continue
                val json = (if (rec.acceptWaveForm(buf, n)) rec.result else rec.partialResult).lowercase()
                if (wake.containsMatchIn(json)) return true
            }
        } finally {
            try { ar.stop() } catch (_: Exception) {}
            ar.release()
            rec.close()
        }
        return false
    }

    private fun handleCommand() {
        try {
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
        }
    }

    private fun speak(s: String) { Voice.speak(this, s) }

    private fun say(who: String, text: String) {
        sendBroadcast(Intent("com.jarvis.app.MSG").setPackage(packageName)
            .putExtra("who", who).putExtra("text", text))
    }

    private fun notifyMsg(msg: String) {
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis").setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        active = false
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        super.onDestroy()
    }
}
