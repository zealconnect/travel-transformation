package com.example.voiceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The agent itself, running quietly in the background: listening (wake word or on request),
 * understanding, and acting. No floating button; it shows itself only as a thin glow at the
 * bottom of the screen while you talk to it, plus a minimised notification Android requires.
 */
class AgentService : Service() {

    companion object {
        @Volatile var instance: AgentService? = null
        private const val CHANNEL = "agent_quiet"
        private const val ACTION_TOGGLE = "com.example.voiceagent.TOGGLE"
        private const val ACTION_QUIT = "com.example.voiceagent.QUIT"

        private val waiting = mutableListOf<(AgentService) -> Unit>()

        /** Run [f] as soon as the service is up (it may still be starting). */
        fun whenReady(f: (AgentService) -> Unit) {
            val s = instance
            if (s != null) f(s) else synchronized(waiting) { waiting += f }
        }

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

    enum class State { IDLE, LISTENING, THINKING, WORKING }
    enum class Feel { WAKE, LISTEN, DONE, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    lateinit var voice: Voice
        private set
    val stateListeners = java.util.concurrent.CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile var state: State = State.IDLE
        private set
    val busy: Boolean get() = job?.isActive == true

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        voice = Voice(this) { Prefs.lang(this) }
        voice.init()
        getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("agent")   // older, louder channel
            createNotificationChannel(NotificationChannel(CHANNEL, "Running in background", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        }
        val ready = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        android.os.Handler(mainLooper).post { ready.forEach { it(this) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            stopSelf()   // usually the microphone permission is missing
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> Prefs.setEnabled(this, !Prefs.enabled(this))
            ACTION_QUIT -> { stopSelf(); return START_NOT_STICKY }
        }
        setState(State.IDLE)
        updateHandsFree()
        AgentTileService.refresh(this)
        Brain.preload(this)
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !busy) Brain.release()
    }

    override fun onDestroy() {
        instance = null
        passive?.cancel()
        stop()
        if (::voice.isInitialized) voice.shutdown()
        Brain.release()
        scope.cancel()
        AgentTileService.refresh(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val on = Prefs.enabled(this)
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AgentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getActivity(this, 4,
            Intent(this, TalkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (on) "Ready" else "Paused")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, if (on) "Pause" else "Resume", svc(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Quit", svc(ACTION_QUIT, 3)).build())
            .build()
    }

    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(1, notification()) } catch (_: Exception) {}
    }

    /** Can we open other apps right now? Android blocks it for background apps without one of these. */
    fun canOpenApps(): Boolean =
        Ui.visible > 0 || android.provider.Settings.canDrawOverlays(this) || Screen.current != null || Assistant.isDefault(this)

    /** When Android won't let us open an app now, offer it as a notification to tap. */
    fun offerLaunch(intent: Intent, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tap", "Next step", NotificationManager.IMPORTANCE_HIGH))
        val id = 100 + (System.nanoTime() % 50).toInt()
        val pi = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, Notification.Builder(this, "tap")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(text)
            .setContentText(tr("Tap to continue", "पुढे जाण्यासाठी टॅप करा"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build())
    }

    // ---------------------------------------------------------------- feedback

    /** Short vibrations instead of beeps and chatter. */
    fun feedback(f: Feel) {
        try {
            val v = getSystemService(VibratorManager::class.java).defaultVibrator
            val effect = when (f) {
                Feel.WAKE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Feel.LISTEN -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Feel.DONE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Feel.ERROR -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            v.vibrate(effect)
        } catch (_: Exception) {}
    }

    fun setState(s: State) {
        state = s
        stateListeners.forEach { try { it(s) } catch (_: Exception) {} }
    }

    // ---------------------------------------------------------------- sessions

    /** Listen for one command (tile, notification, shortcut, assistant button). */
    fun talk() {
        if (busy) return
        voice.cancelListening()
        job = scope.launch { runSession(null) }
    }

    /** Run a typed command (the "Try it" box in the app). */
    fun runTyped(command: String) {
        stop()
        job = scope.launch { runSession(command) }
    }

    fun onEnabledChanged(on: Boolean) {
        updateHandsFree()
        setState(State.IDLE)
    }

    fun stop() {
        job?.cancel()
        job = null
        if (::voice.isInitialized) voice.stopAll()
        setState(State.IDLE)
    }

    // ---------------------------------------------------------------- always listening

    private var passive: Job? = null

    private fun micAllowed() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun updateHandsFree() {
        val want = Prefs.enabled(this) && micAllowed()
        if (want) {
            if (passive?.isActive != true) passive = scope.launch { passiveLoop() }
        } else {
            passive?.cancel()
            passive = null
            if (::voice.isInitialized && !busy) voice.cancelListening()
        }
        refreshNotification()
    }

    private fun shouldPause(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isInteractive && !Prefs.listenScreenOff(this)) return true
        val am = getSystemService(android.media.AudioManager::class.java)
        return am.mode == android.media.AudioManager.MODE_IN_CALL ||
            am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == android.media.AudioManager.MODE_RINGTONE
    }

    /** Listen quietly, over and over; act only on sentences that start with the wake word. */
    private suspend fun passiveLoop() {
        var failures = 0
        while (true) {
            if (busy || shouldPause()) { delay(1000); continue }
            val heard = voice.listen(passive = true)
            if (busy) continue
            if (heard.isNullOrBlank()) {
                when (voice.lastError) {
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { Prefs.setEnabled(this, false); return }
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 0 -> { failures = 0; delay(150) }
                    else -> { failures++; delay(minOf(1000L * failures, 10_000L)) }
                }
                continue
            }
            failures = 0
            val rest = WakeWord.extract(heard, Prefs.wakeWord(this)) ?: continue
            feedback(Feel.WAKE)
            Brain.preload(this)
            job = scope.launch { runSession(rest.ifBlank { null }) }
            job?.join()
        }
    }

    private val OFF = Regex("(?i)\\b(disable|turn off|switch off|deactivate|pause)\\b.*\\b(yourself|agent|assistant|voice agent|listening)\\b|^\\s*(disable|pause|stop listening|go to sleep|sleep)\\s*$|\\bstop listening\\b|(स्वतःला|एजंट) बंद|ऐकणं बंद|ऐकणे बंद|^\\s*बंद हो\\s*$|^\\s*झोप\\s*$")
    private val ON = Regex("(?i)^\\s*(enable|resume|turn on|switch on|activate)\\s*(yourself|agent|voice agent|listening)?\\s*$|\\b(start|keep|always) listening\\b|hands[- ]?free|सतत ऐक|ऐकणं (सुरू|चालू)|ऐकणे (सुरू|चालू)|(स्वतःला|एजंट) (सुरू|चालू)")

    private suspend fun runSession(typed: String?) {
        try {
            val command = typed ?: run {
                feedback(Feel.LISTEN)
                setState(State.LISTENING)
                voice.listen()
            }
            if (command.isNullOrBlank()) {
                if (voice.lastError == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    voice.speak(tr("I can't use the microphone. Open Voice Agent and allow it.",
                        "मला मायक्रोफोन वापरता येत नाही. Voice Agent उघडून परवानगी द्या."))
                return
            }
            if (OFF.containsMatchIn(command)) {
                Prefs.setEnabled(this, false)
                feedback(Feel.DONE)
                return
            }
            if (ON.containsMatchIn(command)) {
                Prefs.setEnabled(this, true)
                feedback(Feel.DONE)
                return
            }
            setState(State.WORKING)
            Agent(this, voice).run(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            feedback(Feel.ERROR)
        } finally {
            setState(State.IDLE)
        }
    }

    /** Ask a question out loud and listen for the answer. */
    suspend fun ask(question: String): String? {
        voice.speak(question)
        feedback(Feel.LISTEN)
        setState(State.LISTENING)
        try {
            return voice.listen()
        } finally {
            setState(State.WORKING)
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
