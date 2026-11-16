package com.jarvis.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Recorder {
    private const val SR = 16000

    /** Records one spoken command and returns WAV bytes, or null if nobody spoke. */
    @SuppressLint("MissingPermission")
    fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(800) // 50 ms
        var base = 0.0; var n = 0
        var threshold = 800.0
        var speaking = false
        var lastVoice = 0L
        rec.startRecording()
        val start = System.currentTimeMillis()
        try {
            while (true) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) break
                var sum = 0.0
                for (i in 0 until r) sum += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(sum / r)
                val now = System.currentTimeMillis()
                val el = now - start
                if (el < 300) { base += rms; n++; threshold = maxOf(700.0, base / n * 3); continue }
                val bb = ByteBuffer.allocate(r * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until r) bb.putShort(buf[i])
                pcm.write(bb.array())
                if (rms > threshold) { speaking = true; lastVoice = now }
                if (!speaking && el > 4500) break
                if (speaking && now - lastVoice > 900) break
                if (el > 9000) break
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (speaking) wav(pcm.toByteArray()) else null
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
