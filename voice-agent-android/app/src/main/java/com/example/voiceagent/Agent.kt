package com.example.voiceagent

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The brain: try the fixed English/Marathi commands first (instant, free, offline),
 * then fall back to the optional on-phone AI model.
 */
class Agent(private val svc: AgentService, private val voice: Voice) {

    private fun tr(en: String, mr: String) = svc.tr(en, mr)

    suspend fun run(text: String) {
        val cmd = CommandParser.parse(text) ?: aiCommand(text) ?: return
        Actions(svc, voice).execute(cmd)
    }

    private suspend fun aiCommand(text: String): Command? {
        if (Prefs.modelPath(svc) == null) {
            voice.speak(tr(
                "Sorry, I don't know how to do that yet. Say help to hear what I can do.",
                "माफ करा, हे मला अजून करता येत नाही. मी काय करू शकतो ते ऐकायला मदत म्हणा."
            ))
            return null
        }
        voice.speak(tr("One moment.", "एक क्षण."))
        val brain = LocalBrain.get(svc) ?: run {
            voice.speak(tr("I couldn't load the AI model. Try adding it again in the app.", "AI मॉडेल सुरू झालं नाही. ॲपमध्ये पुन्हा जोडून बघा."))
            return null
        }
        val reply = try { brain.complete(Prompts.interpret(text)) } catch (e: Exception) { "" }
        val cmd = LocalBrain.firstJson(reply)?.let { Prompts.toCommand(it) }
        if (cmd == null) voice.speak(tr("Sorry, I didn't understand that.", "माफ करा, मला समजलं नाही."))
        return cmd
    }
}

/** What we ask the on-phone AI, and how we read its answers. */
object Prompts {

    fun interpret(text: String) = """
You control an Android phone for its owner. Turn the request into ONE JSON object, choosing one of:
{"a":"call","who":"<contact name or number>"}
{"a":"whatsapp_message","who":"<name>","text":"<message to send>"}
{"a":"whatsapp_call","who":"<name>","video":false}
{"a":"share_file","who":"<name>","kind":"pdf|image|video|audio|document|screenshot|any","words":"<words in the file name>","latest":true}
{"a":"share_this","who":"<name>"}
{"a":"sms","who":"<name>","text":"<message>"}
{"a":"open_app","app":"<app name>"}
{"a":"search","q":"<query>"}
{"a":"play","q":"<song or video>"}
{"a":"navigate","place":"<place>"}
{"a":"alarm","hour":7,"minute":30}
{"a":"timer","seconds":300}
{"a":"screen","goal":"<what to do inside apps, step by step>"}
{"a":"answer","text":"<short spoken answer to a question>"}
The request may be in English or Marathi. Keep names exactly as spoken. Use "screen" for anything done by tapping inside apps.
Examples:
please ring up my brother Amit -> {"a":"call","who":"Amit"}
अमितला सांग मी पाच मिनिटात पोहोचतो -> {"a":"whatsapp_message","who":"अमित","text":"मी पाच मिनिटात पोहोचतो"}
like the first post on instagram -> {"a":"screen","goal":"open Instagram and like the first post in the feed"}
what is the capital of japan -> {"a":"answer","text":"Tokyo is the capital of Japan."}
Request: $text
JSON:""".trim()

    fun screenStep(goal: String, history: List<String>, screen: String) = """
You operate an Android phone one step at a time to reach this goal: $goal
Steps done so far:
${if (history.isEmpty()) "(none)" else history.joinToString("\n")}
Current screen (elements are numbered [n]):
$screen
Reply with ONE JSON object for the next step:
{"do":"tap","id":n} | {"do":"type","id":n,"text":"..."} | {"do":"enter","id":n} | {"do":"scroll","dir":"down"} | {"do":"scroll","dir":"up"} | {"do":"back"} | {"do":"home"} | {"do":"open_app","app":"name"} | {"do":"done","say":"short summary for the user"}
Never type passwords. Say done as soon as the goal is reached.
JSON:""".trim()

    fun toCommand(o: JSONObject): Command? {
        fun str(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val who = str("who")
        return when (o.optString("a")) {
            "call" -> who?.let { Command.Call(it) }
            "whatsapp_message" -> who?.let { Command.WaMessage(it, str("text")) }
            "whatsapp_call" -> who?.let { Command.WaCall(it, o.optBoolean("video", false)) }
            "share_file" -> who?.let {
                val kind = when (o.optString("kind").lowercase()) {
                    "pdf" -> FileKind.PDF; "image", "photo" -> FileKind.IMAGE; "video" -> FileKind.VIDEO
                    "audio" -> FileKind.AUDIO; "document" -> FileKind.DOCUMENT; "screenshot" -> FileKind.SCREENSHOT
                    else -> FileKind.ANY
                }
                val words = (str("words") ?: "").split(Regex("\\s+")).filter { w -> w.length > 1 }
                Command.ShareFile(it, FileQuery(kind, words, o.optBoolean("latest", words.isEmpty())))
            }
            "share_this" -> who?.let { Command.ShareThis(it) }
            "sms" -> who?.let { Command.Sms(it, str("text")) }
            "open_app" -> str("app")?.let { Command.OpenApp(it) }
            "search" -> str("q")?.let { Command.Search(it, null) }
            "play" -> str("q")?.let { Command.Play(it, null) }
            "navigate" -> str("place")?.let { Command.Navigate(it) }
            "alarm" -> if (o.has("hour")) Command.Alarm(o.optInt("hour").coerceIn(0, 23), o.optInt("minute").coerceIn(0, 59)) else null
            "timer" -> o.optInt("seconds").takeIf { it > 0 }?.let { Command.Timer(it) }
            "screen" -> str("goal")?.let { Command.ScreenTask(it) }
            "answer" -> str("text")?.let { Command.Answer(it) }
            else -> null
        }
    }
}

/** Lets the on-phone AI operate apps step by step (read screen → tap/type → repeat). */
class ScreenLoop(private val svc: AgentService, private val voice: Voice) {

    private fun tr(en: String, mr: String) = svc.tr(en, mr)

    private val risky = Regex(
        "(?i)\\b(pay|payment|buy|purchase|order|checkout|transfer|send money|delete|remove|uninstall|unsubscribe|book now|confirm)\\b|पेमेंट|खरेदी|डिलीट|पैसे पाठवा"
    )

    private suspend fun confirm(what: String): Boolean {
        val a = svc.ask(tr("Should I tap \"$what\"? Say yes or no.", "\"$what\" दाबू का? हो किंवा नाही म्हणा.")) ?: return false
        return Regex("(?i)^\\s*(yes|yeah|yep|sure|ok|okay|do it|go ahead|हो|होय|हां|चालेल|कर)").containsMatchIn(a)
    }

    suspend fun run(goal: String) {
        val sc = Screen.current ?: return voice.speak(tr(
            "Sorry, working inside other apps needs screen control, which this version doesn't have.",
            "माफ करा, इतर ॲप्समध्ये काम करायला स्क्रीन कंट्रोल लागतो, जो या व्हर्जनमध्ये नाही."
        ))
        val brain = LocalBrain.get(svc) ?: return voice.speak(tr(
            "For that I need the free offline AI model. Add it in the Voice Agent app.",
            "त्यासाठी मोफत ऑफलाइन AI मॉडेल हवं. Voice Agent ॲपमध्ये ते जोडा."
        ))
        val history = mutableListOf<String>()
        var last = ""
        var repeats = 0
        for (step in 1..12) {
            delay(800)
            val screen = sc.dumpScreen(brain.screenChars)
            val reply = try { brain.complete(Prompts.screenStep(goal, history.takeLast(6), screen)) } catch (e: Exception) { "" }
            val o = LocalBrain.firstJson(reply)
            if (o == null) { history += "$step. (no valid reply)"; continue }
            val sig = o.toString()
            repeats = if (sig == last) repeats + 1 else 0
            last = sig
            if (repeats >= 2) return voice.speak(tr("I seem to be stuck, so I stopped.", "मी अडकलो, म्हणून थांबलो."))
            val id = o.optInt("id", -1)
            val result = when (o.optString("do")) {
                "tap" -> {
                    val what = sc.labelOf(id)
                    if (risky.containsMatchIn(what) && !confirm(what.substringBefore(" | ")))
                        return voice.speak(tr("Okay, I stopped.", "ठीक आहे, थांबलो."))
                    sc.tap(id)
                }
                "type" -> sc.typeText(id, o.optString("text"))
                "enter" -> sc.pressEnter(id)
                "scroll" -> sc.scroll(o.optString("dir", "down"), null)
                "back" -> sc.press("back")
                "home" -> sc.press("home")
                "open_app" -> svc.openApp(o.optString("app")).second
                "done" -> return voice.speak(o.optString("say").ifBlank { tr("Done.", "झालं.") })
                else -> "Unknown step."
            }
            history += "$step. $sig -> $result"
        }
        voice.speak(tr("I couldn't finish that.", "ते मला पूर्ण करता आलं नाही."))
    }
}
