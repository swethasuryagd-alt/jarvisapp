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
