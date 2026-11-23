package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Arc-reactor / HUD style orb. energy 0..1 = faster rotation, brighter pulse and ripples. */
class ReactorView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val tri = Path()
    private val cyan = Color.parseColor("#22D3EE")
    private val ice = Color.parseColor("#C8F7FF")
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var a4 = 0f
    private var last = 0L
    private var smooth = 0f
    @Volatile var energy = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = minOf(w, h) / 2f * 0.94f * 0.3f
        fill.shader = RadialGradient(
            w / 2f, h / 2f, maxOf(r, 1f),
            intArrayOf(Color.argb(255, 235, 253, 255), Color.argb(200, 34, 211, 238), Color.argb(0, 34, 211, 238)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float,
                     w: Float, a: Int, col: Int, g: Float = 3f) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        paint.style = Paint.Style.STROKE
        paint.color = col
        paint.strokeWidth = w * g
        paint.alpha = a / 5
        c.drawArc(oval, start, sweep, false, paint)
        paint.strokeWidth = w
        paint.alpha = a
        c.drawArc(oval, start, sweep, false, paint)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else minOf(now - last, 50L)
        last = now
        smooth += (energy - smooth) * 0.08f
        val sp = 1f + 2.2f * smooth
        a1 += dt * 0.012f * sp
        a2 -= dt * 0.02f * sp
        a3 += dt * 0.035f * sp
        a4 -= dt * 0.006f * sp

        val cx = width / 2f
        val cy = height / 2f
        val rr = minOf(width, height) / 2f * 0.94f
        val pulse = 1f + 0.07f * smooth * sin(now / 140f)

        // outer ring
        ring(c, cx, cy, rr * 0.98f, 0f, 360f, 1.2f * d, 150, cyan)

        // tick marks
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = cyan
        for (i in 0 until 72) {
            val ang = Math.toRadians((i * 5 + a1).toDouble())
            val isLong = i % 6 == 0
            val r1 = rr * 0.90f
            val r2 = rr * (if (isLong) 0.80f else 0.85f)
            paint.alpha = if (isLong) 230 else 120
            c.drawLine(
                cx + (cos(ang) * r1).toFloat(), cy + (sin(ang) * r1).toFloat(),
                cx + (cos(ang) * r2).toFloat(), cy + (sin(ang) * r2).toFloat(), paint
            )
        }

        // three sweeping arcs
        for (k in 0 until 3) ring(c, cx, cy, rr * 0.72f, a2 + k * 120f, 80f, 3f * d, 230, cyan)
        // thin inner ring
        ring(c, cx, cy, rr * 0.62f, 0f, 360f, 1f * d, 110, cyan)
        // reactor coil segments
        for (k in 0 until 10) ring(c, cx, cy, rr * 0.50f, a4 + k * 36f, 26f, rr * 0.09f, 190, cyan, 1.5f)
        // bright counter-rotating arcs
        ring(c, cx, cy, rr * 0.38f, a3, 100f, 2f * d, 255, ice)
        ring(c, cx, cy, rr * 0.38f, a3 + 180f, 40f, 2f * d, 255, ice)
        // inner ring
        ring(c, cx, cy, rr * 0.28f * pulse, 0f, 360f, 2f * d, 200, ice)

        // rotating triangle
        tri.reset()
        for (k in 0 until 3) {
            val ang = Math.toRadians((a4 * 3f + k * 120f - 90f).toDouble())
            val px = cx + (cos(ang) * rr * 0.2f).toFloat()
            val py = cy + (sin(ang) * rr * 0.2f).toFloat()
            if (k == 0) tri.moveTo(px, py) else tri.lineTo(px, py)
        }
        tri.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = ice
        paint.alpha = 220
        c.drawPath(tri, paint)

        // glowing core
        c.save()
        c.scale(pulse, pulse, cx, cy)
        c.drawCircle(cx, cy, rr * 0.3f, fill)
        c.restore()

        // ripple while listening / speaking
        if (smooth > 0.05f) {
            val t = (now % 1600L) / 1600f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = cyan
            paint.alpha = ((1f - t) * 140f * smooth).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, rr * (0.35f + 0.65f * t), paint)
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
        paint.color = Color.parseColor("#22D3EE")
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
class IconView(ctx: Context, private val kind: Int, tint: Int = Color.parseColor("#A8EAF7")) : View(ctx) {
    companion object {
        const val CLOCK = 0
        const val GEAR = 1
        const val MIC = 2
        const val X = 3
        const val PLUS = 4
        const val KEYBOARD = 5
        const val CAMERA = 6
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
            CAMERA -> {
                c.drawRoundRect(RectF(cx - r * 1.0f, cy - r * 0.55f, cx + r * 1.0f, cy + r * 0.7f), r * 0.2f, r * 0.2f, p)
                c.drawCircle(cx, cy + r * 0.07f, r * 0.38f, p)
                c.drawLine(cx - r * 0.35f, cy - r * 0.55f, cx - r * 0.2f, cy - r * 0.85f, p)
                c.drawLine(cx - r * 0.2f, cy - r * 0.85f, cx + r * 0.2f, cy - r * 0.85f, p)
                c.drawLine(cx + r * 0.2f, cy - r * 0.85f, cx + r * 0.35f, cy - r * 0.55f, p)
            }
        }
    }
}
