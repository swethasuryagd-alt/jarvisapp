package com.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Shared text-to-speech. Uses the voice, speed and pitch chosen in Settings. Tamil replies use a Tamil voice if installed. */
object Speaker {
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<() -> Unit>()

    private fun withTts(c: Context, f: (TextToSpeech) -> Unit) {
        synchronized(lock) {
            val t = tts
            if (t != null && ready) { f(t); return }
            waiting.add { tts?.let { x -> f(x) } }
            if (t == null) {
                tts = TextToSpeech(c.applicationContext) { status ->
                    synchronized(lock) {
                        ready = (status == TextToSpeech.SUCCESS)
                        val list = ArrayList(waiting)
                        waiting.clear()
                        if (ready) { for (w in list) w() } else { tts = null }
                    }
                }
            }
        }
    }

    fun speak(c: Context, text: String) {
        withTts(c) { t -> doSpeak(c, t, text) }
    }

    private fun doSpeak(c: Context, t: TextToSpeech, text: String) {
        try {
            val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val tamil = text.any { it in '\u0B80'..'\u0BFF' }
            val loc = if (tamil) Locale("ta", "IN") else Locale.getDefault()
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
            val name = p.getString("voice", "") ?: ""
            if (name.isNotBlank()) {
                val v = t.voices?.firstOrNull { it.name == name }
                if (v != null && v.locale.language == t.voice?.locale?.language) t.voice = v
            }
            t.setSpeechRate(p.getFloat("rate", 1.0f))
            t.setPitch(p.getFloat("pitch", 1.0f))
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
        } catch (_: Exception) {}
    }

    fun voices(c: Context, cb: (List<android.speech.tts.Voice>) -> Unit) {
        withTts(c) { t ->
            val list = try { (t.voices ?: emptySet<android.speech.tts.Voice>()).toList() } catch (_: Exception) { emptyList() }
            cb(list)
        }
    }

    fun preview(c: Context, name: String) {
        withTts(c) { t ->
            try {
                val v = t.voices?.firstOrNull { it.name == name } ?: return@withTts
                val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                t.voice = v
                t.setSpeechRate(p.getFloat("rate", 1.0f))
                t.setPitch(p.getFloat("pitch", 1.0f))
                val sample = if (v.locale.language == "ta") "வணக்கம் பாஸ், நான் ஜார்விஸ்." else "Hello Boss, I am Jarvis."
                t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "p")
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }
}
