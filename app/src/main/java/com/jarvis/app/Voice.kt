package com.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One shared text-to-speech engine. Uses a Tamil voice when the reply is in Tamil. */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(c: Context, text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(c.applicationContext) { status ->
                ready = (status == TextToSpeech.SUCCESS)
                val p = pending
                pending = null
                if (ready && p != null) doSpeak(p)
            }
        } else if (ready) {
            doSpeak(text)
        }
    }

    private fun doSpeak(text: String) {
        val t = tts ?: return
        val tamil = text.any { it in '\u0B80'..'\u0BFF' }
        val loc = if (tamil) Locale("ta", "IN") else Locale.getDefault()
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.US)
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }
}
