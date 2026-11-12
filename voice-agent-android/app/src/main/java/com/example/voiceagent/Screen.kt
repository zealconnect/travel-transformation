package com.example.voiceagent

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Seeing and operating other apps' screens. Only the "full" build provides this
 * (through an Accessibility Service); in the default build [current] stays null and the
 * agent asks you to do the final tap yourself (for example WhatsApp's Send button).
 */
interface Screen {
    fun dumpScreen(maxChars: Int): String
    fun labelOf(id: Int): String
    suspend fun tap(id: Int): String
    fun typeText(id: Int, text: String): String
    fun pressEnter(id: Int): String
    suspend fun scroll(direction: String, id: Int?): String
    fun press(button: String): String
    fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo?
    suspend fun waitForNode(timeoutMs: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo?
    suspend fun clickNode(n: AccessibilityNodeInfo): Boolean
    fun setText(n: AccessibilityNodeInfo, text: String): Boolean
    fun visibleText(limit: Int): String

    companion object {
        @Volatile var current: Screen? = null
    }
}
