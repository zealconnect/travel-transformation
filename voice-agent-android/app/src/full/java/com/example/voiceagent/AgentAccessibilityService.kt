package com.example.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * FULL BUILD ONLY. Lets the agent see and operate other apps (tap Send, flip switches,
 * use Share buttons, step-by-step AI control). Android only allows this through an
 * Accessibility Service, which Play Protect blocks for apps installed from a browser,
 * so the default ("lite") build leaves it out.
 */
class AgentAccessibilityService : AccessibilityService(), Screen {

    companion object {
        private const val MAX_SCREEN_CHARS = 12000
    }

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        Screen.current = this
        AgentService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (Screen.current === this) Screen.current = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reading the screen

    /** Text description of everything visible, with a number for each element. */
    override fun dumpScreen(maxChars: Int): String {
        screenLimit = maxChars
        nodes.clear()
        val sb = StringBuilder()
        val counter = intArrayOf(1)
        val roots = windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        val dm = resources.displayMetrics
        sb.append("Screen ${dm.widthPixels}x${dm.heightPixels}. Foreground app: ")
            .append(rootInActiveWindow?.packageName ?: "unknown").append('\n')
        for (root in roots) {
            if (root.packageName == packageName) continue   // our own 🎤 button
            sb.append("== window: ").append(root.packageName).append('\n')
            walk(root, sb, 0, counter)
        }
        if (sb.length >= maxChars) sb.append("\n…(screen truncated; scroll or use screenshot)")
        return sb.toString()
    }

    private var screenLimit = MAX_SCREEN_CHARS

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, counter: IntArray) {
        if (sb.length >= screenLimit || !n.isVisibleToUser) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isEditable ||
            n.isClickable || n.isScrollable || n.isCheckable
        if (interesting) {
            val id = counter[0]++
            nodes[id] = n
            val r = Rect().also { n.getBoundsInScreen(it) }
            val flags = buildList {
                if (n.isClickable) add("tap")
                if (n.isLongClickable) add("longpress")
                if (n.isEditable) add("edit")
                if (n.isScrollable) add("scroll")
                if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (n.isPassword) add("PASSWORD")
                if (n.isFocused) add("focused")
                if (!n.isEnabled) add("disabled")
            }
            sb.append(" ".repeat(min(depth, 6)))
                .append('[').append(id).append("] ")
                .append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(80)).append('"')
            if (hint.isNotEmpty()) sb.append(" hint=\"").append(hint.take(60)).append('"')
            n.viewIdResourceName?.substringAfter(":id/")?.let { sb.append(" id=").append(it) }
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, sb, depth + 1, counter) }
    }

    /** Words that describe an element, used by the safety check before tapping. */
    override fun labelOf(id: Int): String {
        val n = nodes[id] ?: return ""
        val parts = mutableListOf<String>()
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops < 3) {
            c.text?.let { parts += it.toString() }
            c.contentDescription?.let { parts += it.toString() }
            c.viewIdResourceName?.let { parts += it.substringAfter(":id/").replace('_', ' ') }
            if (c.isClickable) break
            c = c.parent; hops++
        }
        return parts.joinToString(" | ")
    }

    /** Smallest known element containing a screen point. */
    fun elementAt(x: Int, y: Int): Int? = nodes.entries
        .map { (id, n) -> id to Rect().also { n.getBoundsInScreen(it) } }
        .filter { it.second.contains(x, y) }
        .minByOrNull { it.second.width().toLong() * it.second.height() }
        ?.first

    fun isSearchField(id: Int): Boolean {
        val n = nodes[id] ?: return false
        val s = listOfNotNull(n.text, n.hintText, n.contentDescription, n.viewIdResourceName)
            .joinToString(" ").lowercase()
        return listOf("search", "url", "address", "find", "खोज").any { it in s }
    }

    // ---------------------------------------------------------------- actions

    override suspend fun tap(id: Int): String {
        val n = nodes[id] ?: return "No element [$id]. Call read_screen first."
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$id]."
        val r = Rect().also { n.getBoundsInScreen(it) }
        return if (tapAt(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped [$id]." else "Tap on [$id] failed."
    }

    suspend fun longPress(id: Int): String {
        val n = nodes[id] ?: return "No element [$id]."
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Long-pressed [$id]."
        val r = Rect().also { n.getBoundsInScreen(it) }
        val p = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        return if (gesture(p, 900)) "Long-pressed [$id]." else "Long-press failed."
    }

    suspend fun tapAt(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 60)

    override fun typeText(id: Int, text: String): String {
        var n = nodes[id] ?: return "No element [$id]. Call read_screen first."
        if (n.isPassword) return "REFUSED: that is a password field. Ask the user to type it themselves."
        if (!n.isEditable) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "Element [$id] is not a text field."
            if (n.isPassword) return "REFUSED: that is a password field. Ask the user to type it themselves."
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed text into [$id]."
        else "Could not type into [$id]."
    }

    override fun pressEnter(id: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Enter key needs Android 11+; tap the search/go button instead."
        val n = nodes[id] ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "No field to submit."
        return if (n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) "Pressed enter." else "Enter key not supported here."
    }

    override suspend fun scroll(direction: String, id: Int?): String {
        val target = id?.let { nodes[it] }?.takeIf { it.isScrollable }
            ?: nodes.values.filter { it.isScrollable }
                .maxByOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.let { it.width().toLong() * it.height() } }
        if (target != null) {
            val action = when (direction) {
                "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                "up" -> AccessibilityAction.ACTION_SCROLL_UP
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            if (target.performAction(action.id)) return "Scrolled $direction."
            val fallback = if (direction == "down" || direction == "right")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(fallback)) return "Scrolled $direction."
        }
        // Fall back to a swipe gesture.
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val p = Path()
        when (direction) {
            "down" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.30f) }
            "up" -> { p.moveTo(w / 2, h * 0.30f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.25f, h / 2); p.lineTo(w * 0.80f, h / 2) }
            else -> { p.moveTo(w * 0.80f, h / 2); p.lineTo(w * 0.25f, h / 2) }
        }
        return if (gesture(p, 350)) "Swiped to scroll $direction." else "Scroll failed."
    }

    override fun press(button: String): String {
        val action = when (button) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return "Unknown button '$button'."
        }
        return if (performGlobalAction(action)) "Pressed $button." else "Could not press $button."
    }

    /** Screenshot as a base64 JPEG 720px wide, or null if not possible (Android < 11 or a protected screen). */
    suspend fun screenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (bmp == null) { if (cont.isActive) cont.resume(null); return }
                    val small = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * 720f / bmp.width).toInt(), true)
                    if (small !== bmp) bmp.recycle()
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    small.recycle()
                    if (cont.isActive) cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
    }

    // ---------------------------------------------------------------- helpers for scripted steps

    private fun roots(): List<AccessibilityNodeInfo> = windows
        .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        .sortedByDescending { it.layer }
        .mapNotNull { it.root }
        .ifEmpty { listOfNotNull(rootInActiveWindow) }

    /** First visible element (top window first) that matches. */
    override fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < 3000) {
                val n = queue.removeFirst()
                seen++
                if (!n.isVisibleToUser) continue
                if (n.packageName != packageName && match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Keep looking for an element until it appears or time runs out. */
    override suspend fun waitForNode(timeoutMs: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            findNode(match)?.let { return it }
            if (System.currentTimeMillis() > end) return null
            delay(250)
        }
    }

    /** Tap an element (its clickable parent, or its centre as a fallback). */
    override suspend fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    override fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** All readable text on screen, for "read the screen". */
    override fun visibleText(limit: Int): String {
        val parts = LinkedHashSet<String>()
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && parts.sumOf { it.length } < limit) {
                val n = queue.removeFirst()
                if (!n.isVisibleToUser || n.packageName == packageName) continue
                n.text?.toString()?.trim()?.takeIf { it.length > 1 && !n.isPassword }?.let { parts += it }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return parts.joinToString(". ").take(limit)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
}
