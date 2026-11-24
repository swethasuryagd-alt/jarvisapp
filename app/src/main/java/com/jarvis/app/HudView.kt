package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A holographic Iron Man helmet and chest in the style of the Jarvis HUD screens: glowing cyan lines,
 * red and gold armour tint, glowing eyes and an arc reactor. It is stable (it does not spin).
 * The eyes blink on every key you type, glow while Jarvis listens, and the mouth lights up while it speaks.
 */
class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val cyan = Color.parseColor("#22D3EE")
    private val ice = Color.parseColor("#C8F7FF")
    private val red = Color.parseColor("#C21F26")
    private val gold = Color.parseColor("#D9A21B")
    private val rnd = java.util.Random(5)

    @Volatile var energy = 0f
    @Volatile var compact = false
    var onDouble: (() -> Unit)? = null

    private var assemble = 0f
    private var smooth = 0f
    private var last = 0L
    private var eyeOpen = 1f
    private var blinkUntil = 0L
    private var nextBlink = 0L
    private var flash = 0f

    // ----- geometry (unit space: x -1.4..1.4, y -1.2 (helmet top) .. 2.3 (chest bottom)) -----
    private class Shape(val path: Path, val kind: Int, val part: Int) {
        val pm = PathMeasure(path, false)
        val len = pm.length
    }

    private val shapes = ArrayList<Shape>()
    private val eyes = ArrayList<Path>()
    private val mouth = ArrayList<Path>()
    private var curPart = 0

    private fun pts(vararg v: Float): Array<FloatArray> = Array(v.size / 2) { floatArrayOf(v[2 * it], v[2 * it + 1]) }

    private fun closedSym(kind: Int, r: Array<FloatArray>) {
        val p = Path()
        p.moveTo(r[0][0], r[0][1])
        for (i in 1 until r.size) p.lineTo(r[i][0], r[i][1])
        for (i in r.size - 2 downTo 1) p.lineTo(-r[i][0], r[i][1])
        p.close()
        shapes.add(Shape(p, kind, curPart))
    }

    private fun polySym(kind: Int, r: Array<FloatArray>) {
        for (s in floatArrayOf(1f, -1f)) {
            val p = Path()
            p.moveTo(r[0][0] * s, r[0][1])
            for (i in 1 until r.size) p.lineTo(r[i][0] * s, r[i][1])
            p.close()
            shapes.add(Shape(p, kind, curPart))
        }
    }

    private fun lineSym(kind: Int, r: Array<FloatArray>) {
        for (s in floatArrayOf(1f, -1f)) {
            val p = Path()
            p.moveTo(r[0][0] * s, r[0][1])
            for (i in 1 until r.size) p.lineTo(r[i][0] * s, r[i][1])
            shapes.add(Shape(p, kind, curPart))
        }
    }

    private fun lineOne(kind: Int, vararg v: Float) {
        val p = Path()
        p.moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) {
            p.lineTo(v[i], v[i + 1])
            i += 2
        }
        shapes.add(Shape(p, kind, curPart))
    }

    // kinds: 0 helmet (red tint), 1 faceplate (gold tint), 2 line, 3 chest (red tint), 4 plate (gold tint)
    private fun build() {
        curPart = 0
        closedSym(0, pts(0f, -1.20f, 0.28f, -1.18f, 0.50f, -1.08f, 0.63f, -0.90f, 0.68f, -0.62f, 0.71f, -0.30f,
            0.69f, 0.02f, 0.62f, 0.30f, 0.50f, 0.54f, 0.32f, 0.72f, 0.14f, 0.82f, 0f, 0.84f))
        closedSym(1, pts(0f, -0.98f, 0.22f, -0.96f, 0.42f, -0.88f, 0.52f, -0.66f, 0.52f, -0.40f, 0.48f, -0.12f,
            0.40f, 0.20f, 0.28f, 0.46f, 0.14f, 0.62f, 0f, 0.66f))
        polySym(4, pts(0.56f, -0.70f, 0.70f, -0.60f, 0.72f, -0.28f, 0.66f, -0.05f, 0.56f, -0.12f))
        lineOne(2, 0f, -1.20f, 0f, -0.98f)
        lineOne(2, 0f, -0.98f, 0f, -0.50f)
        lineSym(2, pts(0.16f, -1.18f, 0.30f, -1.00f, 0.40f, -0.90f))
        lineSym(2, pts(0.04f, -0.52f, 0.24f, -0.50f, 0.48f, -0.58f))
        lineSym(2, pts(0.06f, -0.20f, 0.09f, 0.12f, 0.04f, 0.26f, 0f, 0.28f))
        lineSym(2, pts(0.46f, -0.10f, 0.36f, 0.18f, 0.22f, 0.38f))
        lineSym(2, pts(0.52f, -0.90f, 0.60f, -0.60f, 0.58f, -0.30f))
        lineSym(2, pts(0.22f, 0.80f, 0.26f, 0.98f))
        lineSym(2, pts(0.36f, 0.70f, 0.38f, 0.96f))
        lineOne(2, -0.40f, 0.98f, 0.40f, 0.98f)

        curPart = 1
        lineSym(2, pts(0.38f, 0.98f, 0.62f, 1.03f, 1.00f, 1.10f, 1.28f, 1.28f, 1.40f, 1.55f, 1.40f, 1.95f, 1.34f, 2.30f))
        polySym(4, pts(0.78f, 1.08f, 1.10f, 1.12f, 1.30f, 1.30f, 1.36f, 1.58f, 1.05f, 1.55f, 0.82f, 1.40f))
        closedSym(3, pts(0f, 1.10f, 0.50f, 1.14f, 0.80f, 1.40f, 0.84f, 1.80f, 0.62f, 2.05f, 0f, 2.12f))
        lineSym(2, pts(0.26f, 1.18f, 0.40f, 1.42f, 0.38f, 1.62f))
        lineSym(2, pts(0.20f, 1.98f, 0.24f, 2.30f))
        lineOne(2, -0.55f, 2.0f, 0.55f, 2.0f)

        // eyes
        val eyeR = pts(0.10f, -0.36f, 0.46f, -0.46f, 0.50f, -0.34f, 0.40f, -0.24f, 0.14f, -0.20f)
        for (s in floatArrayOf(1f, -1f)) {
            val p = Path()
            p.moveTo(eyeR[0][0] * s, eyeR[0][1])
            for (i in 1 until eyeR.size) p.lineTo(eyeR[i][0] * s, eyeR[i][1])
            p.close()
            eyes.add(p)
        }
        // mouth bars
        val bars = arrayOf(floatArrayOf(0.38f, 0.16f), floatArrayOf(0.45f, 0.12f), floatArrayOf(0.52f, 0.08f))
        for (b in bars) {
            val p = Path()
            p.moveTo(-b[1], b[0])
            p.lineTo(b[1], b[0])
            mouth.add(p)
        }
    }

    // ----- paints -----
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = Path()
    private val oval = RectF()

    init {
        line.style = Paint.Style.STROKE
        line.strokeJoin = Paint.Join.ROUND
        line.strokeCap = Paint.Cap.ROUND
        line.pathEffect = CornerPathEffect(0.05f)
        fill.style = Paint.Style.FILL
        fill.pathEffect = CornerPathEffect(0.05f)
        hud.style = Paint.Style.STROKE
        hud.color = cyan
        txt.color = cyan
        txt.textAlign = Paint.Align.CENTER
        txt.typeface = android.graphics.Typeface.MONOSPACE
        build()
    }

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { blinkNow(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onDouble?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { assemble = 0f }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return true
    }

    /** The eyes blink (call this on every key press). */
    fun blinkNow() {
        blinkUntil = SystemClock.uptimeMillis() + 130
        flash = 1f
    }

    fun reassemble() { assemble = 0f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        corePaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(255, 240, 254, 255), Color.argb(210, 34, 211, 238), Color.argb(0, 34, 211, 238)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ----- drawing -----
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else minOf(now - last, 50L)
        last = now
        smooth += (energy - smooth) * 0.08f
        if (assemble < 1f) assemble = minOf(1f, assemble + dt / 1800f)
        flash = maxOf(0f, flash - dt / 400f)

        if (now >= nextBlink) {
            blinkUntil = now + 140
            nextBlink = now + 2200 + rnd.nextInt(3800)
        }
        eyeOpen += ((if (now < blinkUntil) 0.08f else 1f) - eyeOpen) * 0.55f
        val glow = (0.55f + 0.45f * smooth + 0.3f * flash).coerceAtMost(1f)

        val w = width.toFloat()
        val h = height.toFloat()
        val cmp = compact
        val s = if (cmp) h / 2.25f else minOf(h / 3.6f, w / 3.1f)
        val ox = w / 2f
        val oy = if (cmp) h * 0.06f + 1.2f * s else h * 0.05f + 1.22f * s

        // HUD frame (corner brackets + edge ticks + scan line)
        if (!cmp) {
            hud.strokeWidth = 1.2f * d
            hud.alpha = 150
            val m = 10f * d
            val l = 24f * d
            c.drawLine(m, m, m + l, m, hud); c.drawLine(m, m, m, m + l, hud)
            c.drawLine(w - m, m, w - m - l, m, hud); c.drawLine(w - m, m, w - m, m + l, hud)
            c.drawLine(m, h - m, m + l, h - m, hud); c.drawLine(m, h - m, m, h - m - l, hud)
            c.drawLine(w - m, h - m, w - m - l, h - m, hud); c.drawLine(w - m, h - m, w - m, h - m - l, hud)
            hud.alpha = 60
            var ty = m + l + 6f * d
            while (ty < h - m - l - 6f * d) {
                c.drawLine(m, ty, m + 6f * d, ty, hud)
                c.drawLine(w - m, ty, w - m - 6f * d, ty, hud)
                ty += 14f * d
            }
        }
        val scanY = (now / 14L % (h.toLong() + 1)).toFloat()
        hud.alpha = 45
        c.drawLine(ox - 1.4f * s, scanY, ox + 1.4f * s, scanY, hud)
        hud.alpha = 22
        c.drawLine(ox - 1.4f * s, scanY - 3f * d, ox + 1.4f * s, scanY - 3f * d, hud)

        c.save()
        c.translate(ox, oy)
        c.scale(s, s)
        val px = d / s   // one dp in unit space

        // armour and lines, drawn in one by one while the suit "assembles"
        val n = shapes.size
        for ((i, sh) in shapes.withIndex()) {
            if (cmp && sh.part == 1) continue
            val t = (assemble * 1.7f - i.toFloat() / n * 0.7f).coerceIn(0f, 1f)
            if (t <= 0f) continue
            if (t >= 1f && assemble > 0.85f) {
                val a = ((assemble - 0.85f) / 0.15f).coerceIn(0f, 1f)
                val fc = when (sh.kind) {
                    0 -> red
                    1 -> gold
                    3 -> red
                    4 -> gold
                    else -> 0
                }
                if (fc != 0) {
                    fill.color = fc
                    fill.alpha = (a * (if (sh.kind == 1) 85 else if (sh.kind == 4) 70 else 60)).toInt()
                    c.drawPath(sh.path, fill)
                }
            }
            val pathToDraw: Path
            if (t >= 1f) {
                pathToDraw = sh.path
            } else {
                seg.reset()
                sh.pm.getSegment(0f, sh.len * t, seg, true)
                pathToDraw = seg
            }
            line.color = cyan
            line.strokeWidth = 5.5f * px
            line.alpha = 34
            c.drawPath(pathToDraw, line)
            line.strokeWidth = (if (sh.kind == 2) 1.2f else 1.7f) * px
            line.alpha = if (sh.kind == 2) 190 else 235
            c.drawPath(pathToDraw, line)
        }

        if (assemble > 0.6f) {
            val fade = ((assemble - 0.6f) / 0.4f).coerceIn(0f, 1f)

            // glowing eyes (they blink and flare)
            c.save()
            c.scale(1f, eyeOpen.coerceAtLeast(0.06f), 0f, -0.32f)
            fill.color = ice
            fill.alpha = (fade * (150 + 105 * glow)).toInt()
            for (e in eyes) c.drawPath(e, fill)
            line.color = cyan
            line.strokeWidth = (7f + 5f * glow) * px
            line.alpha = (fade * (50 + 120 * glow)).toInt()
            for (e in eyes) c.drawPath(e, line)
            line.strokeWidth = 1.3f * px
            line.color = ice
            line.alpha = (fade * 255).toInt()
            for (e in eyes) c.drawPath(e, line)
            c.restore()

            // mouth bars light up like a voice meter while Jarvis is speaking
            line.color = cyan
            line.strokeWidth = 2.2f * px
            for ((i, m) in mouth.withIndex()) {
                val level = if (smooth > 0.5f) 0.5f + 0.5f * sin(now / 90f + i * 1.3f) else 0.25f
                line.alpha = (fade * (70 + 185 * level)).toInt()
                c.drawPath(m, line)
            }

            // arc reactor
            if (!cmp) {
                val ry = 1.62f
                val rot = (now / 28L % 360L).toFloat()
                val pulse = 0.5f + 0.5f * sin(now / 260f) * (0.4f + 0.6f * glow)
                c.save()
                c.translate(0f, ry)
                c.save()
                val core = 0.15f * (1f + 0.25f * pulse)
                c.scale(core * 1.6f, core * 1.6f)
                corePaint.alpha = (fade * 255).toInt()
                c.drawCircle(0f, 0f, 1f, corePaint)
                c.restore()
                line.color = cyan
                line.strokeWidth = 1.4f * px
                line.alpha = (fade * 220).toInt()
                c.drawCircle(0f, 0f, 0.26f, line)
                c.drawCircle(0f, 0f, 0.13f, line)
                line.color = ice
                line.strokeWidth = 3f * px
                line.alpha = (fade * 255).toInt()
                oval.set(-0.20f, -0.20f, 0.20f, 0.20f)
                for (k in 0 until 3) c.drawArc(oval, rot + k * 120f, 70f, false, line)
                c.restore()
            }
        }
        c.restore()

        if (!cmp) {
            txt.textSize = 12f * d
            txt.letterSpacing = 0.35f
            txt.alpha = 170
            c.drawText("J.A.R.V.I.S", w / 2f, h - 16f * d, txt)
        }
        postInvalidateOnAnimation()
    }
}
