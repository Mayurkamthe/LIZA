package com.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {
    companion object { var instance: JarvisAccessibilityService? = null }

    // Snapshot of the last /nodes listing, keyed by the id handed to the
    // caller, so tap_node/type_node can act on the real node object instead
    // of a guessed pixel coordinate. Only valid until the screen next
    // changes -- callers should re-fetch /nodes before acting if in doubt.
    private val lastNodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 500) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(g, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Traverses the active window and concatenates all visible text and
     * content descriptions -- used to let the assistant answer "what's on
     * my screen" or summarize what's currently open. */
    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text
        if (!text.isNullOrBlank()) sb.append(text).append("\n")
        val desc = node.contentDescription
        if (!desc.isNullOrBlank()) sb.append(desc).append("\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
        }
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    /** Lists every clickable, editable, or text-bearing node on the current
     * screen with a stable-for-this-snapshot integer id, so a caller (the
     * LLM) can say "tap element 7" instead of guessing coordinates. This is
     * what makes app-agnostic control feasible: it works the same way in
     * any app, since it reads the accessibility tree rather than fixed
     * positions. */
    fun getInteractiveNodesJson(): JSONArray {
        lastNodes.clear()
        val root = rootInActiveWindow ?: return JSONArray()
        val arr = JSONArray()
        var counter = 0

        fun walk(n: AccessibilityNodeInfo) {
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (n.isClickable || n.isEditable || text.isNotBlank() || desc.isNotBlank()) {
                val bounds = Rect()
                n.getBoundsInScreen(bounds)
                arr.put(
                    JSONObject()
                        .put("id", counter)
                        .put("text", text)
                        .put("desc", desc)
                        .put("clickable", n.isClickable)
                        .put("editable", n.isEditable)
                        .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                )
                lastNodes[counter] = n
                counter++
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return arr
    }

    fun tapNode(id: Int): Boolean {
        val node = lastNodes[id] ?: return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        return true
    }

    fun typeIntoNode(id: Int, text: String): Boolean {
        val node = lastNodes[id] ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Finds the first node whose text or content-description matches
     * (case-insensitively, substring ok) against the current on-screen
     * nodes -- used for things like auto-tapping a "Send" button after
     * pre-filling a message via deep link. Refreshes the node snapshot. */
    fun tapNodeMatching(label: String): Boolean {
        getInteractiveNodesJson()
        val target = lastNodes.entries.firstOrNull { (_, n) ->
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            text.equals(label, ignoreCase = true) || desc.equals(label, ignoreCase = true)
        }
        return target?.let { tapNode(it.key) } ?: false
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
