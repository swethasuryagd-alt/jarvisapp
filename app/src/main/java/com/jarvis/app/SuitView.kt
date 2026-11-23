package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A nano-tech Iron Man suit made of thousands of glowing particles.
 * It assembles itself when the app opens, can be turned in every direction
 * (finger drag or hand gestures), opens its faceplate and lifts its arms when Jarvis listens or speaks.
 */
class SuitView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val maxPts = 4600
    private val bx = FloatArray(maxPts)
    private val by = FloatArray(maxPts)
    private val bz = FloatArray(maxPts)
    private val scx = FloatArray(maxPts)
    private val scy = FloatArray(maxPts)
    private val scz = FloatArray(maxPts)
    private val ph = FloatArray(maxPts)
    private val grp = IntArray(maxPts)   // 0 red armour, 1 gold armour, 2 cyan light, 3 white light
    private val part = IntArray(maxPts)  // 0 body, 1 faceplate, 2 helmet, 3 left arm, 4 right arm
    private var cnt = 0
    private val rnd = java.util.Random(11)

    @Volatile var energy = 0f
    @Volatile private var yaw = -25f
    @Volatile private var pitch = 6f
    @Volatile private var lastTouch = 0L
    private var assemble = 0f
    private var faceOpen = 0f
    private var arms = 0f
    private var smooth = 0f
    private var last = 0L
    private var faceManual = false
    private var explodeOn = false
    private var explode = 0f
    var onDouble: (() -> Unit)? = null

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { faceManual = !faceManual; return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onDouble?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { explodeOn = !explodeOn }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            rotateBy(-dx * 0.5f, -dy * 0.35f)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return true
    }

    private val pts = Array(12) { FloatArray(maxPts * 2) }
    private val counts = IntArray(12)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val colors = intArrayOf(
        Color.parseColor("#E53935"), Color.parseColor("#FFC107"),
        Color.parseColor("#22D3EE"), Color.parseColor("#F5FDFF")
    )
    private val alphas = intArrayOf(80, 165, 240)

    init {
        paint.strokeCap = Paint.Cap.ROUND
        basePaint.style = Paint.Style.STROKE
        basePaint.color = Color.parseColor("#22D3EE")
        buildSuit()
    }

    // ------------------------------------------------------------------ model
    private fun add(x: Float, y: Float, z: Float, g: Int, p: Int) {
        if (cnt >= maxPts) return
        bx[cnt] = x; by[cnt] = y; bz[cnt] = z
        val a = rnd.nextFloat() * 6.2831855f
        val u = rnd.nextFloat() * 2f - 1f
        val s = sqrt(1f - u * u)
        val r = 1.2f + rnd.nextFloat() * 1.6f
        scx[cnt] = cos(a) * s * r
        scy[cnt] = u * r
        scz[cnt] = sin(a) * s * r
        ph[cnt] = rnd.nextFloat()
        grp[cnt] = g
        part[cnt] = p
        cnt++
    }

    private fun ellipsoid(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float,
                          n: Int, g: Int, p: Int, faceSplit: Boolean = false) {
        var made = 0
        while (made < n) {
            val x = rnd.nextGaussian().toFloat()
            val y = rnd.nextGaussian().toFloat()
            val z = rnd.nextGaussian().toFloat()
            val len = sqrt(x * x + y * y + z * z)
            if (len < 1e-4f) continue
            val px = cx + x / len * rx
            val py = cy + y / len * ry
            val pz = cz + z / len * rz
            if (faceSplit) {
                if (pz > 0.035f) add(px, py, pz, 1, 1) else add(px, py, pz, 0, 2)
            } else {
                add(px, py, pz, g, p)
            }
            made++
        }
    }

    private fun limb(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
                     r: Float, n: Int, g: Int, p: Int) {
        val ax = x2 - x1
        val ay = y2 - y1
        val az = z2 - z1
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-4f) return
        val ux = ax / len
        val uy = ay / len
        val uz = az / len
        var made = 0
        while (made < n) {
            val t = rnd.nextFloat()
            var gx = rnd.nextGaussian().toFloat()
            var gy = rnd.nextGaussian().toFloat()
            var gz = rnd.nextGaussian().toFloat()
            val dot = gx * ux + gy * uy + gz * uz
            gx -= dot * ux
            gy -= dot * uy
            gz -= dot * uz
            val gl = sqrt(gx * gx + gy * gy + gz * gz)
            if (gl < 1e-4f) continue
            add(x1 + ax * t + gx / gl * r, y1 + ay * t + gy / gl * r, z1 + az * t + gz / gl * r, g, p)
            made++
        }
    }

    private fun ringY(y: Float, rx: Float, rz: Float, n: Int, g: Int, p: Int) {
        for (i in 0 until n) {
            val a = i.toFloat() / n * 6.2831855f
            add(cos(a) * rx, y, sin(a) * rz, g, p)
        }
    }

    private fun ringXY(cx: Float, cy: Float, z: Float, r: Float, n: Int, g: Int, p: Int) {
        for (i in 0 until n) {
            val a = i.toFloat() / n * 6.2831855f
            add(cx + cos(a) * r, cy + sin(a) * r, z, g, p)
        }
    }

    private fun buildSuit() {
        // helmet (red) with a gold faceplate that can lift, and glowing eyes
        ellipsoid(0f, 0.84f, 0f, 0.115f, 0.14f, 0.125f, 560, 0, 2, true)
        ellipsoid(-0.048f, 0.855f, 0.118f, 0.024f, 0.008f, 0.01f, 14, 3, 1)
        ellipsoid(0.048f, 0.855f, 0.118f, 0.024f, 0.008f, 0.01f, 14, 3, 1)
        // neck, torso, waist, pelvis
        limb(0f, 0.71f, 0f, 0f, 0.66f, 0f, 0.05f, 50, 0, 0)
        ellipsoid(0f, 0.50f, 0f, 0.22f, 0.20f, 0.12f, 560, 0, 0)
        ellipsoid(0f, 0.26f, 0f, 0.16f, 0.12f, 0.10f, 230, 0, 0)
        ellipsoid(0f, 0.09f, 0f, 0.19f, 0.09f, 0.11f, 230, 1, 0)
        // arms and legs
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            val p = if (side < 0) 3 else 4
            ellipsoid(s * 0.27f, 0.62f, 0f, 0.09f, 0.085f, 0.085f, 130, 1, p)
            limb(s * 0.27f, 0.62f, 0f, s * 0.34f, 0.38f, 0f, 0.062f, 150, 0, p)
            limb(s * 0.34f, 0.38f, 0f, s * 0.38f, 0.15f, 0f, 0.052f, 140, 0, p)
            ellipsoid(s * 0.39f, 0.10f, 0.01f, 0.045f, 0.055f, 0.04f, 80, 1, p)
            ringXY(s * 0.39f, 0.09f, 0.05f, 0.03f, 22, 2, p)
            limb(s * 0.10f, 0.02f, 0f, s * 0.12f, -0.36f, 0.01f, 0.085f, 220, 0, 0)
            limb(s * 0.12f, -0.36f, 0.01f, s * 0.12f, -0.80f, 0f, 0.065f, 180, 0, 0)
            ellipsoid(s * 0.12f, -0.36f, 0.05f, 0.06f, 0.05f, 0.04f, 50, 1, 0)
            ellipsoid(s * 0.12f, -0.90f, 0.04f, 0.07f, 0.06f, 0.12f, 100, 1, 0)
        }
        // arc reactor
        ringXY(0f, 0.53f, 0.118f, 0.05f, 64, 2, 0)
        ringXY(0f, 0.53f, 0.12f, 0.03f, 36, 3, 0)
        ellipsoid(0f, 0.53f, 0.12f, 0.012f, 0.012f, 0.006f, 10, 3, 0)
        // gold belts
        ringY(0.33f, 0.165f, 0.10f, 70, 1, 0)
        ringY(0.64f, 0.215f, 0.115f, 70, 1, 0)
    }

    // ------------------------------------------------------------------ controls
    fun rotateBy(dYaw: Float, dPitch: Float) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-80f, 80f)
        lastTouch = SystemClock.uptimeMillis()
    }

    fun reassemble() { assemble = 0f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        glowPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(190, 120, 240, 255), Color.argb(0, 34, 211, 238)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else minOf(now - last, 50L)
        last = now
        smooth += (energy - smooth) * 0.08f
        if (assemble < 1f) assemble = minOf(1f, assemble + dt / 2400f)
        faceOpen += ((if (faceManual || smooth > 0.3f) 1f else 0f) - faceOpen) * 0.07f
        explode += ((if (explodeOn) 1f else 0f) - explode) * 0.1f
        arms += ((if (smooth > 0.3f) 1f else 0f) - arms) * 0.05f
        if (now - lastTouch > 4000L) {
            yaw += dt * 0.008f
            pitch += (4f - pitch) * 0.01f
        }
        if (yaw > 360f) yaw -= 360f
        if (yaw < -360f) yaw += 360f

        val w = width.toFloat()
        val h = height.toFloat()
        val s = h / 2.2f
        val cx = w / 2f
        val cy = h / 2f - 0.02f * s
        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val pr = Math.toRadians(pitch.toDouble()).toFloat()
        val cyw = cos(yr)
        val syw = sin(yr)
        val cpt = cos(pr)
        val spt = sin(pr)
        val headYaw = 0.45f * smooth * sin(now / 700f)
        val hc = cos(headYaw)
        val hs = sin(headYaw)
        val lift = 0.5f * arms
        val sway = 0.05f * sin(now / 900f)
        val pulse = (0.5f + 0.5f * sin(now / 300f)) * (0.4f + 0.6f * smooth)

        java.util.Arrays.fill(counts, 0)
        for (i in 0 until cnt) {
            var x = bx[i]
            var y = by[i]
            var z = bz[i]
            when (part[i]) {
                1, 2 -> {
                    if (part[i] == 1) {
                        y += 0.16f * faceOpen
                        z -= 0.015f * faceOpen
                    }
                    val nx = x * hc + z * hs
                    val nz = -x * hs + z * hc
                    x = nx
                    z = nz
                }
                3, 4 -> {
                    val side = if (part[i] == 4) 1f else -1f
                    val th = side * (lift + sway)
                    val px = x - side * 0.27f
                    val py = y - 0.62f
                    val ct = cos(th)
                    val st = sin(th)
                    x = side * 0.27f + (px * ct - py * st)
                    y = 0.62f + (px * st + py * ct)
                }
            }
            if (explode > 0.01f && part[i] != 0) {
                val ex = x
                val ey = y - 0.35f
                val ez = z
                val el = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(0.05f)
                val kk = 0.45f * explode
                x += ex / el * kk
                y += ey / el * kk
                z += ez / el * kk
            }
            val t = ((assemble - ph[i] * 0.45f) / 0.55f).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            if (e < 1f) {
                x = scx[i] + (x - scx[i]) * e
                y = scy[i] + (y - scy[i]) * e
                z = scz[i] + (z - scz[i]) * e
            }
            val x1 = x * cyw + z * syw
            val z1 = -x * syw + z * cyw
            val y2 = y * cpt - z1 * spt
            val z2 = y * spt + z1 * cpt
            val persp = 1f / (1f - z2 * 0.22f)
            val px = cx + x1 * s * persp
            val py = cy - y2 * s * persp
            val bin = if (z2 > 0.10f) 2 else if (z2 > -0.10f) 1 else 0
            val b = grp[i] * 3 + bin
            val k = counts[b]
            pts[b][k] = px
            pts[b][k + 1] = py
            counts[b] = k + 2
        }

        // hologram base under the feet
        val fy = cy + 0.97f * s
        basePaint.strokeWidth = 1.2f * d
        basePaint.alpha = 110
        oval.set(cx - 0.50f * s, fy - 0.07f * s, cx + 0.50f * s, fy + 0.07f * s)
        c.drawOval(oval, basePaint)
        basePaint.alpha = 60
        oval.set(cx - 0.36f * s, fy - 0.05f * s, cx + 0.36f * s, fy + 0.05f * s)
        c.drawOval(oval, basePaint)

        // particles
        val sc = (s / (200f * d)).coerceIn(0.5f, 1.6f)
        for (g in 0 until 4) {
            for (bin in 0 until 3) {
                val b = g * 3 + bin
                if (counts[b] == 0) continue
                paint.color = colors[g]
                paint.alpha = alphas[bin]
                val grow = if (g == 2) 1f + 0.5f * pulse else 1f
                paint.strokeWidth = (0.9f + bin * 0.8f) * d * sc * grow
                c.drawPoints(pts[b], 0, counts[b], paint)
            }
        }

        // arc reactor glow on the chest
        if (assemble > 0.8f) {
            val x1 = 0f * cyw + 0.118f * syw
            val z1 = -0f * syw + 0.118f * cyw
            val y2 = 0.53f * cpt - z1 * spt
            val z2 = 0.53f * spt + z1 * cpt
            if (z2 > -0.02f) {
                val persp = 1f / (1f - z2 * 0.22f)
                val gx = cx + x1 * s * persp
                val gy = cy - y2 * s * persp
                val gr = s * (0.075f + 0.04f * pulse)
                c.save()
                c.translate(gx, gy)
                c.scale(gr, gr)
                c.drawCircle(0f, 0f, 1f, glowPaint)
                c.restore()
            }
        }
        postInvalidateOnAnimation()
    }
}
