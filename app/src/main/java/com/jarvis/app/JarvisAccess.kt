package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    fun swipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val p = Path()
        p.moveTo(x, dm.heightPixels * 0.75f)
        p.lineTo(x, dm.heightPixels * 0.25f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        dispatchGesture(g, null, null)
    }

    private fun findNode(node: AccessibilityNodeInfo?, labels: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (labels.any { t.contains(it) || d.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val r = findNode(node.getChild(i), labels)
            if (r != null) return r
        }
        return null
    }

    /** Finds a button on screen by its label (for example "speaker") and taps it if it is not already in the wanted state. */
    fun toggleByLabel(labels: List<String>, wantOn: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findNode(root, labels) ?: return false
        val isOn = n.isChecked || n.isSelected
        if (isOn == wantOn) return true
        var p: AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return (p ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
