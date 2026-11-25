package com.jarvis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

/**
 * A 3D-rendered Iron Man bust (red and gold armour, glowing eyes, arc reactor) with live effects:
 * the eyes blink on every key you type and flare while Jarvis listens, the reactor pulses,
 * and a scan line sweeps over it like the Jarvis HUD. It is stable (it does not spin).
 */
class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val cyan = Color.parseColor("#22D3EE")
    private val ice = Color.parseColor("#C8F7FF")
    private val rnd = java.util.Random(5)

    @Volatile var energy = 0f
    @Volatile var compact = false
    var onDouble: (() -> Unit)? = null

    private val bmpOpen: Bitmap
    private val bmpClosed: Bitmap
    private var assemble = 0f
    private var smooth = 0f
    private var last = 0L
    private var blinkUntil = 0L
    private var nextBlink = 0L
    private var flash = 0f

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val src = Rect()
    private val dst = RectF()

    init {
        val o = BitmapFactory.Options()
        o.inScaled = false
        bmpOpen = BitmapFactory.decodeResource(resources, R.drawable.iron_open, o)
        bmpClosed = BitmapFactory.decodeResource(resources, R.drawable.iron_closed, o)
        bmpPaint.isFilterBitmap = true
        hud.style = Paint.Style.STROKE
        hud.color = cyan
        hud.strokeCap = Paint.Cap.ROUND
        txt.color = cyan
        txt.textAlign = Paint.Align.CENTER
        txt.typeface = Typeface.MONOSPACE
        glow.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(235, 235, 254, 255), Color.argb(150, 34, 211, 238), Color.argb(0, 34, 211, 238)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
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

    /** The eyes blink (called on every key you type). */
    fun blinkNow() {
        blinkUntil = SystemClock.uptimeMillis() + 130
        flash = 1f
    }

    fun reassemble() { assemble = 0f }

    private fun flare(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        glow.alpha = alpha.coerceIn(0, 255)
        c.save()
        c.translate(x, y)
        c.scale(r, r)
        c.drawCircle(0f, 0f, 1f, glow)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else minOf(now - last, 50L)
        last = now
        smooth += (energy - smooth) * 0.08f
        if (assemble < 1f) assemble = minOf(1f, assemble + dt / 1700f)
        flash = maxOf(0f, flash - dt / 400f)
        if (now >= nextBlink) {
            blinkUntil = now + 140
            nextBlink = now + 2200 + rnd.nextInt(3800)
        }
        val blinking = now < blinkUntil
        val glowAmt = (0.30f + 0.70f * smooth + 0.6f * flash).coerceAtMost(1.1f)

        val w = width.toFloat()
        val h = height.toFloat()
        val cmp = compact
        val side = if (cmp) 4f * d else 14f * d
        val padTop = if (cmp) 4f * d else 14f * d
        val padBottom = if (cmp) 4f * d else 26f * d
        val bw = bmpOpen.width
        val bh = bmpOpen.height
        val srcH = if (cmp) (bh * 0.60f).toInt() else bh
        val availW = w - 2f * side
        val availH = h - padTop - padBottom
        val sc = minOf(availW / bw, availH / srcH)
        val dw = bw * sc
        val dh = srcH * sc
        val left = (w - dw) / 2f
        val top = padTop + (availH - dh) / 2f
        val reveal = 1f - (1f - assemble) * (1f - assemble)

        // HUD frame (corner brackets, edge ticks) and a scan line
        if (!cmp) {
            hud.color = cyan
            hud.strokeWidth = 1.2f * d
            hud.alpha = 150
            val m = 10f * d
            val l = 24f * d
            c.drawLine(m, m, m + l, m, hud); c.drawLine(m, m, m, m + l, hud)
            c.drawLine(w - m, m, w - m - l, m, hud); c.drawLine(w - m, m, w - m, m + l, hud)
            c.drawLine(m, h - m, m + l, h - m, hud); c.drawLine(m, h - m, m, h - m - l, hud)
            c.drawLine(w - m, h - m, w - m - l, h - m, hud); c.drawLine(w - m, h - m, w - m, h - m - l, hud)
            hud.alpha = 55
            var ty = m + l + 6f * d
            while (ty < h - m - l - 6f * d) {
                c.drawLine(m, ty, m + 6f * d, ty, hud)
                c.drawLine(w - m, ty, w - m - 6f * d, ty, hud)
                ty += 14f * d
            }
        }

        // the suit
        c.save()
        c.clipRect(0f, 0f, w, top + dh * reveal)
        val breathe = 1f + 0.004f * sin(now / 900f)
        c.scale(breathe, breathe, w / 2f, top + dh / 2f)
        src.set(0, 0, bw, srcH)
        dst.set(left, top, left + dw, top + dh)
        bmpPaint.alpha = (255 * minOf(1f, assemble * 2.5f)).toInt()
        c.drawBitmap(if (blinking) bmpClosed else bmpOpen, src, dst, bmpPaint)
        c.restore()

        // glowing scan edge while the suit builds itself
        if (assemble < 1f) {
            hud.color = ice
            hud.alpha = 230
            hud.strokeWidth = 2f * d
            val y = top + dh * reveal
            c.drawLine(left, y, left + dw, y, hud)
        }

        if (assemble > 0.7f) {
            val fade = ((assemble - 0.7f) / 0.3f).coerceIn(0f, 1f)
            val ex1 = left + 0.447f * bw * sc
            val ex2 = left + 0.551f * bw * sc
            val ey = top + 0.238f * bh * sc
            // eyes flare while listening / speaking and when a key is typed
            if (!blinking) {
                val r = dw * 0.055f * (1f + 0.7f * glowAmt)
                flare(c, ex1, ey, r, (fade * 215f * glowAmt.coerceAtMost(1f)).toInt())
                flare(c, ex2, ey, r, (fade * 215f * glowAmt.coerceAtMost(1f)).toInt())
            }
            if (!cmp) {
                // arc reactor: pulsing glow and turning arcs
                val rx = left + 0.498f * bw * sc
                val ry = top + 0.806f * bh * sc
                val pulse = 0.5f + 0.5f * sin(now / 260f)
                flare(c, rx, ry, dw * 0.085f * (1f + 0.25f * pulse * (0.4f + 0.6f * glowAmt)), (fade * 200f).toInt())
                val rot = (now / 28L % 360L).toFloat()
                hud.color = ice
                hud.strokeWidth = 2.2f * d
                hud.alpha = (fade * 240f).toInt()
                val rr = dw * 0.036f
                oval.set(rx - rr, ry - rr, rx + rr, ry + rr)
                for (k in 0 until 3) c.drawArc(oval, rot + k * 120f, 55f, false, hud)
            }
        }

        // scan line and label
        if (!cmp) {
            val scanY = top + (now / 12L % (dh.toLong() + 1)).toFloat()
            hud.color = cyan
            hud.strokeWidth = 1.5f * d
            hud.alpha = 38
            c.drawLine(left, scanY, left + dw, scanY, hud)
            txt.textSize = 12f * d
            txt.letterSpacing = 0.35f
            txt.alpha = 170
            c.drawText("J.A.R.V.I.S", w / 2f, h - 8f * d, txt)
        }
        postInvalidateOnAnimation()
    }
}
