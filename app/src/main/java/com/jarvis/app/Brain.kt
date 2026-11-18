package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpError(val code: Int, val msg: String) : Exception("HTTP $code $msg")

object Brain {
    private val history = ArrayList<JSONObject>()
    private val FALLBACK = listOf("gemini-2.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-3.5-flash")
    private const val SYSTEM = "You are Jarvis, a fast personal assistant on the user's phone, in the style of the AI from the Iron Man films. " +
            "The user's name is Boss. Always answer in the language the user used (for example Tamil in Tamil). " +
            "Keep every answer to one to three short spoken sentences. No markdown, no lists, no emojis. The user may start by saying your name, Jarvis; that is just calling you."

    fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun clearHistory() { history.clear() }

    private fun remember(role: String, text: String) {
        history.add(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.size > 12) history.removeAt(0)
    }

    // ---------------------------------------------------------------- typed text
    fun handle(c: Context, raw: String): String =
        try {
            if (raw.isBlank()) "" else (tryLocal(c, raw) ?: chat(c, raw.trim()))
        } catch (e: Exception) { "Error: ${e.message}" }

    // ---------------------------------------------------------------- spoken audio (one call: hear + answer)
    /** Returns (what you said, Jarvis's answer). Throws on errors. */
    fun handleAudio(c: Context, wav: ByteArray): Pair<String, String> {
        val (heard, reply) = askAudio(c, wav)
        if (heard.isBlank()) return Pair("", "")
        val answer = tryLocal(c, heard) ?: reply.ifBlank { "Sorry Boss, I did not catch that." }
        remember("user", heard)
        remember("model", answer)
        return Pair(heard, answer)
    }

    private fun askAudio(c: Context, wav: ByteArray): Pair<String, String> {
        val audio = JSONObject().put("inline_data",
            JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        val ask = JSONObject().put("text",
            "Listen to the audio. Reply ONLY with JSON in this exact form: " +
                    "{\"heard\": \"the exact words spoken\", \"reply\": \"your answer\"}. " +
                    "The speaker may begin with the word Jarvis; keep it in heard. If nothing intelligible was said, use empty strings.")
        val contents = JSONArray()
        for (m in history) contents.put(m)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(ask).put(audio)))
        val cfg = JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 1024)
        var text = generate(c, contents, cfg).trim()
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = JSONObject(text)
        return Pair(j.optString("heard", "").trim(), j.optString("reply", "").trim())
    }

    private fun chat(c: Context, text: String): String {
        val contents = JSONArray()
        for (m in history) contents.put(m)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        val reply = generate(c, contents, JSONObject().put("maxOutputTokens", 1024)).trim()
        remember("user", text)
        remember("model", reply)
        return reply
    }

    // ---------------------------------------------------------------- Gemini call (tries several model names)
    private fun generate(c: Context, contents: JSONArray, cfg: JSONObject): String {
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
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", contents)
                    .put("generationConfig", conf)
                    .toString().toByteArray()
                val res = post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", key, body)
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
        con.readTimeout = 20000
        con.setRequestProperty("x-goog-api-key", key)
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(body) }
        val code = con.responseCode
        val stream = if (code in 200..299) con.inputStream else con.errorStream
        val s = stream?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw HttpError(code, s.take(200))
        return s
    }

    // ---------------------------------------------------------------- local commands (instant, no AI)
    private fun tryLocal(c: Context, raw: String): String? {
        var t = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("jarvis").trim()
        if (t.isEmpty()) return null
        if (t == "off" || Regex("(stop listening|turn off|shut down|go to sleep)").containsMatchIn(t)) {
            c.stopService(Intent(c, JarvisService::class.java))
            return "Listening off."
        }
        if (t.startsWith("stop")) { JarvisAccess.stopScroll(); return "Stopped." }
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
        return null
    }

    private fun findApp(c: Context, name: String): String? {
        val pm = c.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        for (r in list) {
            val label = r.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9 ]"), "")
            if (label == name) return r.activityInfo.packageName
            if (best == null && label.isNotEmpty() && (label.contains(name) || name.contains(label)))
                best = r.activityInfo.packageName
        }
        return best
    }

    private fun launch(c: Context, pkg: String): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: throw Exception("x")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (JarvisAccess.inst ?: c).startActivity(i)
        true
    } catch (e: Exception) { false }
}
