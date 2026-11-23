package com.jarvis.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

/**
 * Pinch thumb and index finger together, then move your hand: the suit turns the same way
 * (right, left, up, down). The front camera is only on while this is running.
 */
class HandTracker(
    private val activity: ComponentActivity,
    private val onRotate: (Float, Float) -> Unit,
    private val onState: (String) -> Unit
) {
    @Volatile var running = false
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    fun start() {
        if (running) return
        running = true
        try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> handle(result) }
                .setErrorListener { e -> onState("Hand tracking error: ${e.message}") }
                .build()
            landmarker = HandLandmarker.createFromOptions(activity, opts)
        } catch (e: Throwable) {
            onState("Hand tracking could not start: ${e.message}")
            running = false
            return
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                onState("Hand control on: pinch thumb and index finger, then move. Double-tap the suit to turn it off.")
            } catch (e: Throwable) {
                onState("Camera could not start: ${e.message}")
                stop()
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        running = false
        try { provider?.unbindAll() } catch (_: Throwable) {}
        try { landmarker?.close() } catch (_: Throwable) {}
        landmarker = null
        lastX = Float.NaN
        lastY = Float.NaN
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker
            if (lm == null || !running) return
            val bmp = proxy.toBitmap()
            val m = Matrix()
            m.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            m.postScale(-1f, 1f)
            val img = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            lm.detectAsync(BitmapImageBuilder(img).build(), SystemClock.uptimeMillis())
        } catch (_: Throwable) {
        } finally {
            proxy.close()
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
        Math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private fun handle(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            lastX = Float.NaN
            return
        }
        val lm = hands[0]
        val thumb = lm[4]
        val index = lm[8]
        val wrist = lm[0]
        val mid = lm[9]
        val size = dist(wrist.x(), wrist.y(), mid.x(), mid.y()).coerceAtLeast(0.01f)
        val pinch = dist(thumb.x(), thumb.y(), index.x(), index.y()) < 0.40f * size
        val x = (thumb.x() + index.x()) / 2f
        val y = (thumb.y() + index.y()) / 2f
        if (pinch) {
            if (!lastX.isNaN()) onRotate((x - lastX) * 420f, (y - lastY) * 300f)
            lastX = x
            lastY = y
        } else {
            lastX = Float.NaN
        }
    }
}
