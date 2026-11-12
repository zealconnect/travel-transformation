package com.example.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    private fun x(s: String, w: String = "Agent") = WakeWord.extract(s, w)

    @Test fun findsWakeWord() {
        assertEquals("call mom", x("Agent call mom"))
        assertEquals("call mom", x("agent, call mom"))
        assertEquals("call mom", x("hey agent call mom"))
        assertEquals("open YouTube", x("OK Agent open YouTube"))
        assertEquals("", x("agent"))
        assertEquals("आईला फोन लाव", x("एजंट आईला फोन लाव"))
        assertEquals("आईला फोन लाव", x("अरे एजंट आईला फोन लाव"))
        assertEquals("call dad", x("Jarvis call dad", "Jarvis"))
        assertEquals("call dad", x("hey voice agent call dad", "Voice Agent"))
    }

    @Test fun ignoresOtherSpeech() {
        assertNull(x("call mom"))
        assertNull(x("I spoke to the agent yesterday"))
        assertNull(x("a gentleman called"))
        assertNull(x("urgent meeting at five"))
        assertNull(x("आईला फोन लाव"))
    }
}
