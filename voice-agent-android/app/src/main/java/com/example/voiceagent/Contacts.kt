package com.example.voiceagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

data class Person(val name: String, val numbers: List<String>)
data class Scored(val person: Person, val score: Double)

/** Finds people in your phone's contacts by a spoken name, in English or Marathi. */
object Contacts {

    /** Words that mean the same person, so "call Aai" finds a contact saved as "Mom" and vice versa. */
    private val ALIASES = listOf(
        listOf("mom", "mother", "mummy", "mumma", "mommy", "maa", "aai", "aayi", "आई", "मम्मी", "ममी"),
        listOf("dad", "father", "papa", "pappa", "daddy", "baba", "pitaji", "बाबा", "पप्पा", "वडील", "डॅडी"),
        listOf("brother", "bro", "bhau", "dada", "भाऊ", "भावा", "दादा"),
        listOf("sister", "sis", "tai", "didi", "ताई", "दीदी", "बहीण", "बहिणी"),
        listOf("wife", "bayko", "बायको"),
        listOf("husband", "navra", "नवरा"),
        listOf("grandma", "grandmother", "aaji", "आजी", "nani", "dadi"),
        listOf("grandpa", "grandfather", "aajoba", "आजोबा", "nana"),
    ).map { group -> group.map { Text.fold(it) }.toSet() }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(10)

    fun all(ctx: Context): List<Person> {
        if (!hasPermission(ctx)) return emptyList()
        val byId = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
            null, null,
            Phone.IS_SUPER_PRIMARY + " DESC, " + Phone.IS_PRIMARY + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val num = c.getString(2) ?: continue
                val entry = byId.getOrPut(id) { name to mutableListOf() }
                if (entry.second.none { digits(it) == digits(num) }) entry.second.add(num)
            }
        }
        return byId.values.map { Person(it.first, it.second) }
    }

    /** Spoken forms to try, each with a weight (full phrase counts more than a single word of it). */
    private fun variants(spoken: String): List<Pair<String, Double>> {
        val words = spoken.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.removeSuffix("'s") }
            .dropWhile { it in setOf("my", "the", "माझ्या", "माझी", "माझा") }
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Double>()
        fun add(s: String, w: Double) { if (s.isNotBlank() && (out[s] ?: 0.0) < w) out[s] = w }
        val stripped = words.map { Text.stripMarathiSuffix(it) }
        add(words.joinToString(" "), 1.0)
        add(stripped.joinToString(" "), 1.0)
        // The name may be only the last word or two ("हॅलो राहुलला" → "राहुल").
        if (stripped.size > 1) {
            add(stripped.takeLast(1).joinToString(" "), 0.9)
            add(stripped.takeLast(2).joinToString(" "), 0.92)
        }
        for ((v, w) in out.toList()) {
            val f = Text.fold(v)
            ALIASES.firstOrNull { f in it }?.forEach { add(it, w) }
        }
        return out.toList()
    }

    /** Best matches first; only reasonably close ones. */
    fun search(ctx: Context, spoken: String): List<Scored> {
        val vs = variants(spoken)
        if (vs.isEmpty()) return emptyList()
        return all(ctx).asSequence()
            .map { p -> Scored(p, vs.maxOf { (q, w) -> Text.nameScore(q, p.name) * w }) }
            .filter { it.score >= 0.62 }
            .sortedByDescending { it.score }
            .take(5)
            .toList()
    }

    /** "rahul i'm running late" → (Rahul, "i'm running late"). */
    fun splitLeadingName(ctx: Context, text: String): Pair<Scored, String>? {
        val words = text.trim().split(Regex("\\s+"))
        val people = all(ctx)
        if (people.isEmpty()) return null
        for (n in minOf(3, words.size) downTo 1) {
            val name = words.take(n).joinToString(" ")
            val vs = variants(name).filter { it.second >= 1.0 }
            val best = people.map { p -> Scored(p, vs.maxOfOrNull { (q, _) -> Text.nameScore(q, p.name) } ?: 0.0) }
                .maxByOrNull { it.score } ?: continue
            if (best.score >= 0.82) return best to words.drop(n).joinToString(" ")
        }
        return null
    }
}
