package com.example.voiceagent

import java.util.Locale

/** Everything the agent knows how to do. */
sealed class Command {
    data class Call(val who: String) : Command()
    data class WaMessage(val who: String, val message: String?) : Command()
    /** "tell rahul I'm late": the name and message aren't separated yet; contacts decide where the name ends. */
    data class WaUnsplit(val text: String, val sms: Boolean = false) : Command()
    data class WaCall(val who: String, val video: Boolean) : Command()
    data class ShareFile(val who: String, val query: FileQuery) : Command()
    data class ShareThis(val who: String) : Command()
    data class Sms(val who: String, val message: String?) : Command()
    data class OpenApp(val name: String) : Command()
    data class OpenUrl(val url: String) : Command()
    data class Toggle(val what: String, val on: Boolean) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class Timer(val seconds: Int) : Command()
    data class Navigate(val place: String) : Command()
    data class Search(val query: String, val site: String?) : Command()
    data class Play(val query: String, val app: String?) : Command()
    data class Volume(val action: String) : Command()
    data class Global(val action: String) : Command()
    data class SetLanguage(val tag: String) : Command()
    data class ScreenTask(val goal: String) : Command()
    data class Answer(val text: String) : Command()
    data object ShowAlarms : Command()
    data object Camera : Command()
    data object Time : Command()
    data object Date : Command()
    data object Battery : Command()
    data object ReadScreen : Command()
    data object Help : Command()
    data object Stop : Command()
}

/**
 * Turns what you said into a [Command] using fixed English and Marathi patterns.
 * Free, instant and offline. Anything it doesn't recognise goes to the optional on-phone AI.
 */
object CommandParser {

    fun parse(raw: String): Command? {
        val s = normalize(raw)
        if (s.isBlank()) return null
        return if (Text.hasDevanagari(s)) marathi(s) ?: english(s) else english(s)
    }

    // ------------------------------------------------------------------ cleanup

    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
        s = Text.asciiDigits(s)
        s = s.replace(Regex("""\ba\.\s?m\b\.?"""), "am").replace(Regex("""\bp\.\s?m\b\.?"""), "pm")
        s = s.replace(Regex("""(?<!\d):(?!\d)"""), " saying ")
        s = s.replace(Regex("""[!?,;"“”।॥]"""), " ").replace(Regex("""\.(?=\s|$)"""), " ")
        s = s.replace(Regex("""\bwhat'?s\s?app\b|\bwhatsap+\b|\bwats\s?app\b"""), "whatsapp")
        s = s.replace(Regex("""व्ह[ाॉ]ट्?स\S*?प"""), "whatsapp")
        s = s.replace(Regex("""whatsapp(?:वरून|वरती|वर|ने|मधून|मध्ये|ला|चा|ची|चे)"""), "whatsapp ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        var prev: String
        do {
            prev = s
            s = s.replace(Regex("""^(?:hey|hi|hello|ok|okay|so|um+|uh+|please|kindly|just|can you|could you|would you|will you|can u|i want you to|i want to|i'd like to|i would like to|i need to|let's|जरा|प्लीज|कृपया|अरे|ए)\s+"""), "")
        } while (s != prev)
        do {
            prev = s
            s = s.replace(Regex("""\s+(?:please|for me|right now|now|quickly|immediately|thanks|thank you|प्लीज|जरा)$"""), "")
        } while (s != prev)
        return s.trim()
    }

    private fun clean(who: String): String = who.trim()
        .replace(Regex("""^(?:to|with|my|the)\s+"""), "")
        .replace(Regex("""\s+on\s+(?:his|her|their|the)?\s*(?:mobile|phone|cell|number)$"""), "")
        .replace(Regex("""'s$"""), "")
        .trim()

    private fun msg(who: String, message: String?, sms: Boolean): Command {
        val m = message?.trim()?.ifBlank { null }
        return if (sms) Command.Sms(clean(who), m) else Command.WaMessage(clean(who), m)
    }

    // ------------------------------------------------------------------ files

    private val FILE_REF = Regex(
        """\b(files?|pdfs?|photos?|pictures?|pics?|images?|videos?|documents?|docs?|docx|screen ?shots?|resume|cv|ppt|pptx|presentation|excel|xlsx|spreadsheet|sheet|songs?|audio|recording|voice note|apk|zip|selfie|invoice|bill|tickets?|receipt|report|voucher|itinerary|boarding pass|statement|aadhaa?r|pan card|passport)\b"""
    )
    private val MR_FILE = Regex("""फाईल|फाइल|फायल|फोटो|चित्र|इमेज|सेल्फी|पीडीएफ|व्हिड[िी]ओ|स्क्रीन ?शॉट|डॉक्युमेंट|कागदपत्र|गाणं|गाणे|ऑडिओ|रेकॉर्डिंग|तिकीट|तिकिट|बिल|रिझ्युम|बायोडाटा|पावती""")

    fun isFileRef(s: String) = FILE_REF.containsMatchIn(s) || MR_FILE.containsMatchIn(s)

    private val FILE_STOP = setOf(
        "the", "a", "an", "my", "me", "our", "your", "this", "that", "latest", "last", "recent", "recently", "newest",
        "new", "file", "files", "named", "called", "name", "titled", "of", "with", "from", "which", "i", "just", "took",
        "taken", "downloaded", "received", "got", "today", "yesterday", "one", "pdf", "pdfs", "photo", "photos",
        "picture", "pictures", "pic", "pics", "image", "images", "video", "videos", "document", "documents", "doc",
        "docs", "screenshot", "screenshots", "screen", "shot", "song", "songs", "audio", "recording", "selfie", "and",
        "in", "on", "for", "it", "is", "was", "all", "some", "folder", "downloads", "download", "gallery", "whatsapp",
        "फाईल", "फाइल", "फायल", "फोटो", "चित्र", "इमेज", "पीडीएफ", "व्हिडिओ", "व्हिडीओ", "स्क्रीनशॉट", "डॉक्युमेंट",
        "कागदपत्र", "गाणं", "गाणे", "ऑडिओ", "शेवटचा", "शेवटची", "शेवटचं", "शेवटचे", "नवीन", "लेटेस्ट", "आत्ताच",
        "आताच", "काढलेला", "काढलेली", "आलेली", "आलेला", "डाउनलोड", "केलेली", "केलेला", "नावाची", "नावाचा", "नावाचं",
        "नावाचे", "माझा", "माझी", "माझे", "माझं", "हा", "ही", "हे", "तो", "ती", "ते", "एक", "पाठव", "पाठवा", "शेअर",
        "कर", "करा", "मला", "सेल्फी"
    )

    fun fileQuery(what: String): FileQuery {
        val w = what.lowercase(Locale.ROOT)
        fun has(re: String) = Regex(re).containsMatchIn(w)
        val kind = when {
            has("""\bpdfs?\b|पीडीएफ""") -> FileKind.PDF
            has("""\bscreen ?shots?\b|स्क्रीन ?शॉट""") -> FileKind.SCREENSHOT
            has("""\b(photos?|pictures?|pics?|images?|selfie)\b|फोटो|चित्र|इमेज|सेल्फी""") -> FileKind.IMAGE
            has("""\bvideos?\b|व्हिड[िी]ओ""") -> FileKind.VIDEO
            has("""\b(songs?|audio|recording|voice note)\b|गाणं|गाणे|ऑडिओ|रेकॉर्डिंग""") -> FileKind.AUDIO
            has("""\b(documents?|docs?|docx|ppt|pptx|presentation|excel|xlsx|spreadsheet|sheet)\b|डॉक्युमेंट|कागदपत्र""") -> FileKind.DOCUMENT
            else -> FileKind.ANY
        }
        val latest = has("""\b(latest|last|recent|recently|newest|new|just)\b|शेवटच|नवीन|लेटेस्ट|आत्ताच|आताच""")
        val translated = mapOf(
            "तिकीट" to "ticket", "तिकिट" to "ticket", "बिल" to "bill", "रिझ्युम" to "resume",
            "बायोडाटा" to "biodata", "पावती" to "receipt", "रिपोर्ट" to "report"
        )
        val kw = w.split(Regex("""[^\p{L}\p{M}\p{N}]+"""))
            .filter { it.length > 1 && it !in FILE_STOP && !it.startsWith("पाठव") }
            .map { translated[it] ?: it }
        return FileQuery(kind, kw, latest || kw.isEmpty())
    }

    // ------------------------------------------------------------------ times

    fun parseTime(s: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})(?:[:.](\d{2}))?""").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val before = s.substring(0, m.range.first)
        when {
            before.trimEnd().endsWith("साडे") -> min = 30
            before.trimEnd().endsWith("सव्वा") -> min = 15
            before.trimEnd().endsWith("पावणे") -> { h -= 1; min = 45 }
        }
        val night = Regex("""\b(night|tonight)\b|रात्री""").containsMatchIn(s)
        val pm = night || Regex("""\bpm\b|\b(evening|afternoon)\b|दुपारी|संध्याकाळी|सायंकाळी""").containsMatchIn(s)
        val am = Regex("""\bam\b|\bmorning\b|सकाळी|पहाटे""").containsMatchIn(s)
        if (pm && h < 12 && !(night && h < 5)) h += 12
        if (am && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    fun parseDuration(s: String): Int? {
        var total = 0
        for (m in Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|सेकंद|मिनिट\S*|तास)""").findAll(s)) {
            val n = m.groupValues[1].toInt()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") || u == "तास" -> n * 3600
                u.startsWith("m") || u.startsWith("मिनिट") -> n * 60
                else -> n
            }
        }
        return total.takeIf { it > 0 }
    }

    // ------------------------------------------------------------------ English

    private fun english(s: String): Command? {
        fun re(p: String) = Regex(p)
        fun has(p: String) = Regex(p).containsMatchIn(s)
        val wa = "whatsapp" in s
        val sms = has("""\bsms\b|\btext message\b""")
        val off = has("""\b(off|disable|stop|deactivate|close)\b""")
        // Same sentence with "on WhatsApp" removed, for picking out names and messages.
        val t = s.replace(Regex("""\s*\b(?:on|via|using|through|over|in|from|by)\s+whatsapp\b"""), " ")
            .replace(Regex("""\bwhatsapp\b"""), " ").replace(Regex("""\s+"""), " ").trim()

        if (re("""^(stop|cancel|never ?mind|forget it|nothing|that's all|quit|shut up|be quiet)$""").matches(s)) return Command.Stop
        if (re("""^(help|what can you do.*|what do you do|commands|show commands)$""").matches(s)) return Command.Help
        if (has("""\bmarathi\b""") && has("""^(speak|talk|reply|answer|switch|change|use|set|language|marathi)""")) return Command.SetLanguage("mr-IN")
        if (has("""\benglish\b""") && has("""^(speak|talk|reply|answer|switch|change|use|set|language|english)""")) return Command.SetLanguage("en-IN")
        if (has("""^(what'?s|what is|tell me) (the )?(time|current time)|^what time is it|^(the )?time( now)?$|^current time""")) return Command.Time
        if (has("""^(what'?s|what is|tell me) (the |today'?s )?date|^what day is (it|today)|^today'?s date|^(the )?date$""")) return Command.Date
        if (has("""^(what'?s |what is |how much |check |tell me )?(the |my )?(phone'?s )?battery( level| percentage| left| status)?$|how much battery""")) return Command.Battery

        when {
            re("""^(go |press )?back$""").matches(s) -> return Command.Global("back")
            re("""^(go |press )?(to )?(the )?home( screen| button)?$""").matches(s) -> return Command.Global("home")
            re("""^(show |open )?(the |my )?recent( apps)?$|^recents$""").matches(s) -> return Command.Global("recents")
            re("""^(open|show|pull down|check)( the| my)? notifications?$""").matches(s) -> return Command.Global("notifications")
            re("""^(open|show)( the)? quick settings$""").matches(s) -> return Command.Global("quick_settings")
            re("""^(take|capture|grab)( a)? screen ?shot$""").matches(s) -> return Command.Global("screenshot")
            re("""^lock( the| my)? (phone|screen|device)$""").matches(s) -> return Command.Global("lock")
        }
        if (has("""^(read|what'?s on|what is on|describe)( the| my| this)? screen|^read (this|it)( out| aloud)?$|^read (the )?page$""")) return Command.ReadScreen

        if (has("""\b(torch|flash ?light)\b""") || re("""^(turn|switch) (on|off) (the )?flash$|^flash (on|off)$""").matches(s))
            return Command.Toggle("torch", !off)
        re("""\b(wi-?fi|bluetooth|hotspot|mobile data|airplane mode|flight mode)\b""").find(s)?.let { m ->
            if (has("""\b(on|off|enable|disable|start|stop|activate|deactivate)\b""")) {
                val what = when {
                    m.value.startsWith("wi") -> "wifi"
                    m.value == "bluetooth" -> "bluetooth"
                    m.value == "hotspot" -> "hotspot"
                    m.value == "mobile data" -> "data"
                    else -> "airplane"
                }
                return Command.Toggle(what, !off)
            }
        }
        if (has("""\b(volume|louder|quieter|mute|unmute|silent|vibrate)\b""") || has("""^(increase|decrease|lower|raise) (the )?sound""")) {
            return Command.Volume(
                when {
                    has("""\bunmute\b""") -> "unmute"
                    has("""\b(mute|silent|vibrate)\b""") -> "silent"
                    has("""\b(max|maximum|full)\b""") -> "max"
                    has("""\b(down|decrease|lower|reduce|less|quieter|low)\b""") -> "down"
                    else -> "up"
                }
            )
        }
        if (has("""\b(alarm|wake me)\b""")) return parseTime(s)?.let { Command.Alarm(it.first, it.second) } ?: Command.ShowAlarms
        if (has("""\btimer\b|^remind me in\b""")) parseDuration(s)?.let { return Command.Timer(it) }
        if (has("""^(take|click|capture|shoot)( a| an)? (photo|picture|pic|selfie)\b""") && !has("""\b(send|share)\b""")) return Command.Camera

        // ---- WhatsApp calls ("whatsapp call rahul", "video call mom")
        val video = has("""\bvideo call\b""")
        if ((wa || video) && has("""\bcall\b""") && !has("""\b(send|share|message|msg|text|tell)\b""")) {
            val who = clean(
                t.replace(Regex("""\b(make|start|place|do|give|ring|dial|up)\b|\b(an?|the)\b|\b(video|voice|audio)\b|\bcalls?\b|\b(to|with)\b"""), " ")
                    .replace(Regex("""\s+"""), " ")
            )
            if (who.isNotEmpty()) return Command.WaCall(who, video)
        }

        // ---- messages with the words to send
        re("""^(?:send|write|drop)\s+(?:an?\s+|the\s+)?(?:sms\s+|text\s+)?(?:message|msg|text|sms|note)\s+to\s+(.+?)\s+(?:saying|that says|which says|telling (?:him|her|them)(?: that)?|that)\s+(.+)$""")
            .find(t)?.let { return msg(it.groupValues[1], it.groupValues[2], sms) }
        re("""^(?:message|msg|text|sms|tell|inform|ping|send|write)\s+(.+?)\s+(?:saying|that says|which says|telling (?:him|her|them)(?: that)?|that)\s+(.+)$""")
            .find(t)?.let {
                val who = it.groupValues[1].replace(Regex("""\s+(?:an?\s+|the\s+)?(?:sms\s+|text\s+)?(?:message|msg|text|sms|note)$"""), "")
                return msg(who, it.groupValues[2], sms)
            }
        re("""^(?:send|write)\s+(?:an?\s+|the\s+)?(?:sms\s+|text\s+)?(?:message|msg|text|sms)\s+to\s+(.+)$""")
            .find(t)?.let { return msg(it.groupValues[1], null, sms) }
        re("""^open\s+(?:the\s+)?(?:chat|conversation)\s+(?:with|of|for)\s+(.+)$""").find(t)?.let { return Command.WaMessage(clean(it.groupValues[1]), null) }
        re("""^open\s+(.+?)'s\s+chat$""").find(t)?.let { return Command.WaMessage(clean(it.groupValues[1]), null) }

        // ---- sharing
        re("""^(?:send|share|forward)\s+(?:this|it|that|these|this one|this (?:file|photo|picture|pic|image|video|document|doc|pdf|link|page|post|reel|message|song|location))\s+(?:to|with)\s+(.+)$""")
            .find(t)?.let { return Command.ShareThis(clean(it.groupValues[1])) }
        re("""^(?:send|share|forward)\s+(.+?)\s+(?:to|with)\s+(.+)$""").find(t)?.let {
            val what = it.groupValues[1]
            val who = clean(it.groupValues[2])
            if (isFileRef(what)) return Command.ShareFile(who, fileQuery(what))
            val text = what.replace(Regex("""^(?:an?\s+|the\s+)?(?:message|msg|text|note)\s*(?:saying|that says|that)?\s*"""), "")
            return msg(who, text, sms)
        }
        re("""^(?:message|msg|text|sms|ping|tell|inform|ask)\s+(.+)$""").find(t)?.let { return Command.WaUnsplit(it.groupValues[1], sms) }

        // ---- phone calls
        re("""^(?:call|phone|dial|ring|ring up|make (?:a )?(?:phone )?call to|place (?:a )?call to|give)\s+(.+?)(?:\s+a\s+(?:call|ring))?$""").find(t)?.let {
            val who = clean(it.groupValues[1])
            if (who.isNotEmpty()) return if (wa) Command.WaCall(who, false) else Command.Call(who)
        }

        // "whatsapp rahul" on its own
        if (wa && t.isNotEmpty() && !has("""^(open|launch|start|go to|show)\b""")) return Command.WaUnsplit(t)

        re("""^(?:navigate|take me|directions|get directions|drive|walk|show (?:me )?(?:the )?(?:way|route|directions)|how (?:do|can) i (?:get|go|reach))\s+(?:to\s+)?(?:the\s+)?(.+)$""")
            .find(s)?.let { return Command.Navigate(it.groupValues[1]) }
        re("""^play\s+(.+?)(?:\s+(?:on|in)\s+(youtube music|youtube|spotify))?$""").find(s)?.let {
            return Command.Play(it.groupValues[1], it.groupValues[2].ifEmpty { null })
        }
        re("""^(?:search|google|look up)\s+(?:for\s+)?(.+?)(?:\s+(?:on|in)\s+(google|youtube|amazon|flipkart|google maps|maps))?$""").find(s)?.let {
            return Command.Search(it.groupValues[1], it.groupValues[2].ifEmpty { null })
        }
        re("""^(?:open|launch|start|run|go to|show me|show)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app(?:lication)?)?$""").find(s)?.let {
            val name = it.groupValues[1].replace(" dot ", ".").trim()
            if (re("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(name)) return Command.OpenUrl(name)
            return Command.OpenApp(name)
        }
        return null
    }

    // ------------------------------------------------------------------ Marathi

    private val MR_STOP = setOf(
        "फोन", "फ़ोन", "कॉल", "काॅल", "call", "phone", "डायल", "कर", "करा", "करून", "करशील", "करतोस", "करते", "करतो",
        "लाव", "लावा", "लावशील", "लाग", "लागा", "मेसेज", "मॅसेज", "msg", "message", "संदेश", "सेंड", "send", "शेअर",
        "share", "फॉरवर्ड", "forward", "सांग", "सांगा", "सांगशील", "कळव", "कळवा", "whatsapp", "वर", "वरून", "ने", "जरा",
        "प्लीज", "please", "कृपया", "ला", "ना", "व्हिडिओ", "व्हिडीओ", "video", "व्हॉइस", "ऑडिओ", "दे", "द्या", "टाक",
        "टाका", "एक", "मला", "आता", "लगेच", "तू", "तुम्ही", "हा", "ही", "हे", "हि", "ह्या", "this", "टेक्स्ट", "एसएमएस",
        "sms", "text", "लिहून", "की", "कि", "असं", "असे", "अस", "म्हणून", "त्याला", "तिला", "त्यांना", "आम्हाला", "तुला"
    )

    private fun isMrStop(t: String) =
        t in MR_STOP || t in FILE_STOP || t.startsWith("पाठव") || t.startsWith("whatsapp") || t.startsWith("मेसेज") ||
            MR_FILE.matches(t) || FILE_REF.matches(t)

    private val DATIVE = listOf("लाही", "कडे", "ला", "ना", "शी")

    /** Finds the person in a Marathi sentence: the word ending in -ला ("राहुलला") or the leftover words. */
    private fun recipient(toks: List<String>): Pair<String, Set<Int>>? {
        val k = toks.indexOfFirst { t ->
            !isMrStop(t) && DATIVE.any { t.endsWith(it) && t.length > it.length + 1 }
        }.let { if (it >= 0) it else toks.indexOfFirst { it == "ला" || it == "ना" } }
        if (k >= 0) {
            var start = if (toks[k] == "ला" || toks[k] == "ना") k - 1 else k
            if (start < 0) return null
            while (start - 1 >= 0 && !isMrStop(toks[start - 1]) && k - start < 2) start--
            val idx = (start..k).filter { toks[it] != "ला" && toks[it] != "ना" }.toSet()
            if (idx.isEmpty()) return null
            return idx.sorted().joinToString(" ") { toks[it] } to idx
        }
        val idx = toks.indices.filter { !isMrStop(toks[it]) }.take(3).toSet()
        if (idx.isEmpty()) return null
        return idx.sorted().joinToString(" ") { toks[it] } to idx
    }

    private fun mrMessage(s: String): Pair<String, String?>? {
        // "राहुलला सांग की मी उशिरा येतोय"
        Regex("""\s(?:की|कि)\s""").find(s)?.let { ki ->
            val who = recipient(s.substring(0, ki.range.first).split(" "))?.first ?: return null
            val message = s.substring(ki.range.last + 1)
                .replace(Regex("""\s*(?:असं|असे|अस)?\s*(?:सांग|पाठव|कळव)\S*$"""), "").trim()
            return who to message.ifBlank { null }
        }
        // "राहुलला मी उशिरा येतोय असं सांग"
        val marker = Regex("""^(.*?)\s(?:असं|असे|अस|म्हणून)\s+\S+.*$""").find(s)
        val core = marker?.groupValues?.get(1) ?: s
        val toks = core.split(" ").filter { it.isNotBlank() }
        val (who, idx) = recipient(toks) ?: return null
        val rest = toks.indices.filter { it !in idx }.map { toks[it] }
        val words = if (marker != null) rest.filter { !it.startsWith("whatsapp") } else rest.filter { !isMrStop(it) }
        return who to words.joinToString(" ").ifBlank { null }
    }

    private fun stripWords(s: String, words: Set<String>, startsWith: List<String> = emptyList()): String =
        s.split(" ").filter { w -> w.isNotBlank() && w !in words && startsWith.none { w.startsWith(it) } }.joinToString(" ").trim()

    private fun marathi(s: String): Command? {
        fun has(p: String) = Regex(p).containsMatchIn(s)
        val toks = s.split(" ").filter { it.isNotBlank() }
        val wa = "whatsapp" in s
        val off = has("बंद|ऑफ|\\boff\\b")

        if (Regex("^(थांब|थांबा|बस|राहू दे|राहूदे|रद्द कर|नको|cancel|stop)$").matches(s)) return Command.Stop
        if (has("मदत|काय (काय )?करू शकत")) return Command.Help
        if (has("मराठी(त|मध्ये| मध्ये)?\\s*(बोल|भाषा)")) return Command.SetLanguage("mr-IN")
        if (has("(इंग्रजी|इंग्लिश|english)(त|मध्ये| मध्ये)?\\s*(बोल|भाषा)")) return Command.SetLanguage("en-IN")
        if (has("किती वाजले|वेळ (काय|किती|सांग)|टाइम (काय|किती)")) return Command.Time
        if (has("तारीख|कोणता वार|काय वार|आज (कोणता )?दिवस")) return Command.Date
        if (has("बॅटरी|battery")) return Command.Battery
        if (has("स्क्रीन ?शॉट (घे|काढ)") && !has("पाठव|शेअर")) return Command.Global("screenshot")
        if (has("(फोन|स्क्रीन|मोबाईल|मोबाइल) लॉक")) return Command.Global("lock")
        if (Regex("^(मागे|मागे जा|बॅक|back)$").matches(s)) return Command.Global("back")
        if (has("^होम|होम स्क्रीन")) return Command.Global("home")
        if (has("नोटिफिकेशन")) return Command.Global("notifications")
        if (has("स्क्रीनवर काय|स्क्रीन वाच|वाचून दाखव|वाचून सांग")) return Command.ReadScreen
        if (has("टॉर्च|फ्लॅश")) return Command.Toggle("torch", !off)
        if (has("वाय ?-?फाय|wifi|wi-fi")) return Command.Toggle("wifi", !off)
        if (has("ब्लू ?टूथ|bluetooth")) return Command.Toggle("bluetooth", !off)
        if (has("हॉटस्पॉट")) return Command.Toggle("hotspot", !off)
        if (has("आवाज|व्हॉल्यूम|सायलेंट|व्हायब्रेट")) {
            return Command.Volume(
                when {
                    has("सायलेंट|व्हायब्रेट|आवाज बंद") -> "silent"
                    has("कमी|हळू") -> "down"
                    has("पूर्ण|फुल|जास्तीत जास्त") -> "max"
                    else -> "up"
                }
            )
        }
        if (has("अलार्म|उठव")) return parseTime(s)?.let { Command.Alarm(it.first, it.second) } ?: Command.ShowAlarms
        if (has("टायमर")) parseDuration(s)?.let { return Command.Timer(it) }
        if (has("(फोटो|सेल्फी) (काढ|घे)") && !has("पाठव|शेअर")) return Command.Camera
        if (has("रस्ता|मार्ग|नेव्हिगेट|नेव्हिगेशन")) {
            val place = stripWords(
                s, setOf("रस्ता", "मार्ग", "दाखव", "दाखवा", "नेव्हिगेट", "नेव्हिगेशन", "कर", "करा", "सांग", "जायचा",
                    "जाण्याचा", "जायचं", "चा", "ची", "ला", "कडे", "कसा", "कसे", "मला", "इथून", "पर्यंत", "चालू")
            ).split(" ").joinToString(" ") { Text.stripMarathiSuffix(it) }
            if (place.isNotBlank()) return Command.Navigate(place)
        }

        val sendVerb = has("पाठव|शेअर|फॉरवर्ड|सेंड|send|share|forward")
        val latestWord = has("शेवटच|नवीन|लेटेस्ट|आत्ताच|आताच")
        val thisWord = Regex("(^|\\s)(हा|ही|हे|हि|ह्या|this)(\\s|$)").containsMatchIn(s)
        if (sendVerb && thisWord && !latestWord && !has("नावाच")) return recipient(toks)?.let { Command.ShareThis(it.first) }
        if (sendVerb && isFileRef(s)) {
            val (who, idx) = recipient(toks) ?: return null
            return Command.ShareFile(who, fileQuery(toks.indices.filter { it !in idx }.joinToString(" ") { toks[it] }))
        }
        val callWord = has("फोन|कॉल|काॅल|call|डायल")
        if (callWord && !has("मेसेज|संदेश|message")) {
            val who = recipient(toks)?.first ?: return null
            val video = has("व्हिड[िी]ओ|video")
            return if (wa || video) Command.WaCall(who, video) else Command.Call(who)
        }
        if (has("मेसेज|मॅसेज|संदेश|message|msg|सांग|कळव|पाठव|लिहून|टेक्स्ट|एसएमएस|sms") || wa) {
            val sms = has("एसएमएस|\\bsms\\b")
            val (who, message) = mrMessage(s) ?: return null
            return if (sms) Command.Sms(who, message) else Command.WaMessage(who, message)
        }
        if (has("वाजव|प्ले|play") || (has("गाणं|गाणे|गाणी") && has("लाव"))) {
            val q = stripWords(s, setOf("वाजव", "वाजवा", "प्ले", "play", "लाव", "लावा", "कर", "करा", "वर", "youtube"), listOf("यूट्यूब", "युट्युब", "स्पॉटिफाय"))
            if (q.isNotBlank()) return Command.Play(q, if (has("स्पॉटिफाय|spotify")) "spotify" else null)
        }
        if (has("शोध|सर्च|search")) {
            val q = stripWords(s, setOf("शोध", "शोधा", "सर्च", "कर", "करा", "search", "google", "वर"), listOf("गूगल", "गुगल", "यूट्यूब", "युट्युब"))
            if (q.isNotBlank()) return Command.Search(q, if (has("यूट्यूब|युट्युब")) "youtube" else null)
        }
        if (has("उघड|ओपन|open|चालू कर|सुरू कर|सुरु कर|लाँच|चालू करा|सुरू करा")) {
            val name = stripWords(s, setOf("उघड", "उघडा", "ओपन", "open", "कर", "करा", "चालू", "सुरू", "सुरु", "लाँच", "ॲप", "अ‍ॅप", "app", "ऍप", "माझं", "माझा", "माझी"))
            if (name.isNotBlank()) return Command.OpenApp(name)
        }
        return null
    }
}
