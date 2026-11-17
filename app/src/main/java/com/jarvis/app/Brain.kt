package com.jarvis.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Brain {
    private val history = ArrayList<JSONObject>()

    fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun clearHistory() { history.clear() }

    fun handle(c: Context, raw: String): String =
        try { run(c, raw) } catch (e: Exception) { "Error: ${e.message}" }

    private fun run(c: Context, raw: String): String {
        var t = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("jarvis").trim()
        if (t.isEmpty()) return if (raw.isBlank()) "" else chat(c, raw.trim())

        // ---- local commands (instant, no AI) ----
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
            val pkg = findApp(c, name) ?: return "I could not find $name."
            return if (launch(c, pkg)) "Opening $name." else "Could not open $name (allow Display over other apps)."
        }
        // ---- everything else goes to the AI ----
        return chat(c, raw.trim())
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

    private fun chat(c: Context, text: String): String {
        val key = prefs(c).getString("groq", "") ?: ""
        if (key.isBlank()) return "Add your Groq key first."
        history.add(JSONObject().put("role", "user").put("content", text))
        while (history.size > 10) history.removeAt(0)
        val msgs = JSONArray().put(
            JSONObject().put("role", "system").put("content",
                "You are Jarvis, a fast assistant on the user's phone. Reply in one or two short sentences, in the user's language.")
        )
        for (m in history) msgs.put(m)
        val body = JSONObject().put("model", "llama-3.3-70b-versatile")
            .put("messages", msgs).put("max_tokens", 200)
        val res = post("https://api.groq.com/openai/v1/chat/completions", key, "application/json", body.toString().toByteArray())
        val reply = JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        history.add(JSONObject().put("role", "assistant").put("content", reply))
        return reply
    }

    fun transcribe(c: Context, wav: ByteArray): String {
        val key = prefs(c).getString("groq", "") ?: ""
        if (key.isBlank()) throw Exception("Add your Groq key first.")
        val b = "----jarvis" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        out.write("--$b\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n".toByteArray())
        out.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav)
        out.write("\r\n--$b--\r\n".toByteArray())
        val res = post("https://api.groq.com/openai/v1/audio/transcriptions", key, "multipart/form-data; boundary=$b", out.toByteArray())
        return JSONObject(res).optString("text", "").trim()
    }

    private fun post(url: String, key: String, type: String, body: ByteArray): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 10000
        con.readTimeout = 30000
        con.setRequestProperty("Authorization", "Bearer $key")
        con.setRequestProperty("Content-Type", type)
        con.outputStream.use { it.write(body) }
        val code = con.responseCode
        val stream = if (code in 200..299) con.inputStream else con.errorStream
        val s = stream.bufferedReader().readText()
        if (code !in 200..299) throw Exception("HTTP $code ${s.take(150)}")
        return s
    }
}
