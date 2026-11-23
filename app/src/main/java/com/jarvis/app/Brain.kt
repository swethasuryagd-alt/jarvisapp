package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Base64
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class HttpError(val code: Int, val msg: String) : Exception("HTTP $code $msg")

object Brain {
    private class Ans(val heard: String, val reply: String, val action: String, val arg: String)

    private val history = ArrayList<JSONObject>()
    private val FALLBACK = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3.1-flash-lite", "gemini-3.5-flash")
    private val DAD = listOf("dad", "father", "papa", "appa", "daddy")
    private val MOM = listOf("mom", "mother", "mummy", "mommy", "mama", "amma")
    private const val PERM_CALL = "I need Phone permission. Open Jarvis settings and tap Allow calling and contacts."
    private const val PERM_CONTACTS = "I need Contacts permission to find people. Open Jarvis settings and tap Allow calling and contacts."

    private const val SYSTEM_BASE = "You are Jarvis, a fast personal assistant on the user's phone, in the style of the AI from the Iron Man films. " +
            "The user's name is Boss. Always answer in the language the user used (for example Tamil in Tamil). " +
            "Keep every answer to one to three short spoken sentences. No markdown, no lists, no emojis. " +
            "The user may start by saying your name, Jarvis; that is just calling you. " +
            "You can control the phone ONLY through these actions: " +
            "call (arg: contact name or phone number, add 'on speaker' if wanted), answer_call (arg: 'speaker' if wanted), end_call, speaker (arg: on or off, for an ongoing call), " +
            "volume (arg: up, down, max, mute, or a number 0-100), brightness (arg: up, down, max, min, or a number 0-100), " +
            "flashlight (arg: on or off), media (arg: play, pause, next or previous), play_song (arg: song or artist name, or empty to just start music), timer (arg: number of seconds), alarm (arg: time as HH:MM in 24-hour form), " +
            "home, lock (turn the screen off), wake (turn the screen on), unlock, open_app (arg: app name), stop_listening, " +
            "search (arg: the question to look up on the web). " +
            "Use search for anything about current events, news, prices, scores, weather, recent facts, or anything you are not sure of, and set reply to a short line like 'Let me check that, Boss.'. " +
            "If the user wants something one of the other actions can do, set action and arg, and keep reply to a very short acknowledgement. " +
            "If the user wants anything else that needs the phone (sending messages, closing apps, changing other settings), " +
            "leave action empty and honestly say you cannot do that yet. Never claim you did something yourself; the app confirms real actions. " +
            "For normal knowledge questions, just answer."

    private const val AUDIO_ASK = "Listen to the audio. Reply ONLY with JSON in this exact form: " +
            "{\"heard\": \"the exact words spoken\", \"reply\": \"your short answer\", \"action\": \"action name or empty\", \"arg\": \"action argument or empty\"}. " +
            "The speaker may begin with the word Jarvis; keep it in heard. If nothing intelligible was said, use empty strings."

    private const val TEXT_HINT = "\n\n(Reply ONLY with JSON: {\"reply\": \"your short answer\", \"action\": \"action name or empty\", \"arg\": \"action argument or empty\"})"

    private fun systemText(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a, zzzz", Locale.ENGLISH).format(Date())
        return "$SYSTEM_BASE The current local date and time is $now. Use it when asked about the time or date."
    }

    fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun clearHistory() { history.clear() }

    /** Opens the internet connection to Gemini early (while you are still talking) so the answer comes back sooner. */
    fun warmUp() {
        Thread {
            try {
                val con = URL("https://generativelanguage.googleapis.com/").openConnection() as HttpURLConnection
                con.connectTimeout = 3000
                con.readTimeout = 3000
                val code = con.responseCode
                val s = if (code in 200..299) con.inputStream else con.errorStream
                s?.use { it.readBytes() }
            } catch (_: Exception) {}
        }.start()
    }

    private fun remember(role: String, text: String) {
        history.add(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.size > 12) history.removeAt(0)
    }

    /** If the AI is slow, say a short line after 2.5 seconds so Jarvis never just sits silent. */
    private fun <T> withFiller(onInterim: ((String) -> Unit)?, block: () -> T): T {
        if (onInterim == null) return block()
        val done = AtomicBoolean(false)
        val t = Thread {
            try {
                Thread.sleep(3000)
                if (!done.get()) onInterim("One moment, Boss.")
            } catch (_: InterruptedException) {}
        }
        t.start()
        try {
            return block()
        } finally {
            done.set(true)
            t.interrupt()
        }
    }

    // ---------------------------------------------------------------- typed text
    fun handle(c: Context, raw: String, onInterim: ((String) -> Unit)? = null): String =
        try {
            if (raw.isBlank()) "" else (tryLocal(c, raw) ?: chat(c, raw.trim(), onInterim))
        } catch (e: Exception) { "Error: ${e.message}" }

    // ---------------------------------------------------------------- spoken audio
    /** Returns (what you said, Jarvis's answer). localText is the instant offline transcript, if there is one. Throws on errors. */
    fun handleAudio(c: Context, wav: ByteArray, localText: String?, onInterim: ((String) -> Unit)? = null): Pair<String, String> {
        if (!localText.isNullOrBlank()) {
            val quick = tryLocal(c, localText)
            if (quick != null) {
                remember("user", localText)
                remember("model", quick)
                return Pair(localText, quick)
            }
        }
        val ans = withFiller(onInterim) { askAudio(c, wav) }
        if (ans.heard.isBlank()) return Pair("", "")
        val answer = tryLocal(c, ans.heard) ?: finish(c, ans, ans.heard, onInterim)
        remember("user", ans.heard)
        remember("model", answer)
        return Pair(ans.heard, answer)
    }

    private fun parse(raw: String): Ans {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = try { JSONObject(t) } catch (e: Exception) { return Ans("", t, "", "") }
        fun s(k: String): String {
            val v = j.optString(k, "").trim()
            return if (v == "null") "" else v
        }
        return Ans(s("heard"), s("reply"), s("action"), s("arg"))
    }

    /** Runs a phone action or a web search if the AI asked for one, and reports what REALLY happened. */
    private fun finish(c: Context, a: Ans, userText: String, onInterim: ((String) -> Unit)?): String {
        if (a.action.trim().lowercase() == "search") {
            onInterim?.invoke(a.reply.ifBlank { "Let me check that, Boss." })
            return searchWeb(c, a.arg.ifBlank { userText })
        }
        if (a.action.isNotEmpty()) {
            val r = runAction(c, a.action, a.arg)
            if (r != null) return r
        }
        return a.reply.ifBlank { "Sorry Boss, I did not catch that." }
    }

    private fun askAudio(c: Context, wav: ByteArray): Ans {
        val audio = JSONObject().put("inline_data",
            JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        val ask = JSONObject().put("text", AUDIO_ASK)
        val contents = JSONArray()
        for (m in history) contents.put(m)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(ask).put(audio)))
        val cfg = JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 400)
        return parse(generate(c, contents, cfg))
    }

    private fun chat(c: Context, text: String, onInterim: ((String) -> Unit)?): String {
        val contents = JSONArray()
        for (m in history) contents.put(m)
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text + TEXT_HINT))))
        val cfg = JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 400)
        val ans = withFiller(onInterim) { parse(generate(c, contents, cfg)) }
        val answer = finish(c, ans, text, onInterim)
        remember("user", text)
        remember("model", answer)
        return answer
    }

    private fun searchWeb(c: Context, q: String): String {
        val contents = JSONArray()
        for (m in history) contents.put(m)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
            q + "\n\n(Search the web if needed and answer in one to three short spoken sentences. Plain text only, no JSON.)"))))
        val cfg = JSONObject().put("maxOutputTokens", 400)
        return try {
            generate(c, contents, cfg, true).trim().ifBlank { "I could not find an answer, Boss." }
        } catch (e: Exception) {
            "I could not search the web right now: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- Gemini call (tries several model names)
    private fun generate(c: Context, contents: JSONArray, cfg: JSONObject, useSearch: Boolean = false): String {
        val key = prefs(c).getString("gemini", "")?.trim() ?: ""
        if (key.isEmpty()) throw Exception("Add your Gemini key in settings first.")

        val custom = prefs(c).getString("model", "")?.trim() ?: ""
        val tries = ArrayList<String>()
        if (custom.isNotEmpty()) tries.add(custom)
        for (m in FALLBACK) if (m !in tries) tries.add(m)

        var last: Exception? = null
        for (m in tries) {
            try {
                val conf = JSONObject(cfg.toString())
                if (m.startsWith("gemini-2.5-flash")) conf.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val bodyJson = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText()))))
                    .put("contents", contents)
                    .put("generationConfig", conf)
                if (useSearch) bodyJson.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val res = post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", key, bodyJson.toString().toByteArray())
                return extract(res)
            } catch (e: HttpError) {
                last = e
                val tryNext = e.code == 404 || e.code == 429 || e.code >= 500 ||
                        (e.code == 400 && e.msg.contains("not found", ignoreCase = true))
                if (!tryNext) throw e
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last ?: Exception("No Gemini model worked")
    }

    private fun extract(res: String): String {
        val cands = JSONObject(res).optJSONArray("candidates")
        if (cands == null || cands.length() == 0) throw Exception("No answer (the request may have been blocked)")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception("Empty answer")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.has("thought")) sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    private fun post(url: String, key: String, body: ByteArray): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 10000
        con.readTimeout = 25000
        con.setRequestProperty("x-goog-api-key", key)
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(body) }
        val code = con.responseCode
        val stream = if (code in 200..299) con.inputStream else con.errorStream
        val s = stream?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw HttpError(code, s.take(200))
        return s
    }

    // ---------------------------------------------------------------- local commands (instant, no internet, no AI)
    private fun tryLocal(c: Context, raw: String): String? {
        var t = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("jarvis").trim()
        if (t.isEmpty()) return null

        if (Regex("(stop listening|stop jarvis|turn off jarvis|shut down jarvis|go to sleep)").containsMatchIn(t)) {
            c.stopService(Intent(c, JarvisService::class.java))
            return "Listening off."
        }
        if (t == "stop" || t.startsWith("stop scroll")) { JarvisAccess.stopScroll(); return "Stopped." }
        if (t.contains("scroll")) {
            val acc = JarvisAccess.inst ?: return "Turn on Jarvis in Accessibility settings first."
            val gap = Regex("(\\d+) sec").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(2, 120) ?: 8
            val pkg = if (t.contains("insta") || t.contains("reel")) "com.instagram.android" else null
            if (pkg != null && !launch(c, pkg)) return "Instagram is not installed."
            acc.startScroll(gap, pkg)
            return "Scrolling every $gap seconds. Say stop to end."
        }
        if (t.startsWith("open ") || t.startsWith("launch ")) {
            val name = t.substringAfter(" ").trim()
            val pkg = findApp(c, name) ?: return null
            return if (launch(c, pkg)) "Opening $name." else "Could not open $name (allow Display over other apps)."
        }

        val speaker = t.contains("speaker")

        // calls
        Regex("^(call|phone|dial) (.+)$").find(t)?.let { m ->
            val who = m.groupValues[2].replace(Regex(" (on|with|in) (the )?speaker(phone)?$"), "").removePrefix("my ").trim()
            if (who.isNotEmpty() && who != "back") return callLocal(c, who, speaker)
        }
        if (Regex("(answer|attend|pick up|accept|receive)( the| this| my)?( incoming)? call").containsMatchIn(t))
            return doAnswer(c, speaker)
        if (Regex("(hang up|end (the |this )?call|cut (the |this )?call|disconnect (the |this )?call|reject (the |this )?call|decline (the |this )?call)").containsMatchIn(t))
            return doEndCall(c)
        if (Regex("((put|turn|switch|enable|disable|set).*(speaker|speakerphone))|((speaker|speakerphone) (on|off))").containsMatchIn(t))
            return doSpeaker(c, if (Regex("(off|disable)").containsMatchIn(t)) "off" else "on")

        // volume and brightness
        Regex("volume (to )?(\\d{1,3})").find(t)?.let { return doVolume(c, it.groupValues[2]) }
        if (t.contains("volume")) {
            val arg = when {
                Regex("(max|full|maximum)").containsMatchIn(t) -> "max"
                Regex("(mute|zero|silent|minimum)").containsMatchIn(t) -> "mute"
                Regex("(up|increase|raise|higher|louder|more)").containsMatchIn(t) -> "up"
                Regex("(down|decrease|lower|reduce|less|quieter)").containsMatchIn(t) -> "down"
                else -> ""
            }
            if (arg.isNotEmpty()) return doVolume(c, arg)
        }
        Regex("brightness (to )?(\\d{1,3})").find(t)?.let { return doBrightness(c, it.groupValues[2]) }
        if (t.contains("brightness")) {
            val arg = when {
                Regex("(max|full|maximum)").containsMatchIn(t) -> "max"
                Regex("(min|lowest|minimum|dim)").containsMatchIn(t) -> "min"
                Regex("(up|increase|raise|higher|brighter|more)").containsMatchIn(t) -> "up"
                Regex("(down|decrease|lower|reduce|less|darker)").containsMatchIn(t) -> "down"
                else -> ""
            }
            if (arg.isNotEmpty()) return doBrightness(c, arg)
        }

        // flashlight
        if (Regex("(flash ?light|torch)").containsMatchIn(t) && Regex("(turn|switch|put|enable|disable|on|off)").containsMatchIn(t))
            return doTorch(c, if (Regex("(off|disable)").containsMatchIn(t)) "off" else "on")

        // screen
        if (Regex("((turn off|switch off|lock)( the| my)? (screen|phone|display))|(screen off)").containsMatchIn(t))
            return doGlobalLock()
        if (Regex("(unlock)").containsMatchIn(t)) return doUnlock(c)
        if (Regex("((turn on|switch on|wake)( up)?( the| my)? (screen|phone|display))|(screen on)|(^wake up$)").containsMatchIn(t))
            return doWake(c)

        // music
        Regex("^play (.+)$").find(t)?.let { m ->
            var what = m.groupValues[1].replace(Regex(" (on|in|using) (the )?spotify$"), "").trim()
            what = what.removePrefix("the song ").removePrefix("song ").trim()
            val generic = Regex("^(a song|some music|music|a song for me|songs|something|any song|some songs|my music|my songs|the music|music for me|song)$")
            return if (what.isEmpty() || generic.matches(what)) doPlaySong(c, "") else doPlaySong(c, what)
        }
        if (Regex("^(play|resume)$").matches(t)) return doMedia(c, "play")
        if (Regex("^(pause|stop music|pause music|pause the music)$").matches(t)) return doMedia(c, "pause")
        if (Regex("^(next|skip)( the)?( song| track)?$").matches(t)) return doMedia(c, "next")
        if (Regex("^(previous|last|back)( song| track)$").matches(t)) return doMedia(c, "previous")
        return null
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun findApp(c: Context, name: String): String? {
        val q = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (q.length < 3) return null
        val pm = c.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        var bestScore = 0.0
        for (r in list) {
            val label = r.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "")
            if (label.isEmpty()) continue
            val score = when {
                label == q -> 1.0
                label.startsWith(q) || q.startsWith(label) -> 0.9
                label.contains(q) -> 0.8
                else -> 1.0 - lev(label, q).toDouble() / maxOf(label.length, q.length)
            }
            if (score > bestScore) {
                bestScore = score
                best = r.activityInfo.packageName
            }
        }
        return if (bestScore >= 0.7) best else null
    }

    private fun launch(c: Context, pkg: String): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: throw Exception("x")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (JarvisAccess.inst ?: c).startActivity(i)
        true
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- REAL phone actions
    private fun runAction(c: Context, name: String, arg: String): String? =
        try {
            when (name.trim().lowercase()) {
                "call" -> doCall(c, arg)
                "answer_call" -> doAnswer(c, arg.contains("speaker", ignoreCase = true))
                "end_call" -> doEndCall(c)
                "speaker" -> doSpeaker(c, arg)
                "volume" -> doVolume(c, arg)
                "brightness" -> doBrightness(c, arg)
                "flashlight" -> doTorch(c, arg)
                "media" -> doMedia(c, arg)
                "play_song" -> doPlaySong(c, arg)
                "timer" -> doTimer(c, arg)
                "alarm" -> doAlarm(c, arg)
                "home" -> doGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "Going to the home screen.")
                "lock" -> doGlobalLock()
                "wake" -> doWake(c)
                "unlock" -> doUnlock(c)
                "open_app" -> {
                    val n = arg.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                    val pkg = findApp(c, n)
                    if (pkg != null && launch(c, pkg)) "Opening ${arg.trim()}." else "I could not open ${arg.trim()}."
                }
                "stop_listening" -> { c.stopService(Intent(c, JarvisService::class.java)); "Listening off." }
                else -> null
            }
        } catch (e: Exception) {
            "I could not do that: ${e.message}"
        }

    private fun hasPerm(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun doGlobal(action: Int, ok: String): String {
        val acc = JarvisAccess.inst ?: return "Turn on Jarvis in Accessibility settings first."
        return if (acc.performGlobalAction(action)) ok else "The phone refused that."
    }

    private fun doGlobalLock(): String =
        if (Build.VERSION.SDK_INT >= 28) doGlobal(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Screen off.")
        else "Locking the screen needs Android 9 or newer."

    @Suppress("DEPRECATION")
    private fun doWake(c: Context, dismiss: Boolean = false): String {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake")
            wl.acquire(5000)
        } catch (_: Exception) {}
        try {
            val i = Intent(c, WakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("dismiss", dismiss)
            (JarvisAccess.inst ?: c).startActivity(i)
        } catch (_: Exception) {}
        return "Screen on."
    }

    private fun doUnlock(c: Context): String {
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) return "The phone is already unlocked."
        if (km.isDeviceSecure) {
            doWake(c, false)
            return "Screen on. Your phone has a PIN, pattern or fingerprint lock, so I can't unlock it for you."
        }
        doWake(c, true)
        val acc = JarvisAccess.inst
        if (acc != null) {
            Thread {
                try { Thread.sleep(1200) } catch (_: Exception) {}
                acc.swipeUp()
            }.start()
        }
        return "Unlocking."
    }

    private fun cleanName(x: String): String =
        x.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Best match by whole words: "akash" finds "Akash S" but never "Prakash". */
    private fun findContact(c: Context, name: String): Pair<String, String>? {
        val q = cleanName(name)
        if (q.isEmpty()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        var best: Pair<String, String>? = null
        var bestScore = 0
        c.contentResolver.query(uri, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val dn = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val full = cleanName(dn)
                val tokens = full.split(" ")
                val score = when {
                    full == q -> 100
                    tokens.contains(q) -> 80
                    q.contains(" ") && (full.startsWith(q) || full.contains(" $q")) -> 70
                    tokens.any { it.startsWith(q) } -> 60
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(dn, num)
                }
            }
        }
        return best
    }

    private fun contactFor(c: Context, q: String): Pair<String, String>? {
        val low = q.lowercase()
        val names = when {
            low in DAD -> DAD
            low in MOM -> MOM
            else -> listOf(q)
        }
        for (n in names) {
            val h = findContact(c, n)
            if (h != null) return h
        }
        return null
    }

    private fun place(c: Context, number: String, label: String, speaker: Boolean): String {
        val tm = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
        if (speaker) speakerLater(c)
        return if (speaker) "Calling $label on speaker." else "Calling $label."
    }

    /** Used by instant commands: null means "no such contact", so the AI gets a chance to understand it. */
    private fun callLocal(c: Context, who: String, speaker: Boolean): String? {
        if (!hasPerm(c, Manifest.permission.CALL_PHONE)) return PERM_CALL
        if (!hasPerm(c, Manifest.permission.READ_CONTACTS)) return PERM_CONTACTS
        val hit = contactFor(c, who) ?: return null
        return place(c, hit.second, hit.first, speaker)
    }

    private fun doCall(c: Context, arg: String): String {
        if (!hasPerm(c, Manifest.permission.CALL_PHONE)) return PERM_CALL
        var q = arg.trim()
        val speaker = Regex("speaker", RegexOption.IGNORE_CASE).containsMatchIn(q)
        q = q.replace(Regex("\\s*(on|with|in)?\\s*(the )?speaker(phone)?", RegexOption.IGNORE_CASE), "").trim()
        if (q.isEmpty()) return "Who should I call?"
        val isNumber = q.all { it.isDigit() || it in "+ -()" }
        if (isNumber) return place(c, q, q, speaker)
        if (!hasPerm(c, Manifest.permission.READ_CONTACTS)) return PERM_CONTACTS
        val hit = contactFor(c, q) ?: return "I could not find a contact named $q."
        return place(c, hit.second, hit.first, speaker)
    }

    private fun speakerLater(c: Context) {
        Thread {
            for (i in 0 until 20) {
                try { Thread.sleep(1000) } catch (_: Exception) {}
                try {
                    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.isSpeakerphoneOn = true
                } catch (_: Exception) {}
                val ok = JarvisAccess.inst?.toggleByLabel(listOf("speaker"), true) ?: false
                if (ok) break
            }
        }.start()
    }

    private fun doSpeaker(c: Context, arg: String): String {
        val on = arg.trim().lowercase() != "off"
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {}
        val ok = JarvisAccess.inst?.toggleByLabel(listOf("speaker"), on) ?: false
        return if (ok) (if (on) "Speaker on." else "Speaker off.")
        else "I tried, but I could not find the speaker button on the call screen."
    }

    private fun doAnswer(c: Context, speaker: Boolean): String {
        if (!hasPerm(c, Manifest.permission.ANSWER_PHONE_CALLS))
            return "I need permission to answer calls. Open Jarvis settings and tap Allow calling and contacts."
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringing = am.mode == AudioManager.MODE_RINGTONE
        val tm = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.acceptRingingCall()
        if (speaker) speakerLater(c)
        return if (ringing) (if (speaker) "Answering on speaker." else "Answering the call.")
        else "No call seems to be ringing, but I tried to answer."
    }

    private fun doEndCall(c: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "Ending calls needs Android 9 or newer."
        if (!hasPerm(c, Manifest.permission.ANSWER_PHONE_CALLS))
            return "I need permission to end calls. Open Jarvis settings and tap Allow calling and contacts."
        val tm = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return if (tm.endCall()) "Call ended." else "There was no call to end."
    }

    private fun doVolume(c: Context, arg: String): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = if (am.mode == AudioManager.MODE_IN_CALL) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        val a = arg.trim().lowercase()
        val target = when {
            a == "max" -> max
            a == "min" || a == "mute" -> 0
            a == "up" -> minOf(max, cur + maxOf(1, max / 5))
            a == "down" -> maxOf(0, cur - maxOf(1, max / 5))
            a.toIntOrNull() != null -> a.toInt().coerceIn(0, 100) * max / 100
            else -> return "I did not understand that volume request."
        }
        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return "Volume set to ${target * 100 / max} percent."
    }

    private fun doBrightness(c: Context, arg: String): String {
        if (!Settings.System.canWrite(c))
            return "I need permission to change brightness. Open Jarvis settings and tap Allow brightness control."
        val a = arg.trim().lowercase()
        val cur = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when {
            a == "max" -> 255
            a == "min" -> 10
            a == "up" -> minOf(255, cur + 50)
            a == "down" -> maxOf(5, cur - 50)
            a.toIntOrNull() != null -> maxOf(5, a.toInt().coerceIn(0, 100) * 255 / 100)
            else -> return "I did not understand that brightness request."
        }
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return "Brightness set to ${target * 100 / 255} percent."
    }

    private fun doTorch(c: Context, arg: String): String {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        val on = arg.trim().lowercase() != "off"
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun doMedia(c: Context, arg: String): String {
        val a = arg.trim().lowercase()
        if (LocalPlayer.hasTrack()) {
            when (a) {
                "play" -> return LocalPlayer.resume()
                "pause" -> return LocalPlayer.pause()
                "next" -> return LocalPlayer.next()
                "previous" -> return LocalPlayer.previous()
            }
        }
        val code = when (a) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "I did not understand that music request."
        }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Sent $a to your music player."
    }

    private fun pressPlayLater(c: Context, ms: Long) {
        Thread {
            try { Thread.sleep(ms) } catch (_: Exception) {}
            try { doMedia(c, "play") } catch (_: Exception) {}
        }.start()
    }

    private fun doPlaySong(c: Context, arg: String): String {
        val wantSpotify = Regex("(on|in|using|with) spotify", RegexOption.IGNORE_CASE).containsMatchIn(arg)
        val q = arg.replace(Regex("\\s*(on|in|using|with) spotify", RegexOption.IGNORE_CASE), "").trim()
        if (!wantSpotify) {
            val r = LocalPlayer.play(c, q.ifEmpty { null })
            if (LocalPlayer.hasTrack() || r.startsWith("I need permission")) return r
        }
        val spotify = "com.spotify.music"
        val hasSpotify = try { c.packageManager.getPackageInfo(spotify, 0); true } catch (e: Exception) { false }
        if (q.isNotEmpty()) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (hasSpotify) i.setPackage(spotify)
            (JarvisAccess.inst ?: c).startActivity(i)
            pressPlayLater(c, 3500)
            return "Asking ${if (hasSpotify) "Spotify" else "your music app"} to play $q."
        }
        if (hasSpotify && !launch(c, spotify)) return "I could not open Spotify."
        pressPlayLater(c, 2500)
        return if (hasSpotify) "Opening Spotify and pressing play." else "Pressing play on your music player."
    }

    private fun doTimer(c: Context, arg: String): String {
        val secs = arg.trim().toIntOrNull() ?: return "I did not understand the timer length."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (JarvisAccess.inst ?: c).startActivity(i)
        return if (secs >= 60) "Timer set for ${secs / 60} minutes." else "Timer set for $secs seconds."
    }

    private fun doAlarm(c: Context, arg: String): String {
        val parts = arg.trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return "I did not understand the alarm time."
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (JarvisAccess.inst ?: c).startActivity(i)
        return "Alarm set for %02d:%02d.".format(h, m)
    }
}
