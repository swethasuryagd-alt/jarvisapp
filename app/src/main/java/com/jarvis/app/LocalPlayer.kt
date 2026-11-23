package com.jarvis.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Plays songs from the phone's own music files, hands-free (no tapping play). */
object LocalPlayer {
    private class Track(val uri: Uri, val title: String)

    private var mp: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var index = 0
    private var app: Context? = null
    private var errors = 0

    fun hasTrack(): Boolean = mp != null

    private fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun play(c: Context, query: String?): String {
        if (c.checkSelfPermission(permission()) != PackageManager.PERMISSION_GRANTED)
            return "I need permission to read your music. Open Jarvis settings and tap Allow music files."
        app = c.applicationContext
        val found = load(c, query)
        if (found.isEmpty())
            return if (query.isNullOrEmpty()) "I could not find any music files on this phone."
            else "I could not find \"$query\" in your phone's music."
        queue = if (query.isNullOrEmpty()) found.shuffled() else found
        index = 0
        errors = 0
        startCurrent()
        return "Playing ${queue[0].title}."
    }

    private fun load(c: Context, query: String?): List<Track> {
        val out = ArrayList<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
        var sel = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 90000)"
        var args: Array<String>? = null
        if (!query.isNullOrEmpty()) {
            sel += " AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)"
            val like = "%$query%"
            args = arrayOf(like, like, like)
        }
        c.contentResolver.query(uri, proj, sel, args, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tCol = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            while (cur.moveToNext() && out.size < 500) {
                out.add(Track(ContentUris.withAppendedId(uri, cur.getLong(idCol)), cur.getString(tCol) ?: "a song"))
            }
        }
        return out
    }

    private fun startCurrent() {
        val c = app ?: return
        stopPlayer()
        val t = queue.getOrNull(index) ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.requestAudioFocus(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build())
            val p = MediaPlayer()
            p.setAudioAttributes(attrs)
            p.setDataSource(c, t.uri)
            p.setOnPreparedListener { it.start(); errors = 0 }
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ ->
                errors++
                if (errors < 5) next() else stopPlayer()
                true
            }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) {
            stopPlayer()
        }
    }

    private fun stopPlayer() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    fun next(): String {
        if (queue.isEmpty()) return "Nothing is queued."
        index = (index + 1) % queue.size
        startCurrent()
        return "Next: ${queue[index].title}."
    }

    fun previous(): String {
        if (queue.isEmpty()) return "Nothing is queued."
        index = (index - 1 + queue.size) % queue.size
        startCurrent()
        return "Previous: ${queue[index].title}."
    }

    fun pause(): String {
        try { mp?.pause() } catch (_: Exception) {}
        return "Paused."
    }

    fun resume(): String {
        try { mp?.start() } catch (_: Exception) {}
        return "Resuming."
    }

    fun stop(): String {
        stopPlayer()
        queue = emptyList()
        return "Stopped the music."
    }
}
