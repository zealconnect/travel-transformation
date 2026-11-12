package com.example.voiceagent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the brain understood: steps to run in order, and anything to say back. */
data class Plan(val steps: List<Command>, val reply: String)

/**
 * The agent's understanding: Google's Gemma 4 model running entirely on the phone
 * (LiteRT-LM). Private, offline, free. The instructions are loaded once and cached,
 * so each command only has to process what you said.
 */
object Brain {

    const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
    const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val MODEL_BYTES = 2_588_147_712L
    const val MIN_BYTES = 2_000_000_000L

    fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir("models"), MODEL_FILE)
    fun installed(ctx: Context) = modelFile(ctx).let { it.exists() && it.length() > MIN_BYTES }

    private val lock = Mutex()
    private var engine: Engine? = null
    private var base: Conversation? = null
    @Volatile var backendName: String = ""
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null

    @Volatile var loading = false
        private set
    val ready: Boolean get() = base != null

    private fun asset(ctx: Context, name: String) = ctx.assets.open("brain/$name").bufferedReader().use { it.readText() }

    private fun systemPrompt(ctx: Context) =
        asset(ctx, "system.txt") + "\n\nExamples:\n" + asset(ctx, "examples.txt")

    /** Load the model (GPU first, CPU if the GPU can't) and cache the instructions. */
    suspend fun load(ctx: Context): Boolean = lock.withLock {
        if (base != null) return@withLock true
        if (!installed(ctx)) return@withLock false
        loading = true
        try {
            withContext(Dispatchers.IO) {
                val app = ctx.applicationContext
                val cache = File(app.cacheDir, "litertlm").apply { mkdirs() }.path
                for ((name, backend) in listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })) {
                    try {
                        val e = Engine(EngineConfig(modelPath = modelFile(app).path, backend = backend(), cacheDir = cache))
                        e.initialize()
                        val conv = e.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(systemPrompt(app)),
                                samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                                prefillPrefaceOnInit = true,
                            )
                        )
                        engine = e; base = conv; backendName = name
                        return@withContext true
                    } catch (_: Throwable) {
                        try { engine?.close() } catch (_: Throwable) {}
                        engine = null
                    }
                }
                false
            }
        } finally {
            loading = false
        }
    }

    /** Load in the background so the first command is quick. */
    fun preload(ctx: Context) {
        if (base != null || loading || !installed(ctx)) return
        scope.launch { load(ctx); touch() }
    }

    fun release() {
        idleJob?.cancel()
        try { base?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        base = null; engine = null
    }

    /** Free the memory after a while without use. */
    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(20 * 60 * 1000L); lock.withLock { release() } }
    }

    private fun now(): String =
        SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())

    /** Understand one thing the user said. Null if the model isn't available or failed. */
    suspend fun understand(ctx: Context, said: String): Plan? {
        if (!load(ctx)) return null
        val raw = withTimeoutOrNull(25_000) {
            lock.withLock {
                val b = base ?: return@withLock null
                withContext(Dispatchers.IO) {
                    val conv = b.clone()
                    try {
                        val reply = conv.sendMessage(Message.user("[Now: ${now()}]\n$said"))
                        reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    } catch (_: Throwable) {
                        null
                    } finally {
                        try { conv.close() } catch (_: Throwable) {}
                    }
                }
            }
        }
        touch()
        return raw?.let { PlanParser.parse(it, said) }
    }

    /** One-off question with its own fresh context (used for step-by-step screen control). */
    suspend fun complete(ctx: Context, prompt: String): String? {
        if (!load(ctx)) return null
        return lock.withLock {
            val e = engine ?: return@withLock null
            withContext(Dispatchers.IO) {
                try {
                    e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(1, 1.0, 0.0, 0))).use { c ->
                        c.sendMessage(Message.user(prompt)).contents.contents
                            .filterIsInstance<Content.Text>().joinToString("") { it.text }
                    }
                } catch (_: Throwable) { null }
            }
        }.also { touch() }
    }
}

/** Turns the model's JSON into commands, checking the details it tends to get wrong. */
object PlanParser {

    /** First {...} in the text. */
    fun firstJson(s: String): JSONObject? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when { esc -> esc = false; c == '\\' -> esc = true; c == '"' -> inStr = false }
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull() }
            }
        }
        return null
    }

    fun parse(raw: String, said: String): Plan? {
        val o = firstJson(raw) ?: return null
        val arr = o.optJSONArray("steps")
        val steps = mutableListOf<Command>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { step(it) }?.let { steps += it }
        fixNumbers(steps, said)
        // The model sometimes drops a simple extra request ("… and then turn on the torch").
        for (clause in clauses(said).drop(1)) {
            val extra = CommandParser.parse(clause) ?: continue
            if (extra is Command.WaMessage || extra is Command.WaUnsplit || extra is Command.Sms) continue
            if (steps.none { it::class == extra::class }) steps += extra
        }
        val reply = o.optString("reply").trim().takeIf { it != "null" }.orEmpty()
        return Plan(steps, if (steps.isEmpty()) reply else "")
    }

    private val JOINERS = Regex("""\s+(?:and then|and after that|after that|then|and also|आणि मग|आणि नंतर|त्यानंतर|मग|नंतर)\s+""", RegexOption.IGNORE_CASE)

    fun clauses(said: String): List<String> = said.split(JOINERS).map { it.trim() }.filter { it.isNotEmpty() }

    /** Small models are weak at arithmetic: recompute times and durations from the words. */
    private fun fixNumbers(steps: MutableList<Command>, said: String) {
        val text = CommandParser.normalize(said)
        if (steps.count { it is Command.Timer } == 1) {
            CommandParser.parseDuration(text)?.let { d -> steps.replaceAll { if (it is Command.Timer) Command.Timer(d) else it } }
        }
        val alarmAt = Regex("""alarm|wake|अलार्म|उठव""").find(text)?.range?.first
        if (steps.count { it is Command.Alarm } == 1 && alarmAt != null) {
            // Only look at the words near "alarm", so numbers in a message don't get mixed in.
            val after = text.substring(alarmAt, minOf(text.length, alarmAt + 40))
            val before = text.substring(maxOf(0, alarmAt - 25), alarmAt)   // Marathi puts the time first
            (CommandParser.parseTime(after) ?: CommandParser.parseTime(before))?.let { (h, m) -> steps.replaceAll { if (it is Command.Alarm) Command.Alarm(h, m) else it } }
        }
    }

    private fun step(o: JSONObject): Command? {
        fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val who = s("who")
        return when (o.optString("do")) {
            "call" -> who?.let { Command.Call(it) }
            "whatsapp" -> who?.let { Command.WaMessage(it, s("text")) }
            "sms" -> who?.let { Command.Sms(it, s("text")) }
            "wa_call" -> who?.let { Command.WaCall(it, o.optBoolean("video", false)) }
            "send_file" -> who?.let {
                val kind = when (o.optString("kind").lowercase()) {
                    "pdf" -> FileKind.PDF; "photo", "image" -> FileKind.IMAGE; "video" -> FileKind.VIDEO
                    "audio" -> FileKind.AUDIO; "document" -> FileKind.DOCUMENT; "screenshot" -> FileKind.SCREENSHOT
                    else -> FileKind.ANY
                }
                val words = (s("words") ?: "").split(Regex("\\s+")).filter { w -> w.length > 1 }
                Command.ShareFile(it, FileQuery(kind, words, o.optBoolean("latest", true) || words.isEmpty()))
            }
            "open" -> s("app")?.let { Command.OpenApp(it) }
            "play" -> s("query")?.let { Command.Play(it, null) }
            "search" -> s("query")?.let { Command.Search(it, null) }
            "navigate" -> s("place")?.let { Command.Navigate(it) }
            "alarm" -> if (o.has("hour")) Command.Alarm(o.optInt("hour").coerceIn(0, 23), o.optInt("minute").coerceIn(0, 59)) else null
            "timer" -> o.optInt("seconds").takeIf { it > 0 }?.let { Command.Timer(it) }
            "torch" -> Command.Toggle("torch", o.optBoolean("on", true))
            "wifi" -> Command.Toggle("wifi", o.optBoolean("on", true))
            "bluetooth" -> Command.Toggle("bluetooth", o.optBoolean("on", true))
            "volume" -> Command.Volume(when (o.optString("level")) { "down" -> "down"; "mute" -> "silent"; "max" -> "max"; else -> "up" })
            "time" -> Command.Time
            "date" -> Command.Date
            "battery" -> Command.Battery
            "language" -> Command.SetLanguage(if (o.optString("lang") == "mr") "mr-IN" else "en-IN")
            "stop" -> Command.Stop
            else -> null
        }
    }
}
