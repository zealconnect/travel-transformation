package com.example.voiceagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Optional free AI that runs entirely on the phone (Google MediaPipe + a Gemma model file).
 * No account, no API key, no internet. Used only for requests the fixed commands don't cover.
 */
class LocalBrain private constructor(private val llm: LlmInference, private val family: String, val maxTokens: Int) {

    companion object {
        @Volatile private var cached: LocalBrain? = null
        @Volatile private var cachedPath: String? = null
        private val loadLock = Mutex()

        suspend fun get(ctx: Context): LocalBrain? = loadLock.withLock {
            withContext(Dispatchers.IO) {
                val path = Prefs.modelPath(ctx) ?: return@withContext null
                cached?.takeIf { cachedPath == path }?.let { return@withContext it }
                close()
                for (tokens in listOf(4096, 2048, 1280)) {
                    try {
                        val opts = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(path)
                            .setMaxTokens(tokens)
                            .build()
                        val brain = LocalBrain(LlmInference.createFromOptions(ctx.applicationContext, opts), familyOf(path), tokens)
                        cached = brain
                        cachedPath = path
                        return@withContext brain
                    } catch (_: Throwable) {
                        // Try a smaller context size; some model files are built for less.
                    }
                }
                null
            }
        }

        fun close() {
            try { cached?.llm?.close() } catch (_: Throwable) {}
            cached = null
            cachedPath = null
        }

        private fun familyOf(path: String): String {
            val n = File(path).name.lowercase()
            return when {
                "gemma" in n -> "gemma"
                "qwen" in n || "smol" in n -> "chatml"
                "llama" in n -> "llama"
                "phi" in n -> "phi"
                else -> "gemma"
            }
        }

        /** First {...} block in the model's reply. */
        fun firstJson(s: String): JSONObject? {
            val start = s.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until s.length) {
                val c = s[i]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
            return null
        }
    }

    private val lock = Mutex()

    /** How much screen text fits in one question for this model. */
    val screenChars: Int get() = when {
        maxTokens >= 4096 -> 6000
        maxTokens >= 2048 -> 2800
        else -> 1200
    }

    private fun wrap(p: String) = when (family) {
        "chatml" -> "<|im_start|>user\n$p<|im_end|>\n<|im_start|>assistant\n"
        "llama" -> "<|start_header_id|>user<|end_header_id|>\n\n$p<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        "phi" -> "<|user|>\n$p<|end|>\n<|assistant|>\n"
        else -> "<start_of_turn>user\n$p<end_of_turn>\n<start_of_turn>model\n"
    }

    suspend fun complete(prompt: String): String = lock.withLock {
        withContext(Dispatchers.IO) { llm.generateResponse(wrap(prompt)) }
    }
}
