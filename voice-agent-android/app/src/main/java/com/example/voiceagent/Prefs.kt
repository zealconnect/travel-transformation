package com.example.voiceagent

import android.content.Context

/** Settings you choose on the app screen. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("voice_agent", Context.MODE_PRIVATE)

    /**
     * Enabled = always listening: say the wake word ("Agent, call Mom") any time, no tap needed.
     * Disabled = not listening in the background; tapping 🎤 still works for a single command.
     */
    fun enabled(c: Context) = sp(c).getBoolean("hands_free", false)
    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("hands_free", on).apply()
        AgentService.instance?.onEnabledChanged(on)
        AgentTileService.refresh(c)
    }

    /** The name you call it by when it's always listening. */
    fun wakeWord(c: Context): String = sp(c).getString("wake", "Agent")?.trim()?.ifEmpty { "Agent" } ?: "Agent"
    fun setWakeWord(c: Context, w: String) = sp(c).edit().putString("wake", w.trim()).apply()

    /** "minimal": speak only answers, questions and problems. "full": also confirm each action. */
    fun talkBack(c: Context): String = sp(c).getString("talkback", "minimal") ?: "minimal"
    fun setTalkBack(c: Context, v: String) = sp(c).edit().putString("talkback", v).apply()

    /** Keep listening while the screen is off (uses more battery). */
    fun listenScreenOff(c: Context) = sp(c).getBoolean("screen_off", false)
    fun setListenScreenOff(c: Context, on: Boolean) = sp(c).edit().putBoolean("screen_off", on).apply()

    /** "en-IN" or "mr-IN": the language it listens and replies in. */
    fun lang(c: Context): String = sp(c).getString("lang", "en-IN") ?: "en-IN"
    fun setLang(c: Context, tag: String) = sp(c).edit().putString("lang", tag).apply()
    fun marathi(c: Context) = lang(c).startsWith("mr")

    /** Added in front of 10-digit numbers for WhatsApp. */
    fun countryCode(c: Context): String = sp(c).getString("cc", "91")?.filter { it.isDigit() }?.ifEmpty { "91" } ?: "91"
    fun setCountryCode(c: Context, cc: String) = sp(c).edit().putString("cc", cc).apply()

    /** Press WhatsApp/SMS "Send" automatically (otherwise stop with the message ready). */
    fun autoSend(c: Context) = sp(c).getBoolean("auto_send", true)
    fun setAutoSend(c: Context, on: Boolean) = sp(c).edit().putBoolean("auto_send", on).apply()

    /** Id of a running brain download (DownloadManager), or -1. */
    fun downloadId(c: Context): Long = sp(c).getLong("dl", -1)
    fun setDownloadId(c: Context, id: Long) = sp(c).edit().putLong("dl", id).apply()
}

/** Pick the English or Marathi sentence, matching the chosen language. */
fun Context.tr(en: String, mr: String) = if (Prefs.marathi(this)) mr else en
