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
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

class JarvisService : Service() {

    companion object {
        @Volatile var active = false
        const val ACTION_STOP = "com.jarvis.app.STOP"
    }

    @Volatile private var running = false
    private var model: Model? = null

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
        nm.createNotificationChannel(NotificationChannel("jarvis_quiet", "Jarvis listening", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis messages", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis_quiet")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\", wait for the beep, then speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .setOngoing(true).build()
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
            var wav: ByteArray? = null
            try {
                wav = listenAndCapture()
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
            if (wav != null) handleCommand(wav)
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

    private fun check(rec: Recognizer, data: ByteArray, len: Int, wake: Regex): Boolean {
        val json = (if (rec.acceptWaveForm(data, len)) rec.result else rec.partialResult).lowercase()
        return wake.containsMatchIn(json)
    }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /**
     * Listens for "Jarvis" and then keeps recording the SAME stream until you stop talking,
     * so nothing you say right after (or in the same breath as) the wake word is lost.
     * Battery saver: the mic is read all the time (cheap), but the speech recognizer only runs
     * while somebody is actually making sound.
     */
    @SuppressLint("MissingPermission")
    private fun listenAndCapture(): ByteArray? {
        val m = model ?: return null
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384))
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("mic busy")
        }
        val rec = Recognizer(m, 16000f)
        val wake = Regex("jarvis|jervis|jarvas|jarvice|harvis")
        val buf = ByteArray(3200) // 100 ms
        val pre = ArrayDeque<ByteArray>()
        val utter = ByteArrayOutputStream()
        var floor = 300.0
        var feeding = false
        var captured = false
        var lastVoice = 0L
        var utterStart = 0L
        var prompted = false
        var promptAt = 0L
        var spokeAfter = false
        ar.startRecording()
        try {
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("mic read error $n")
                if (n < 2) continue

                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val sv = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    sum += sv.toDouble() * sv
                    i += 2
                }
                val rms = Math.sqrt(sum / (n / 2))
                val now = SystemClock.uptimeMillis()
                if (rms < floor * 1.6) floor += (rms - floor) * 0.05 else floor += (rms - floor) * 0.002
                val voice = rms > maxOf(floor * 2.2, 400.0)
                if (voice) lastVoice = now

                if (!captured) {
                    if (!feeding) {
                        if (voice) {
                            feeding = true
                            utterStart = now
                            utter.reset()
                            for (p in pre) {
                                utter.write(p, 0, p.size)
                                if (!captured && check(rec, p, p.size, wake)) captured = true
                            }
                            pre.clear()
                            utter.write(buf, 0, n)
                            if (!captured && check(rec, buf, n, wake)) captured = true
                        } else {
                            pre.addLast(buf.copyOf(n))
                            if (pre.size > 5) pre.removeFirst()
                        }
                    } else {
                        utter.write(buf, 0, n)
                        if (check(rec, buf, n, wake)) {
                            captured = true
                        } else if (now - lastVoice > 1200) {
                            feeding = false
                            rec.reset()
                            utter.reset()
                        }
                    }
                    if (captured) {
                        buzz()
                        lastVoice = now
                    }
                } else {
                    utter.write(buf, 0, n)
                    val total = now - utterStart
                    val silent = now - lastVoice
                    if (prompted) {
                        if (voice && now - promptAt > 400) spokeAfter = true
                        if (spokeAfter && silent > 1500) return Recorder.toWav(utter.toByteArray())
                        if (!spokeAfter && now - promptAt > 4500) return Recorder.toWav(utter.toByteArray())
                    } else if (silent > 1500) {
                        if (total < 2500) {
                            prompted = true
                            promptAt = now
                        } else {
                            return Recorder.toWav(utter.toByteArray())
                        }
                    }
                    if (total > 20000 || utter.size() > 640000) return Recorder.toWav(utter.toByteArray())
                }
            }
        } finally {
            try { ar.stop() } catch (_: Exception) {}
            ar.release()
            rec.close()
        }
        return null
    }

    private fun handleCommand(wav: ByteArray) {
        try {
            val (heard, answer) = Brain.handleAudio(this, wav)
            if (heard.isNotBlank()) say("You", heard)
            if (answer.isNotBlank()) { say("Jarvis", answer); Speaker.speak(this, answer) }
        } catch (e: Exception) {
            say("Jarvis", "Error: ${e.message}")
        }
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        active = false
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        super.onDestroy()
    }
}
