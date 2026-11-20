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

    /** Records one spoken command from the moment it is called. Returns WAV bytes, or null if nobody spoke. */
    @SuppressLint("MissingPermission")
    fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(800) // 50 ms
        var floor = 250.0
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
                if (rms < floor * 1.6) floor += (rms - floor) * 0.05 else floor += (rms - floor) * 0.01
                if (rms > maxOf(floor * 2.2, 400.0)) { speaking = true; lastVoice = now }
                // keep ALL audio from the very first moment, so the start of the sentence is never lost
                val bb = ByteBuffer.allocate(r * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until r) bb.putShort(buf[i])
                pcm.write(bb.array())
                if (!speaking && el > 6000) break
                if (speaking && now - lastVoice > 1100) break
                if (el > 15000) break
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (speaking) toWav(pcm.toByteArray()) else null
    }

    /** 16-bit mono PCM -> WAV, boosting quiet recordings so soft voices are still understood. */
    fun toWav(pcmIn: ByteArray): ByteArray {
        val n = pcmIn.size / 2
        val src = ByteBuffer.wrap(pcmIn).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 1
        for (i in 0 until n) {
            val v = Math.abs(src.getShort(i * 2).toInt())
            if (v > peak) peak = v
        }
        val gain = if (peak < 12000) minOf(8.0, 20000.0 / peak) else 1.0
        val out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (src.getShort(i * 2) * gain).toInt().coerceIn(-32768, 32767)
            out.putShort(v.toShort())
        }
        val pcm = out.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
