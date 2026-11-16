package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            add(i.getStringExtra("who") ?: "", i.getStringExtra("text") ?: "")
        }
    }

    private fun add(who: String, text: String) = runOnUiThread {
        log.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun field(hint: String, value: String, pass: Boolean = false, number: Boolean = false) =
        EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine()
            if (pass) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Brain.prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 24)
        }
        root.addView(TextView(this).apply { text = "Jarvis"; textSize = 24f })

        val groq = field("Groq API key", p.getString("groq", "") ?: "", pass = true)
        val pico = field("Picovoice access key", p.getString("pico", "") ?: "", pass = true)
        val cutoff = field("Auto-off battery %", p.getInt("cutoff", 30).toString(), number = true)
        root.addView(groq); root.addView(pico); root.addView(cutoff)

        fun save() {
            p.edit().putString("groq", groq.text.toString().trim())
                .putString("pico", pico.text.toString().trim())
                .putInt("cutoff", cutoff.text.toString().toIntOrNull() ?: 30).apply()
        }

        val row1 = LinearLayout(this)
        row1.addView(button("Start") { save(); startListening() })
        row1.addView(button("Stop") { stopService(Intent(this, JarvisService::class.java)); add("Jarvis", "Stopped listening.") })
        root.addView(row1)

        val row2 = LinearLayout(this)
        row2.addView(button("Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        row2.addView(button("Overlay") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(row2)

        log = TextView(this).apply { textSize = 16f; setPadding(8, 16, 8, 16) }
        scroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(scroll)

        val input = EditText(this).apply {
            hint = "Type a command or question"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setOnClickListener
                save(); input.setText("")
                add("You", t)
                Thread { add("Jarvis", Brain.handle(this@MainActivity, t)) }.start()
            }
        }
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(input); bottom.addView(send)
        root.addView(bottom)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, rx, IntentFilter("com.jarvis.app.MSG"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        super.onStop()
    }

    private fun startListening() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        val p = Brain.prefs(this)
        if ((p.getString("pico", "") ?: "").isBlank()) { add("Jarvis", "Add your Picovoice key first."); return }
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else add("Jarvis", "Microphone permission is needed.")
    }
}
