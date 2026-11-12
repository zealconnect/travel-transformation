package com.example.voiceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The agent itself: the floating 🎤 button, listening/talking, always-listening mode,
 * and running your commands. It's a normal foreground service (with a notification), so the
 * app needs no Accessibility permission and installs without being blocked by Play Protect.
 * Tapping and reading other apps' screens is only available in the "full" build ([Screen]).
 */
class AgentService : Service() {

    companion object {
        @Volatile var instance: AgentService? = null
        private const val CHANNEL = "agent"
        private const val ACTION_TOGGLE = "com.example.voiceagent.TOGGLE"
        private const val ACTION_QUIT = "com.example.voiceagent.QUIT"

        /** Start the agent (shows the 🎤 button). Call while the app is open. */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, AgentService::class.java)); true
        } catch (_: Exception) { false }

        fun quit(ctx: Context) {
            try { ctx.stopService(Intent(ctx, AgentService::class.java)) } catch (_: Exception) {}
        }

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

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        voice = Voice(this) { Prefs.lang(this) }
        voice.init()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Voice Agent", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Android refused (usually the microphone permission is missing).
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> Prefs.setEnabled(this, !Prefs.enabled(this))
            ACTION_QUIT -> { stopSelf(); return START_NOT_STICKY }
        }
        showBubble()
        setState(State.IDLE)
        updateHandsFree()
        AgentTileService.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        passive?.cancel()
        stop()
        removeBubble()
        if (::voice.isInitialized) voice.shutdown()
        LocalBrain.close()
        scope.cancel()
        AgentTileService.refresh(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val on = Prefs.enabled(this)
        val wake = Prefs.wakeWord(this)
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AgentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (on) "Voice Agent is listening" else "Voice Agent")
            .setContentText(if (on) "Say \u201c$wake\u201d, then your command" else "Disabled \u00b7 tap 🎤 to talk")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (on) "Disable" else "Enable", svc(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Quit", svc(ACTION_QUIT, 3)).build())
            .build()
    }

    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(1, notification()) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- sessions

    /** Bubble tap: start listening for a command, or stop the current one. Works whether enabled or not. */
    fun toggle() {
        if (job?.isActive == true) { stop(); return }
        voice.cancelListening()   // pause background listening while we take this command
        job = scope.launch { runSession(null) }
    }

    /** Run a typed command (handy for testing from the app screen). */
    fun runTyped(command: String) {
        stop()
        job = scope.launch { runSession(command) }
    }

    /** Called when Enable/Disable changes anywhere (app, tile, bubble, notification, voice). */
    fun onEnabledChanged(on: Boolean) {
        updateHandsFree()
        setState(State.IDLE)
    }

    // ---------------------------------------------------------------- always listening

    private var passive: Job? = null

    private fun micAllowed() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Start or stop background listening to match the Enable/Disable setting. */
    fun updateHandsFree() {
        val want = Prefs.enabled(this) && micAllowed()
        if (want) {
            if (passive?.isActive != true) passive = scope.launch { passiveLoop() }
        } else {
            passive?.cancel()
            passive = null
            if (::voice.isInitialized && job?.isActive != true) voice.cancelListening()
        }
        refreshNotification()
        setState(State.IDLE)
    }

    /** Is now a bad time to listen (screen off, on a call, another app recording)? */
    private fun shouldPause(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isInteractive && !Prefs.listenScreenOff(this)) return true
        val am = getSystemService(android.media.AudioManager::class.java)
        if (am.mode == android.media.AudioManager.MODE_IN_CALL ||
            am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == android.media.AudioManager.MODE_RINGTONE) return true
        return false
    }

    /** Listen quietly, over and over; act only on sentences that start with the wake word. */
    private suspend fun passiveLoop() {
        var failures = 0
        while (true) {
            if (job?.isActive == true || shouldPause()) { delay(1000); continue }
            val heard = voice.listen(passive = true)
            if (job?.isActive == true) continue
            if (heard.isNullOrBlank()) {
                when (voice.lastError) {
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Prefs.setEnabled(this, false); return
                    }
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 0 -> { failures = 0; delay(150) }
                    else -> { failures++; delay(minOf(1000L * failures, 10_000L)) }   // mic busy etc.: back off
                }
                continue
            }
            failures = 0
            val rest = WakeWord.extract(heard, Prefs.wakeWord(this)) ?: continue
            job = scope.launch { runSession(rest.ifBlank { null }, greet = rest.isBlank()) }
            job?.join()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (::voice.isInitialized) voice.stopAll()
        setState(State.IDLE)
    }

    private suspend fun runSession(typed: String?, greet: Boolean = false) {
        try {
            if (greet) voice.speak(tr("Yes?", "बोला?"))
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
            if (Regex("(?i)\\b(disable|turn off|switch off|deactivate|go to sleep)\\b.*\\b(yourself|agent|assistant|voice agent|listening)\\b|^\\s*(disable|turn off|stop listening|go to sleep|sleep)\\s*$|\\bstop listening\\b|(स्वतःला|एजंट) बंद|ऐकणं बंद|ऐकणे बंद|^\\s*बंद हो\\s*$|^\\s*झोप\\s*$")
                    .containsMatchIn(command)) {
                Prefs.setEnabled(this, false)
                voice.speak(tr("Okay, I've stopped listening. Tap me when you need me, or double tap to enable me again.",
                    "ठीक आहे, मी ऐकणं बंद केलं. गरज असेल तेव्हा मला टॅप करा, किंवा परत सुरू करायला दोनदा टॅप करा."))
                return
            }
            if (Regex("(?i)^\\s*(enable|turn on|switch on|activate)\\s*(yourself|agent|voice agent|listening)?\\s*$|\\b(start|keep|always) listening\\b|hands[- ]?free|सतत ऐक|ऐकणं (सुरू|चालू)|ऐकणे (सुरू|चालू)|(स्वतःला|एजंट) (सुरू|चालू)")
                    .containsMatchIn(command)) {
                Prefs.setEnabled(this, true)
                voice.speak(enabledMessage())
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

    fun enabledMessage(): String {
        val w = Prefs.wakeWord(this)
        return tr("I'm listening. Just say $w, then what you need.", "मी ऐकतोय. फक्त $w म्हणा आणि काय हवं ते सांगा.")
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
        if (bubble != null || !android.provider.Settings.canDrawOverlays(this)) return
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
                        // Double-tap: Enable / Disable always-listening.
                        handler.removeCallbacks(pendingTap!!)
                        pendingTap = null
                        val on = !Prefs.enabled(this)
                        if (on && !micAllowed()) {
                            android.widget.Toast.makeText(this, "Allow the microphone in the Voice Agent app first.", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            Prefs.setEnabled(this, on)
                            android.widget.Toast.makeText(this,
                                if (on) "Voice Agent ENABLED: always listening" else "Voice Agent DISABLED", android.widget.Toast.LENGTH_SHORT).show()
                            scope.launch {
                                voice.cancelListening()
                                voice.speak(if (on) enabledMessage() else tr("Disabled. Tap me when you need me.", "बंद केलं. गरज असेल तेव्हा मला टॅप करा."))
                            }
                        }
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
                // Green = enabled, always listening for the wake word. Grey = disabled (tap to talk).
                State.IDLE -> if (Prefs.enabled(this@AgentService)) "🎤" to "#2E7D32" else "🎤" to "#757575"
                State.LISTENING -> "👂" to "#E53935"
                State.WORKING -> "⏹" to "#FB8C00"   // tap to stop
            }
            b.text = label
            (b.background as GradientDrawable).setColor(Color.parseColor(color))
        }
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

}
