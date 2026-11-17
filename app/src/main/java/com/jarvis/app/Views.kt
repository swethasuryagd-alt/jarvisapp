package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rotating particle sphere (the orb). energy 0..1 makes it spin faster and pulse. */
class SphereView(ctx: Context) : View(ctx) {
    private val n = 520
    private val xs = FloatArray(n)
    private val ys = FloatArray(n)
    private val zs = FloatArray(n)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var angle = 0f
    private var last = 0L
    private var smooth = 0f
    @Volatile var energy = 0f

    init {
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            val th = i * 2.3999631f
            xs[i] = cos(th) * r
            ys[i] = y
            zs[i] = sin(th) * r
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else minOf(now - last, 50L)
        last = now
        smooth += (energy - smooth) * 0.08f
        angle += dt * (0.00035f + 0.0014f * smooth)
        val cx = width / 2f
        val cy = height / 2f
        val rad = minOf(width, height) / 2f * 0.86f * (1f + 0.05f * smooth * sin(now / 110f))
        val ca = cos(angle)
        val sa = sin(angle)
        val dotScale = (minOf(width, height) / (240f * d)).coerceAtLeast(0.5f)
        for (i in 0 until n) {
            val x = xs[i] * ca + zs[i] * sa
            val z = -xs[i] * sa + zs[i] * ca
            val depth = (z + 1f) / 2f
            paint.color = Color.argb((50 + 190 * depth).toInt(), 185, 185, 225)
            c.drawCircle(cx + x * rad, cy + ys[i] * rad, (0.6f + 2.0f * depth) * d * dotScale, paint)
        }
        postInvalidateOnAnimation()
    }
}

/** Four little bars that move while Jarvis is listening. */
class BarsView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    @Volatile var active = false

    init {
        paint.color = Color.parseColor("#8E8DD8")
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        paint.strokeWidth = 3f * d
        val gap = width / 5f
        for (i in 0 until 4) {
            val a = if (active) 0.35f + 0.65f * (0.5f + 0.5f * sin(now / 130f + i * 1.7f)) else 0.18f
            val h = height * a
            val x = gap * (i + 1)
            c.drawLine(x, (height - h) / 2f, x, (height + h) / 2f, paint)
        }
        postInvalidateOnAnimation()
    }
}

/** Simple line icons drawn in code (no image files needed). */
class IconView(ctx: Context, private val kind: Int, tint: Int = Color.parseColor("#C9C9D6")) : View(ctx) {
    companion object {
        const val CLOCK = 0
        const val GEAR = 1
        const val MIC = 2
        const val X = 3
        const val PLUS = 4
        const val KEYBOARD = 5
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = tint
        p.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.27f
        when (kind) {
            CLOCK -> {
                c.drawCircle(cx, cy, r, p)
                c.drawLine(cx, cy, cx, cy - r * 0.6f, p)
                c.drawLine(cx, cy, cx + r * 0.5f, cy + r * 0.2f, p)
            }
            GEAR -> {
                c.drawCircle(cx, cy, r * 0.8f, p)
                c.drawCircle(cx, cy, r * 0.3f, p)
                for (i in 0 until 8) {
                    val a = Math.PI / 4 * i
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    c.drawLine(cx + ca * r * 0.8f, cy + sa * r * 0.8f, cx + ca * r * 1.2f, cy + sa * r * 1.2f, p)
                }
            }
            MIC -> {
                c.drawRoundRect(RectF(cx - r * 0.35f, cy - r * 1.0f, cx + r * 0.35f, cy + r * 0.25f), r * 0.35f, r * 0.35f, p)
                c.drawArc(RectF(cx - r * 0.7f, cy - r * 0.6f, cx + r * 0.7f, cy + r * 0.6f), 0f, 180f, false, p)
                c.drawLine(cx, cy + r * 0.6f, cx, cy + r * 1.0f, p)
            }
            X -> {
                c.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, p)
                c.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, p)
            }
            PLUS -> {
                c.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, p)
                c.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, p)
            }
            KEYBOARD -> {
                c.drawRoundRect(RectF(cx - r * 1.1f, cy - r * 0.6f, cx + r * 1.1f, cy + r * 0.6f), r * 0.2f, r * 0.2f, p)
                for (i in 0 until 4) c.drawPoint(cx - r * 0.6f + i * r * 0.4f, cy - r * 0.2f, p)
                c.drawLine(cx - r * 0.5f, cy + r * 0.25f, cx + r * 0.5f, cy + r * 0.25f, p)
            }
        }
    }
}
