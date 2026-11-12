package com.example.voiceagent

import java.text.Normalizer
import kotlin.math.max

/**
 * Text helpers shared by the parser, contacts, apps and files:
 *  - turn Marathi (Devanagari) into Latin letters, so "राहुल" can match a contact saved as "Rahul";
 *  - fuzzy "sounds alike" scoring for names.
 */
object Text {

    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ii", 'उ' to "u", 'ऊ' to "uu", 'ऋ' to "ru",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऍ' to "e", 'ऑ' to "o", 'ॲ' to "a"
    )
    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ii", 'ु' to "u", 'ू' to "uu", 'ृ' to "ru",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o"
    )
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'फ' to "f", 'ड' to "r", 'ढ' to "rh")
    private const val VIRAMA = '्'
    private const val NUKTA_SIGN = '़'

    fun hasDevanagari(s: String) = s.any { it in 'ऀ'..'ॿ' }

    private fun isDevLetter(c: Char) = c in 'ऀ'..'ॿ' && c !in '०'..'९'

    /** Devanagari digits → 0-9. */
    fun asciiDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '०'..'९') '0' + (c - '०') else c)
    }

    /** Rough Devanagari → Latin ("राहुल" → "raahul"). Latin text passes through unchanged. */
    fun translit(s: String): String {
        if (!hasDevanagari(s)) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cons = CONSONANTS[c]
            if (cons != null) {
                var j = i + 1
                var base = cons
                if (j < s.length && s[j] == NUKTA_SIGN) { base = NUKTA[c] ?: cons; j++ }
                sb.append(base)
                when {
                    j < s.length && s[j] == VIRAMA -> j++
                    j < s.length && MATRAS.containsKey(s[j]) -> { sb.append(MATRAS[s[j]]); j++ }
                    // Inherent "a", dropped at the end of a word (राहुल → rahul, not rahula).
                    j < s.length && isDevLetter(s[j]) -> sb.append('a')
                    else -> {}
                }
                i = j
                continue
            }
            when {
                VOWELS.containsKey(c) -> sb.append(VOWELS[c])
                MATRAS.containsKey(c) -> sb.append(MATRAS[c])
                c == 'ं' || c == 'ँ' -> sb.append('n')
                c == 'ः' -> sb.append('h')
                c in '०'..'९' -> sb.append('0' + (c - '०'))
                c == '‍' || c == '‌' || c == NUKTA_SIGN || c == VIRAMA -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Lower-case Latin letters/digits only, accents and long vowels folded. */
    fun fold(s: String): String {
        var t = translit(s.lowercase())
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace(Regex("[^a-z0-9 ]"), " ")
        t = t.replace("aa", "a").replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Consonant outline used for "sounds alike" checks: "Priya"/"प्रिया" → "pry", "YouTube"/"यूट्यूब" → "ytb". */
    fun skeleton(s: String): String {
        var t = fold(s).replace(" ", "")
        t = t.replace("chh", "C").replace("ch", "C").replace("sh", "s").replace("ph", "f")
            .replace("bh", "b").replace("kh", "k").replace("gh", "g").replace("jh", "j")
            .replace("th", "t").replace("dh", "d").replace("ck", "k").replace("q", "k")
            .replace("x", "ks").replace("w", "v").replace("z", "j").replace("c", "k").replace("C", "c")
        val vowels = "aeiou"
        val sb = StringBuilder()
        for ((i, ch) in t.withIndex()) {
            if (ch in vowels || ch == 'h') continue
            if (ch == 'y' && i > 0 && t[i - 1] !in vowels) continue
            if (sb.isNotEmpty() && sb.last() == ch) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    fun sim(a: String, b: String): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else 1.0 - lev(a, b).toDouble() / max(a.length, b.length)

    private fun pairScore(x: String, y: String): Double {
        if (x == y) return 1.0
        val sx = skeleton(x); val sy = skeleton(y)
        if (sx.length < 2 || sy.length < 2) return sim(x, y)
        return 0.55 * sim(sx, sy) + 0.45 * sim(x, y)
    }

    /** 0..1: how alike two whole phrases sound (no partial-word credit). */
    fun phraseScore(a: String, b: String): Double = pairScore(fold(a), fold(b))

    /** 0..1: how well a spoken name matches a saved name (whole name, or one/two of its words). */
    fun nameScore(spoken: String, saved: String): Double {
        val q = fold(spoken); val c = fold(saved)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        var best = pairScore(q, c)
        val words = c.split(" ")
        if (words.size > 1) {
            for (w in words) best = max(best, pairScore(q, w) * 0.97)
            for (k in 0 until words.size - 1) best = max(best, pairScore(q, words[k] + " " + words[k + 1]) * 0.98)
        }
        return best
    }

    private val MR_SUFFIXES = listOf("लाही", "कडे", "ला", "ना", "शी")

    /** "राहुलला" → "राहुल" (Marathi "to Rahul"). */
    fun stripMarathiSuffix(w: String): String {
        for (s in MR_SUFFIXES) if (w.endsWith(s) && w.length > s.length + 1) return w.dropLast(s.length)
        return w
    }
}

/** Spotting the wake word ("Agent, call Mom") at the start of what was heard. */
object WakeWord {

    private val LEAD_INS = setOf("hey", "hi", "ok", "okay", "hello", "o", "e", "are", "arey", "aho", "hai", "a")
    private val AGENT_MARATHI = listOf("एजंट", "एजन्ट", "एजेंट", "एजंट्स")

    private fun matches(heard: String, wake: String): Boolean {
        val variants = mutableListOf(wake)
        if (Text.fold(wake) == "agent") variants += AGENT_MARATHI
        val h = Text.fold(heard)
        return variants.any { v -> h == Text.fold(v) || Text.phraseScore(heard, v) >= 0.84 }
    }

    /**
     * If [heard] starts with the wake word, returns what comes after it ("" if nothing),
     * otherwise null (the sentence wasn't meant for the agent).
     */
    fun extract(heard: String, wake: String): String? {
        val words = heard.trim().split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', '।') }.filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size - 1 && Text.fold(words[i]) in LEAD_INS) i++
        val wakeLen = wake.trim().split(Regex("\\s+")).size
        for (n in maxOf(1, wakeLen - 1)..(wakeLen + 1)) {   // shortest first, so the command isn't swallowed
            if (i + n > words.size) continue
            if (matches(words.subList(i, i + n).joinToString(" "), wake)) return words.drop(i + n).joinToString(" ")
        }
        return null
    }
}
