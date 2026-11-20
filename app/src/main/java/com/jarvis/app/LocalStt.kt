package com.jarvis.app

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Offline English transcription using the wake-word model, so common commands run with no internet delay. */
object LocalStt {
    @Volatile var model: Model? = null

    fun transcribe(wav: ByteArray): String? {
        val m = model ?: return null
        if (wav.size <= 44) return null
        return try {
            val pcm = wav.copyOfRange(44, wav.size)
            val rec = Recognizer(m, 16000f)
            try {
                rec.acceptWaveForm(pcm, pcm.size)
                JSONObject(rec.finalResult).optString("text", "").trim()
            } finally {
                rec.close()
            }
        } catch (e: Throwable) {
            null
        }
    }
}
