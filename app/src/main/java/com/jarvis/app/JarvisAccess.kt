package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class JarvisAccess : AccessibilityService() {

    companion object {
        @Volatile var inst: JarvisAccess? = null
        @Volatile var scrolling = false
        fun stopScroll() { scrolling = false }
    }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null; scrolling = false
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val p = Path()
        p.moveTo(x, dm.heightPixels * 0.75f)
        p.lineTo(x, dm.heightPixels * 0.25f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        dispatchGesture(g, null, null)
    }

    /** Swipes up every gapSec seconds. If pkg is set, stops when that app leaves the screen. */
    fun startScroll(gapSec: Int, pkg: String?) {
        scrolling = true
        Thread {
            var misses = 0
            try { Thread.sleep(if (pkg != null) 3500 else 800) } catch (_: Exception) {}
            while (scrolling && inst != null) {
                if (pkg != null) {
                    val cur = rootInActiveWindow?.packageName?.toString()
                    if (cur != null && cur != pkg) misses++ else misses = 0
                    if (misses >= 2) { scrolling = false; break }
                }
                swipeUp()
                var waited = 0
                while (scrolling && waited < gapSec * 1000) { Thread.sleep(200); waited += 200 }
            }
        }.start()
    }
}
