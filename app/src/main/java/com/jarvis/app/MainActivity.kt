package com.jarvis.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.Calendar

private val BG = Color.parseColor("#05090D")
private val PANEL = Color.parseColor("#0F1A22")
private val MINE = Color.parseColor("#0F2F3A")
private val ACCENT = Color.parseColor("#22D3EE")
private val DIM = Color.parseColor("#8A8A99")
private val LINE = Color.parseColor("#1F4A57")

class MainActivity : ComponentActivity() {

    private lateinit var suit: SuitView
    private lateinit var barsL: BarsView
    private lateinit var barsR: BarsView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var greeting: TextView
    private lateinit var subtitle: TextView
    private lateinit var topSpace: View
    private lateinit var bottomSpace: View
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var typeBtn: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var controls: LinearLayout
    private lateinit var tabs: LinearLayout

    private var tracker: HandTracker? = null
    private var chatMode = false
    private val ui = Handler(Looper.getMainLooper())

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val who = i.getStringExtra("who") ?: ""
            val msg = i.getStringExtra("text") ?: ""
            when (who) {
                "Status" -> runOnUiThread { subtitle.text = msg }
                "State" -> handleState(msg)
                else -> addMessage(who, msg)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun circle(sizeDp: Int, fill: Int, strokeColor: Int, icon: IconView, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(fill)
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor)
        f.background = g
        f.addView(icon, FrameLayout.LayoutParams(-1, -1))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun LinearLayout.addM(v: View, w: Int, h: Int, m: Int) {
        val lp = LinearLayout.LayoutParams(w, h)
        lp.setMargins(m, m / 2, m, m / 2)
        addView(v, lp)
    }

    private fun greetWord(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (h < 12) "Morning" else if (h < 17) "Afternoon" else "Evening"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(BG)

        // ----- top bar -----
        val top = FrameLayout(this)
        val clock = IconView(this, IconView.CLOCK)
        clock.setOnClickListener { Toast.makeText(this, "History is coming soon", Toast.LENGTH_SHORT).show() }
        top.addView(clock, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        val title = TextView(this)
        title.text = "Jarvis"
        title.typeface = Typeface.SERIF
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        top.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val cam = IconView(this, IconView.CAMERA)
        cam.setOnClickListener { toggleCamera() }
        val camLp = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
        camLp.rightMargin = dp(46)
        top.addView(cam, camLp)
        val gear = IconView(this, IconView.GEAR)
        gear.setOnClickListener { showSettings() }
        top.addView(gear, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        main.addView(top, LinearLayout.LayoutParams(-1, dp(56)))

        // ----- status row (chat mode) -----
        statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER
        barsL = BarsView(this)
        barsR = BarsView(this)
        statusText = TextView(this)
        statusText.setTextColor(DIM)
        statusText.textSize = 13f
        statusRow.addView(barsL, LinearLayout.LayoutParams(dp(28), dp(18)))
        val stLp = LinearLayout.LayoutParams(-2, -2)
        stLp.setMargins(dp(8), 0, dp(8), 0)
        statusRow.addView(statusText, stLp)
        statusRow.addView(barsR, LinearLayout.LayoutParams(dp(28), dp(18)))
        statusRow.visibility = View.GONE
        main.addView(statusRow, LinearLayout.LayoutParams(-1, dp(28)))

        // ----- home: the nano-tech suit and greeting -----
        topSpace = View(this)
        main.addView(topSpace, LinearLayout.LayoutParams(-1, 0, 0.15f))

        suit = SuitView(this)
        suit.onDouble = { micCapture() }
        main.addView(suit, LinearLayout.LayoutParams(-1, dp(330)))

        greeting = TextView(this)
        greeting.text = "${greetWord()}, Boss."
        greeting.typeface = Typeface.SERIF
        greeting.textSize = 30f
        greeting.setTextColor(Color.WHITE)
        greeting.gravity = Gravity.CENTER
        main.addView(greeting, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        subtitle = TextView(this)
        subtitle.textSize = 13f
        subtitle.setTextColor(DIM)
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(dp(24), 0, dp(24), 0)
        main.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        bottomSpace = View(this)
        main.addView(bottomSpace, LinearLayout.LayoutParams(-1, 0, 0.5f))

        // ----- chat list (chat mode) -----
        chatList = LinearLayout(this)
        chatList.orientation = LinearLayout.VERTICAL
        chatScroll = ScrollView(this)
        chatScroll.addView(chatList)
        chatScroll.isVerticalScrollBarEnabled = false
        chatScroll.visibility = View.GONE
        main.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- Type button + input -----
        typeBtn = LinearLayout(this)
        typeBtn.orientation = LinearLayout.HORIZONTAL
        typeBtn.gravity = Gravity.CENTER
        typeBtn.addView(IconView(this, IconView.KEYBOARD), LinearLayout.LayoutParams(dp(30), dp(30)))
        val typeLabel = TextView(this)
        typeLabel.text = "Type"
        typeLabel.textSize = 16f
        typeLabel.setTextColor(Color.parseColor("#A8EAF7"))
        typeBtn.addView(typeLabel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        typeBtn.setOnClickListener { toggleInput() }
        main.addView(typeBtn, LinearLayout.LayoutParams(-1, dp(52)))

        inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(dp(12), dp(4), dp(12), dp(8))
        input = EditText(this)
        input.hint = "Type a command or question"
        input.setHintTextColor(DIM)
        input.setTextColor(Color.WHITE)
        input.background = rounded(PANEL, 22)
        input.setPadding(dp(16), dp(10), dp(16), dp(10))
        input.setSingleLine()
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = TextView(this)
        send.text = "Send"
        send.textSize = 15f
        send.setTextColor(ACCENT)
        send.setPadding(dp(14), dp(10), dp(6), dp(10))
        send.setOnClickListener { sendTyped() }
        inputRow.addView(send, LinearLayout.LayoutParams(-2, -2))
        inputRow.visibility = View.GONE
        main.addView(inputRow, LinearLayout.LayoutParams(-1, -2))

        // ----- controls (chat mode) -----
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.setPadding(0, 0, 0, dp(8))
        controls.addM(circle(52, BG, LINE, IconView(this, IconView.X)) { goHome() }, dp(52), dp(52), dp(16))
        controls.addM(circle(76, ACCENT, 0, IconView(this, IconView.MIC, BG)) { micCapture() }, dp(76), dp(76), dp(16))
        controls.addM(circle(52, BG, LINE, IconView(this, IconView.PLUS)) { newChat() }, dp(52), dp(52), dp(16))
        controls.visibility = View.GONE
        main.addView(controls, LinearLayout.LayoutParams(-1, -2))

        // ----- bottom tabs (home mode) -----
        tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(Color.parseColor("#0A1218"))
        val names = listOf("Jarvis", "Today", "Memory", "Files")
        for ((i, name) in names.withIndex()) {
            val tv = TextView(this)
            tv.text = name
            tv.gravity = Gravity.CENTER
            tv.textSize = 12f
            tv.setTextColor(if (i == 0) ACCENT else DIM)
            tv.setPadding(0, dp(14), 0, dp(14))
            if (i != 0) tv.setOnClickListener { Toast.makeText(this, "$name is coming soon", Toast.LENGTH_SHORT).show() }
            tabs.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        }
        main.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        setContentView(main)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, rx, IntentFilter("com.jarvis.app.MSG"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        stopCamera()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        subtitle.text = if (JarvisService.active) "Say “Jarvis”, double-tap the suit, or tap your AirPods"
        else "Double-tap the suit and speak. Drag to turn it. Tap = faceplate, hold = parts apart."
        if (tracker == null && Brain.prefs(this).getBoolean("camstart", false) &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleCamera()
    }

    // ------------------------------------------------------------------ hand control (camera is on only when you ask)

    private fun stopCamera() {
        tracker?.stop()
        tracker = null
    }

    private fun toggleCamera() {
        if (tracker?.running == true) {
            stopCamera()
            subtitle.text = "Hand control off."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4)
            return
        }
        val t = HandTracker(this, { dy, dp -> suit.rotateBy(dy, dp) }, { msg -> runOnUiThread { subtitle.text = msg } })
        tracker = t
        t.start()
    }

    // ------------------------------------------------------------------ modes

    private fun setMode(chat: Boolean) {
        if (chatMode == chat) return
        chatMode = chat
        topSpace.visibility = vis(!chat)
        bottomSpace.visibility = vis(!chat)
        greeting.visibility = vis(!chat)
        subtitle.visibility = vis(!chat)
        tabs.visibility = vis(!chat)
        statusRow.visibility = vis(chat)
        chatScroll.visibility = vis(chat)
        controls.visibility = vis(chat)
        suit.layoutParams = LinearLayout.LayoutParams(-1, if (chat) dp(130) else dp(330))
    }

    private fun goHome() {
        Speaker.stop()
        setStatus(null)
        setMode(false)
    }

    private fun newChat() {
        Speaker.stop()
        Brain.clearHistory()
        chatList.removeAllViews()
        setStatus(null)
    }

    // --------------------------------------------------------------- messages

    private fun addMessage(who: String, msg: String) = runOnUiThread {
        if (msg.isBlank()) return@runOnUiThread
        setMode(true)
        val mine = (who == "You" || who == "Boss")
        val label = TextView(this)
        label.text = if (mine) "Boss" else "Jarvis"
        label.setTextColor(DIM)
        label.textSize = 11f
        label.setPadding(dp(6), 0, dp(6), dp(3))
        val bubble = TextView(this)
        bubble.text = msg
        bubble.setTextColor(Color.WHITE)
        bubble.textSize = 15f
        bubble.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10))
        bubble.background = rounded(if (mine) MINE else PANEL, 16)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = if (mine) Gravity.END else Gravity.START
        col.addView(label)
        col.addView(bubble)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.setMargins(dp(16), dp(8), dp(16), dp(4))
        chatList.addView(col, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String?, energy: Float = 0f, animate: Boolean = false) = runOnUiThread {
        statusText.text = s ?: ""
        barsL.active = animate
        barsR.active = animate
        suit.energy = energy
    }

    /** Live state from the background listener, so the suit reacts when you just say "Jarvis". */
    private fun handleState(s: String) {
        when {
            s == "listening" -> {
                setMode(true)
                setStatus("Listening…", 0.8f, true)
            }
            s == "thinking" -> setStatus("Thinking…", 0.4f, false)
            s.startsWith("speaking:") -> {
                val len = s.substringAfter(":").toIntOrNull() ?: 40
                setStatus("Jarvis is speaking", 0.9f, false)
                ui.postDelayed({ setStatus(null) }, minOf(9000L, 1500L + len * 55L))
            }
            else -> setStatus(null)
        }
    }

    private fun finishReply(reply: String, speak: Boolean) {
        if (reply.isBlank()) { setStatus(null); return }
        if (speak && Brain.prefs(this).getBoolean("speak", true)) {
            Speaker.speak(this, reply) { msg -> addMessage("Jarvis", msg) }
            setStatus("Jarvis is speaking", 0.9f, false)
            ui.postDelayed({ setStatus(null) }, minOf(9000L, 1500L + reply.length * 55L))
        } else {
            setStatus(null)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun toggleInput() {
        val show = inputRow.visibility != View.VISIBLE
        inputRow.visibility = vis(show)
        if (show) {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        addMessage("Boss", t)
        setStatus("Thinking…", 0.4f, false)
        Thread {
            val speakOn = Brain.prefs(this).getBoolean("speak", true)
            val reply = Brain.handle(this, t) { interim ->
                addMessage("Jarvis", interim)
                if (speakOn) Speaker.speak(this, interim) { m -> addMessage("Jarvis", m) }
            }
            addMessage("Jarvis", reply)
            finishReply(reply, true)
        }.start()
    }

    private fun micCapture() {
        if (JarvisService.active) {
            // the background listener is already running: just tell it to listen right now
            JarvisService.trigger = true
            setMode(true)
            setStatus("Listening…", 0.8f, true)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (Brain.prefs(this).getString("gemini", "").isNullOrBlank()) {
            addMessage("Jarvis", "Add your Gemini key in settings (gear icon) first.")
            return
        }
        Speaker.stop()
        setMode(true)
        setStatus("Listening…", 0.8f, true)
        Thread {
            try {
                val wav = Recorder.record()
                if (wav == null) {
                    addMessage("Jarvis", "I didn't hear anything.")
                    setStatus(null)
                    return@Thread
                }
                setStatus("Thinking…", 0.4f, false)
                Brain.warmUp()
                val speakOn = Brain.prefs(this).getBoolean("speak", true)
                val (heard, answer) = Brain.handleAudio(this, wav, LocalStt.transcribe(wav, false)) { interim ->
                    addMessage("Jarvis", interim)
                    if (speakOn) Speaker.speak(this, interim) { m -> addMessage("Jarvis", m) }
                }
                if (heard.isBlank()) { setStatus(null); return@Thread }
                addMessage("Boss", heard)
                addMessage("Jarvis", answer)
                finishReply(answer, true)
            } catch (e: Exception) {
                addMessage("Jarvis", "Error: ${e.message}")
                setStatus(null)
            }
        }.start()
    }

    private fun startListening() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        if (Brain.prefs(this).getString("gemini", "").isNullOrBlank()) {
            addMessage("Jarvis", "Add your Gemini key in settings first.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
        subtitle.text = "Starting the listener…"
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        if (code == 4 && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleCamera()
    }

    // --------------------------------------------------------------- settings

    private fun pickVoice() {
        Speaker.voices(this) { list ->
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "No voices found. Install voices in Settings > Text-to-speech.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val sorted = list.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                val labels = sorted.map { it.locale.displayName + " - " + it.name }.toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Choose a voice")
                    .setItems(labels) { _, which ->
                        val name = sorted[which].name
                        Brain.prefs(this).edit().putString("voice", name).apply()
                        Speaker.preview(this, name)
                    }
                    .show()
            }
        }
    }

    private fun showSettings() {
        val p = Brain.prefs(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), dp(4))

        fun field(hint: String, value: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine()
            return e
        }
        fun check(label: String, on: Boolean): CheckBox {
            val c = CheckBox(this)
            c.text = label
            c.isChecked = on
            return c
        }

        val gemini = field("Gemini API key", p.getString("gemini", "") ?: "")
        gemini.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val cutoff = field("Auto-off battery %", p.getInt("cutoff", 30).toString())
        cutoff.inputType = InputType.TYPE_CLASS_NUMBER
        val model = field("AI model (blank = automatic)", p.getString("model", "") ?: "")
        val rate = field("Voice speed (1.0 = normal)", p.getFloat("rate", 1.0f).toString())
        val pitch = field("Voice pitch (1.0 = normal, lower = deeper)", p.getFloat("pitch", 1.0f).toString())
        val speakBox = check("Speak replies aloud", p.getBoolean("speak", true))
        val airBox = check("AirPods / headset button starts Jarvis", p.getBoolean("airpods", true))
        val btBox = check("Use the AirPods / headset microphone", p.getBoolean("bt", false))
        val camBox = check("Open the camera for hand control when I enter the app", p.getBoolean("camstart", false))

        fun save() {
            p.edit().putString("gemini", gemini.text.toString().trim())
                .putString("model", model.text.toString().trim())
                .putFloat("rate", rate.text.toString().toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f)
                .putFloat("pitch", pitch.text.toString().toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f)
                .putBoolean("speak", speakBox.isChecked)
                .putBoolean("airpods", airBox.isChecked)
                .putBoolean("bt", btBox.isChecked)
                .putBoolean("camstart", camBox.isChecked)
                .putInt("cutoff", cutoff.text.toString().toIntOrNull() ?: 30).apply()
        }
        fun btn(label: String, f: () -> Unit): Button {
            val bt = Button(this)
            bt.text = label
            bt.isAllCaps = false
            bt.setOnClickListener { save(); f() }
            return bt
        }

        box.addView(gemini)
        box.addView(cutoff)
        box.addView(model)
        box.addView(rate)
        box.addView(pitch)
        box.addView(speakBox)
        box.addView(airBox)
        box.addView(btBox)
        box.addView(camBox)
        box.addView(btn("Start background listening") { startListening() })
        box.addView(btn("Stop background listening") { stopService(Intent(this, JarvisService::class.java)) })
        box.addView(btn("Choose voice") { pickVoice() })
        box.addView(btn("Test voice") {
            Speaker.speak(this, "Hello Boss, I am Jarvis.") { msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        })
        box.addView(btn("Allow calling and contacts") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS), 3)
        })
        box.addView(btn("Allow music files") {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            requestPermissions(arrayOf(perm), 5)
        })
        box.addView(btn("Allow Bluetooth (AirPods microphone)") {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 6)
        })
        box.addView(btn("Allow brightness control") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        })
        box.addView(btn("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        box.addView(btn("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Settings")
            .setView(sv)
            .setPositiveButton("Done") { _, _ -> save() }
            .setOnDismissListener { save() }
            .show()
    }
}
