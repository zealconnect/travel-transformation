package com.example.voiceagent

import kotlinx.coroutines.delay

/**
 * Decides what you meant and runs it:
 *  1. Obvious one-liners ("torch on", "what time is it") run instantly from fixed patterns.
 *  2. Everything else goes to the on-phone Gemma 4 brain, which understands free speech in
 *     English, Marathi or a mix, and can split one sentence into several steps.
 *  3. If the brain isn't installed, the fixed patterns are used for everything.
 */
class Agent(private val svc: AgentService, private val voice: Voice) {

    private fun tr(en: String, mr: String) = svc.tr(en, mr)

    private fun instant(c: Command) = when (c) {
        Command.Stop, Command.Help, Command.Time, Command.Date, Command.Battery, Command.ReadScreen,
        Command.Camera, Command.ShowAlarms -> true
        is Command.Toggle, is Command.Volume, is Command.Global, is Command.SetLanguage,
        is Command.Timer, is Command.Alarm, is Command.OpenApp -> true
        else -> false
    }

    suspend fun run(said: String, depth: Int = 0) {
        val fast = CommandParser.parse(said)
        val single = PlanParser.clauses(said).size == 1
        if (fast != null && single && instant(fast)) return Actions(svc, voice).executeAll(listOf(fast))

        if (Brain.installed(svc)) {
            svc.setState(AgentService.State.THINKING)
            val plan = Brain.understand(svc, said)
            when {
                plan == null -> {}
                plan.steps.isNotEmpty() -> return Actions(svc, voice).executeAll(plan.steps)
                plan.reply.isNotBlank() && plan.reply.trimEnd().endsWith("?") && depth == 0 -> {
                    // Something essential was missing ("message Amit" → "What should I tell Amit?").
                    val answer = svc.ask(plan.reply)
                    if (answer.isNullOrBlank()) return
                    return run("$said — $answer", depth + 1)
                }
                plan.reply.isNotBlank() -> { svc.feedback(AgentService.Feel.DONE); return voice.speak(plan.reply) }
            }
        }
        if (fast != null) return Actions(svc, voice).executeAll(listOf(fast))
        svc.feedback(AgentService.Feel.ERROR)
        voice.speak(
            if (Brain.installed(svc)) tr("Sorry, I didn't get that.", "माफ करा, मला समजलं नाही.")
            else tr("Sorry, I didn't get that. Install the on-phone brain in the app so I understand more.",
                "माफ करा, समजलं नाही. मला जास्त समजावं म्हणून ॲपमध्ये ऑन-फोन ब्रेन इन्स्टॉल करा.")
        )
    }
}

/** Full build only: lets the brain operate apps step by step (read screen → tap/type → repeat). */
class ScreenLoop(private val svc: AgentService, private val voice: Voice) {

    private fun tr(en: String, mr: String) = svc.tr(en, mr)

    private val risky = Regex(
        "(?i)\\b(pay|payment|buy|purchase|order|checkout|transfer|send money|delete|remove|uninstall|unsubscribe|book now|confirm)\\b|पेमेंट|खरेदी|डिलीट|पैसे पाठवा"
    )

    private fun prompt(goal: String, history: List<String>, screen: String) = """
You operate an Android phone one step at a time to reach this goal: $goal
Steps done so far:
${if (history.isEmpty()) "(none)" else history.joinToString("\n")}
Current screen (elements are numbered [n]):
$screen
Reply with ONE JSON object for the next step:
{"do":"tap","id":n} | {"do":"type","id":n,"text":"..."} | {"do":"enter","id":n} | {"do":"scroll","dir":"down"} | {"do":"scroll","dir":"up"} | {"do":"back"} | {"do":"home"} | {"do":"open_app","app":"name"} | {"do":"done","say":"short summary"}
Never type passwords. Say done as soon as the goal is reached.""".trim()

    private suspend fun confirm(what: String): Boolean {
        val a = svc.ask(tr("Should I tap \"$what\"?", "\"$what\" दाबू का?")) ?: return false
        return Regex("(?i)^\\s*(yes|yeah|yep|sure|ok|okay|do it|go ahead|हो|होय|हां|चालेल|कर)").containsMatchIn(a)
    }

    suspend fun run(goal: String) {
        val sc = Screen.current ?: return voice.speak(tr(
            "Sorry, working inside other apps needs screen control, which this version doesn't have.",
            "माफ करा, इतर ॲप्समध्ये काम करायला स्क्रीन कंट्रोल लागतो, जो या व्हर्जनमध्ये नाही."
        ))
        if (!Brain.installed(svc)) return voice.speak(tr("Install the on-phone brain in the app first.", "आधी ॲपमध्ये ऑन-फोन ब्रेन इन्स्टॉल करा."))
        val history = mutableListOf<String>()
        var last = ""; var repeats = 0
        for (step in 1..12) {
            delay(800)
            val reply = Brain.complete(svc, prompt(goal, history.takeLast(6), sc.dumpScreen(5000))) ?: ""
            val o = PlanParser.firstJson(reply)
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
