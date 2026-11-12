package com.example.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

/**
 * The part of the app that can see and operate the phone. Android only lets an
 * Accessibility Service do this, and only after you switch it on in Settings.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
        private const val MAX_SCREEN_CHARS = 12000

        /** Spoken Marathi/English names for common apps. */
        private val APP_ALIASES = mapOf(
            "व्हॉट्सॲप" to "whatsapp", "whatsapp" to "whatsapp", "यूट्यूब" to "youtube", "युट्युब" to "youtube",
            "कॅमेरा" to "camera", "कॅमेरा" to "camera", "गॅलरी" to "photos", "gallery" to "photos", "फोटो" to "photos",
            "सेटिंग्ज" to "settings", "सेटिंग" to "settings", "setting" to "settings", "क्रोम" to "chrome",
            "नकाशा" to "maps", "मॅप" to "maps", "मॅप्स" to "maps", "google maps" to "maps", "कॅल्क्युलेटर" to "calculator",
            "घड्याळ" to "clock", "संपर्क" to "contacts", "कॉन्टॅक्ट्स" to "contacts", "इन्स्टाग्राम" to "instagram",
            "फेसबुक" to "facebook", "जीमेल" to "gmail", "ईमेल" to "gmail", "email" to "gmail", "mail" to "gmail",
            "फोन" to "phone", "dialer" to "phone", "संदेश" to "messages", "मेसेजेस" to "messages", "sms" to "messages",
            "प्ले स्टोअर" to "play store", "playstore" to "play store", "फाईल्स" to "files", "file manager" to "files",
            "कॅलेंडर" to "calendar", "स्पॉटिफाय" to "spotify", "फोन पे" to "phonepe", "फोनपे" to "phonepe",
            "पेटीएम" to "paytm", "गूगल पे" to "gpay", "google pay" to "gpay", "जीपे" to "gpay"
        )
    }

    enum class State { IDLE, LISTENING, WORKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    lateinit var voice: Voice
        private set
    private var bubble: TextView? = null
    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        voice = Voice(this) { Prefs.lang(this) }
        voice.init()
        showBubble()
        setState(State.IDLE)
        AgentTileService.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        instance = null
        stop()
        removeBubble()
        if (::voice.isInitialized) voice.shutdown()
        LocalBrain.close()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sessions

    /** Bubble tap: start listening for a command, or stop the current one. */
    fun toggle() {
        if (job?.isActive == true) { stop(); return }
        if (!Prefs.enabled(this)) { sayDisabled(); return }
        job = scope.launch { runSession(null) }
    }

    /** Run a typed command (handy for testing from the app screen). */
    fun runTyped(command: String) {
        stop()
        if (!Prefs.enabled(this)) { sayDisabled(); return }
        job = scope.launch { runSession(command) }
    }

    private fun sayDisabled() {
        android.widget.Toast.makeText(this, "Voice Agent is OFF. Double-tap the button to turn it on.", android.widget.Toast.LENGTH_SHORT).show()
        scope.launch { voice.speak("Voice Agent is off. Double tap the button to turn it on.") }
    }

    /** Called when the on/off switch changes anywhere (app, tile, bubble, voice). */
    fun onEnabledChanged(on: Boolean) {
        if (!on) stop()
        setState(State.IDLE)
    }

    fun stop() {
        job?.cancel()
        job = null
        if (::voice.isInitialized) voice.stopAll()
        setState(State.IDLE)
    }

    private suspend fun runSession(typed: String?) {
        try {
            val command = typed ?: run {
                setState(State.LISTENING)
                voice.listen()
            }
            if (command.isNullOrBlank()) {
                if (voice.lastError == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    voice.speak(tr("I can't use the microphone. Open the Voice Agent app and allow it.",
                        "मला मायक्रोफोन वापरता येत नाही. Voice Agent ॲप उघडून परवानगी द्या."))
                else voice.speak(tr("Sorry, I didn't catch that.", "माफ करा, मला समजलं नाही."))
                return
            }
            if (Regex("(?i)\\b(disable|turn off|switch off|deactivate|go to sleep)\\b.*\\b(yourself|agent|assistant|voice agent)\\b|^\\s*(disable|turn off)\\s*$|(स्वतःला|एजंट) बंद")
                    .containsMatchIn(command)) {
                voice.speak(tr("Okay, I'm off. Double tap the button when you want me back.",
                    "ठीक आहे, मी बंद होतोय. परत हवं असेल तेव्हा बटणावर दोनदा टॅप करा."))
                Prefs.setEnabled(this, false)
                return
            }
            setState(State.WORKING)
            Agent(this, voice).run(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            voice.speak(tr("Something went wrong. ", "काहीतरी चुकलं. ") + (e.message?.take(100) ?: ""))
        } finally {
            setState(State.IDLE)
        }
    }

    /** Ask a question out loud and listen for the answer. */
    suspend fun ask(question: String): String? {
        voice.speak(question)
        setState(State.LISTENING)
        try {
            return voice.listen()
        } finally {
            setState(State.WORKING)
        }
    }

    // ---------------------------------------------------------------- floating mic bubble

    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val view = TextView(this).apply {
            text = "🎤"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F51B5"))
            }
            elevation = 8 * dm.density
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - (12 * dm.density).toInt()
            y = dm.heightPixels / 3
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var downTime = 0L; var dragging = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pendingTap: Runnable? = null
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downTime = System.currentTimeMillis(); dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragging || abs(dx) > 12 || abs(dy) > 12) {
                        dragging = true
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    if (System.currentTimeMillis() - downTime > 600) {
                        // Long-press: open the settings screen.
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else if (pendingTap != null) {
                        // Double-tap: switch the agent on/off.
                        handler.removeCallbacks(pendingTap!!)
                        pendingTap = null
                        val on = !Prefs.enabled(this)
                        Prefs.setEnabled(this, on)
                        android.widget.Toast.makeText(this,
                            if (on) "Voice Agent ON" else "Voice Agent OFF", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch { voice.speak(if (on) "Voice Agent is on." else "Voice Agent is off.") }
                    } else {
                        // Single tap (waits briefly to rule out a double-tap).
                        val r = Runnable { pendingTap = null; toggle() }
                        pendingTap = r
                        handler.postDelayed(r, 300)
                    }
                }
            }
            true
        }
        wm.addView(view, lp)
        bubble = view
    }

    private fun removeBubble() {
        bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        bubble = null
    }

    fun setState(state: State) {
        scope.launch(Dispatchers.Main) {
            val b = bubble ?: return@launch
            val (label, color) = when (state) {
                State.IDLE -> if (Prefs.enabled(this@AgentAccessibilityService)) "🎤" to "#3F51B5" else "💤" to "#9E9E9E"
                State.LISTENING -> "👂" to "#E53935"
                State.WORKING -> "⏹" to "#FB8C00"   // tap to stop
            }
            b.text = label
            (b.background as GradientDrawable).setColor(Color.parseColor(color))
        }
    }

    // ---------------------------------------------------------------- reading the screen

    /** Text description of everything visible, with a number for each element. */
    fun dumpScreen(maxChars: Int = MAX_SCREEN_CHARS): String {
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
    fun labelOf(id: Int): String {
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

    suspend fun tap(id: Int): String {
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

    fun typeText(id: Int, text: String): String {
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

    fun pressEnter(id: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Enter key needs Android 11+; tap the search/go button instead."
        val n = nodes[id] ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "No field to submit."
        return if (n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) "Pressed enter." else "Enter key not supported here."
    }

    suspend fun scroll(direction: String, id: Int?): String {
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

    fun press(button: String): String {
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

    /** Open an installed app by its spoken name (English or Marathi). Returns (opened?, message). */
    fun openApp(name: String): Pair<Boolean, String> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it to it.loadLabel(pm).toString() }
        val raw = name.trim().lowercase()
        val q = APP_ALIASES[raw] ?: APP_ALIASES[raw.replace(Regex("\\s+"), "")] ?: raw
        val fq = Text.fold(q)
        val match = apps.firstOrNull { Text.fold(it.second) == fq }
            ?: apps.firstOrNull { Text.fold(it.second).startsWith(fq) && fq.length >= 3 }
            ?: apps.map { it to Text.nameScore(q, it.second) }.filter { it.second >= 0.75 }.maxByOrNull { it.second }?.first
            ?: apps.firstOrNull { it.first.activityInfo.packageName.lowercase().contains(fq.replace(" ", "")) && fq.length >= 4 }
            ?: return false to tr("I couldn't find an app called $name.", "$name नावाचं ॲप सापडलं नाही.")
        val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
            ?: return false to tr("Could not open ${match.second}.", "${match.second} उघडता आलं नाही.")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true to tr("Opening ${match.second}.", "${match.second} उघडत आहे.")
    }

    fun openUrl(url: String): String {
        val uri = Uri.parse(url.trim())
        val allowed = setOf("http", "https", "geo", "mailto", "tel", "sms", "smsto", "market", "whatsapp")
        if (uri.scheme?.lowercase() !in allowed) return "REFUSED: links of type '${uri.scheme}' are not allowed."
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened $url"
        } catch (e: Exception) {
            "No app can open that link."
        }
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
    fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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
    suspend fun waitForNode(timeoutMs: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            findNode(match)?.let { return it }
            if (System.currentTimeMillis() > end) return null
            delay(250)
        }
    }

    /** Tap an element (its clickable parent, or its centre as a fallback). */
    suspend fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** All readable text on screen, for "read the screen". */
    fun visibleText(limit: Int = 800): String {
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
