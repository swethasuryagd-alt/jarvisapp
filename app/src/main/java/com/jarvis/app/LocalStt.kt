package com.jarvis.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Offline English transcription with the wake-word model, so common commands run with no internet delay. */
object LocalStt {
    @Volatile var model: Model? = null
    @Volatile private var grammar: String? = null

    private val FIXED = listOf(
        "volume up", "volume down", "volume max", "volume mute", "increase the volume", "decrease the volume",
        "turn up the volume", "turn down the volume", "brightness up", "brightness down", "brightness max",
        "increase the brightness", "decrease the brightness", "torch on", "torch off", "flashlight on", "flashlight off",
        "turn on the torch", "turn off the torch", "turn on the flashlight", "turn off the flashlight",
        "turn on the screen", "turn off the screen", "screen on", "screen off", "unlock", "unlock the phone",
        "lock the phone", "answer the call", "attend the call", "pick up the call", "accept the call",
        "hang up", "end the call", "cut the call", "reject the call", "speaker on", "speaker off",
        "put on speaker", "turn on the speaker", "turn off the speaker", "play", "pause", "resume",
        "next song", "previous song", "play a song", "play some music", "play music", "pause the music",
        "stop", "stop listening", "stop scrolling", "scroll instagram", "go home", "go to the home screen",
        "what time is it", "what is the time", "tell me the time"
    )

    /** Builds the phrase list (fixed commands + "open <app>" for every installed app). */
    fun buildGrammar(c: Context) {
        val phrases = LinkedHashSet<String>()
        for (p in FIXED) {
            phrases.add(p)
            phrases.add("jarvis $p")
        }
        try {
            val pm = c.packageManager
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            for (r in list) {
                val label = r.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
                if (label.length < 2 || label.length > 30) continue
                phrases.add("open $label")
                phrases.add("jarvis open $label")
                phrases.add("launch $label")
                phrases.add("open $label and play a song")
                phrases.add("jarvis open $label and play a song")
            }
        } catch (_: Exception) {}
        val arr = JSONArray()
        for (p in phrases) arr.put(p)
        arr.put("[unk]")
        grammar = arr.toString()
    }

    /** useGrammar = false: free English transcript. true: only the known command phrases (more accurate for commands). */
    fun transcribe(wav: ByteArray, useGrammar: Boolean): String? {
        val m = model ?: return null
        if (wav.size <= 44) return null
        val g = if (useGrammar) grammar else null
        if (useGrammar && g == null) return null
        return try {
            val pcm = wav.copyOfRange(44, wav.size)
            val rec = if (g != null) Recognizer(m, 16000f, g) else Recognizer(m, 16000f)
            try {
                rec.acceptWaveForm(pcm, pcm.size)
                val t = JSONObject(rec.finalResult).optString("text", "").trim()
                if (g != null && (t.isBlank() || t.split(" ").all { it == "[unk]" })) "" else t
            } finally {
                rec.close()
            }
        } catch (e: Throwable) {
            null
        }
    }
}
