package com.jarvis.app

import android.Manifest
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

class JarvisService : Service() {

    companion object {
        @Volatile var active = false
        const val ACTION_STOP = "com.jarvis.app.STOP"
        /** Set to true (orb tap, AirPods button) to make Jarvis start listening right now. */
        @Volatile var trigger = false
    }

    @Volatile private var running = false
    @Volatile private var lastTranscript = ""
    private var model: Model? = null
    private var btOn = false
    private var session: MediaSession? = null

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
            .setContentText("Say \"Jarvis\", feel the buzz, then speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        Speaker.warm(this)
        startSession()
        running = true
        active = true
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        Thread { runLoop() }.start()
    }

    /** Optional: use the AirPods / Bluetooth headset microphone. */
    private fun routeBluetooth(on: Boolean): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 31) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        say("Status", "Allow Bluetooth in Jarvis settings to use the AirPods mic.")
                        return false
                    }
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?: return false
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    val ok = am.setCommunicationDevice(dev)
                    Thread.sleep(900)
                    return ok
                } else {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    Thread.sleep(900)
                    return true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
        return false
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
            LocalStt.model = model
            try { LocalStt.buildGrammar(this) } catch (_: Exception) {}
            Brain.refreshModels(this)
        } catch (e: Throwable) {
            say("Status", "Model failed: ${e.message}")
            notifyMsg("Wake word model failed: ${e.message}")
            stopSelf()
            return
        }
        btOn = Brain.prefs(this).getBoolean("bt", false) && routeBluetooth(true)
        say("Status", if (btOn) "Listening through your headset. Say Jarvis." else "Listening. Say Jarvis.")
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
        if (btOn) routeBluetooth(false)
        LocalStt.model = null
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

    /** Multiplies the 16-bit samples by g (quiet voices become loud enough). */
    private fun amp(data: ByteArray, len: Int, g: Float) {
        var i = 0
        while (i + 1 < len) {
            val s = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            val v = (s * g).toInt().coerceIn(-32768, 32767)
            data[i] = (v and 0xFF).toByte()
            data[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Listens for "Jarvis" and then keeps recording the SAME stream until you stop talking, so nothing you say
     * right after (or in the same breath as) the wake word is lost.
     * - Normal speaking volume works: the voice detector is sensitive and a smart gain boosts quiet voices.
     * - Battery saver: the recognizer only runs while there is sound.
     * - The offline transcript is ready the moment you stop, so simple commands run with no internet.
     */
    @SuppressLint("MissingPermission")
    private fun listenAndCapture(): ByteArray? {
        val m = model ?: return null
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var opened: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384))
            if (r.state == AudioRecord.STATE_INITIALIZED) { opened = r; break }
            r.release()
        }
        val ar = opened ?: throw IllegalStateException("mic busy")
        try {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.setEnabled(true)
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.setEnabled(true)
        } catch (_: Exception) {}
        val rec = Recognizer(m, 16000f)
        val wake = Regex("jarvis|jervis|jarvas|jarvice|harvis|jarvi|charvis|garvis|darvis|jarviz|jar vis|java is|jarvish")
        val buf = ByteArray(3200) // 100 ms
        val pre = ArrayDeque<ByteArray>()
        val utter = ByteArrayOutputStream()
        var floor = 60.0
        var gain = 6f
        var feeding = false
        var captured = false
        var lastVoice = 0L
        var utterStart = 0L
        var prompted = false
        var promptAt = 0L
        var spokeAfter = false

        fun done(): ByteArray {
            lastTranscript = try { JSONObject(rec.finalResult).optString("text", "") } catch (_: Exception) { "" }
            return Recorder.toWav(utter.toByteArray())
        }

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
                val voice = rms > maxOf(floor * 2.2, 70.0)
                if (voice) {
                    lastVoice = now
                    val desired = (8000f / maxOf(rms.toFloat(), 1f)).coerceIn(3f, 24f)
                    gain += (desired - gain) * 0.25f
                } else {
                    gain += (9f - gain) * 0.01f
                }
                amp(buf, n, gain)

                if (trigger && !captured) {
                    trigger = false
                    captured = true
                    feeding = true
                    utter.reset()
                    pre.clear()
                    utterStart = now
                    lastVoice = now
                    prompted = true
                    promptAt = now
                    buzz()
                    Brain.warmUp()
                    say("State", "listening")
                }

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
                        Brain.warmUp()
                        say("State", "listening")
                    }
                } else {
                    utter.write(buf, 0, n)
                    rec.acceptWaveForm(buf, n)
                    val total = now - utterStart
                    val silent = now - lastVoice
                    if (prompted) {
                        if (voice && now - promptAt > 400) spokeAfter = true
                        if (spokeAfter && silent > 750) return done()
                        if (!spokeAfter && now - promptAt > 4500) return done()
                    } else if (silent > 750) {
                        if (total < 2500) {
                            prompted = true
                            promptAt = now
                        } else {
                            return done()
                        }
                    }
                    if (total > 9000 || utter.size() > 300000) return done()
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
        val t0 = SystemClock.elapsedRealtime()
        say("State", "thinking")
        try {
            val free = lastTranscript
            val g = LocalStt.transcribe(wav, true) ?: ""
            val cands = ArrayList<String>()
            if (g.isNotBlank() && (g.contains(' ') || free.contains(g))) cands.add(g)
            if (free.isNotBlank()) cands.add(free)
            val speakOn = Brain.prefs(this).getBoolean("speak", true)
            val (heard, answer) = Brain.handleAudio(this, wav, cands) { interim ->
                say("Jarvis", interim)
                if (speakOn) Speaker.speak(this, interim) { m -> say("Jarvis", m) }
            }
            if (heard.isNotBlank()) say("You", heard)
            if (answer.isNotBlank()) {
                val secs = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
                say("Jarvis", answer, "${secs}s · ${Brain.lastTiming}")
                if (Brain.lastSuggest.isNotBlank()) say("Suggest", Brain.lastSuggest)
                if (speakOn) Speaker.speak(this, answer) { m -> say("Jarvis", m) }
                say("State", if (speakOn) "speaking:${answer.length}" else "idle")
            } else {
                say("State", "idle")
            }
        } catch (e: Exception) {
            say("Jarvis", "Error: ${e.message}")
            say("State", "idle")
        }
    }

    private fun say(who: String, text: String, info: String = "") {
        sendBroadcast(Intent("com.jarvis.app.MSG").setPackage(packageName)
            .putExtra("who", who).putExtra("text", text).putExtra("info", info))
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

    /** AirPods / headset button: a tap on the earbud makes Jarvis start listening. */
    private fun startSession() {
        if (!Brain.prefs(this).getBoolean("airpods", true)) return
        try {
            val s = MediaSession(this, "JarvisSession")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null && ev.action == KeyEvent.ACTION_DOWN) {
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                trigger = true
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            s.isActive = true
            session = s
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { session?.release() } catch (_: Exception) {}
        running = false
        active = false
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        super.onDestroy()
    }
}
