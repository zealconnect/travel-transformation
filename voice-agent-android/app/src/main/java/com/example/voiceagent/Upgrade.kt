package com.example.voiceagent

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Phone-only upgrade to the full version (the one that presses Send for you).
 *
 * Play Protect blocks apps installed from a browser if they use Accessibility, and Android
 * won't let you switch it on for them. Shizuku (free, Play Store) grants this app the same rights
 * a computer cable would, once. With them we install the full version over this one (same app id,
 * so settings and the downloaded brain are kept) and switch its screen control on. After that
 * Shizuku is no longer needed.
 */
object Upgrade {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SERVICE = "com.example.voiceagent/com.example.voiceagent.AgentAccessibilityService"

    enum class Stage { NO_SHIZUKU, NOT_RUNNING, NEEDS_PERMISSION, READY }

    fun shizukuInstalled(ctx: Context) =
        try { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }

    fun stage(ctx: Context): Stage = when {
        !shizukuInstalled(ctx) -> Stage.NO_SHIZUKU
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Stage.NOT_RUNNING
        runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) != PackageManager.PERMISSION_GRANTED -> Stage.NEEDS_PERMISSION
        else -> Stage.READY
    }

    fun requestPermission() = runCatching { Shizuku.requestPermission(7) }

    /** Run a shell command with Shizuku's rights. The command API is hidden, so call it by reflection. */
    private fun shell(cmd: String): Int {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return p.waitFor()
    }

    /**
     * Start the one-time upgrade. Installs the bundled full APK over this app and turns on its
     * screen control, then reopens the app. Because installing replaces (and closes) this app,
     * the script runs detached.
     */
    suspend fun start(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (stage(ctx) != Stage.READY) return@withContext false
        val dir = (ctx.getExternalFilesDir("upgrade") ?: return@withContext false).apply { mkdirs() }
        val apk = File(dir, "full.apk")
        ctx.assets.open("upgrade/full.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        val script = File(dir, "upgrade.sh")
        script.writeText(buildScript(ctx.packageName, apk.path))
        shell("(setsid sh '${script.path}' || sh '${script.path}') </dev/null >/data/local/tmp/voiceagent-upgrade.log 2>&1 &") == 0
    }

    /**
     * The upgrade script: install the full build over this app, then enable its accessibility
     * service (appending to any already enabled) and file access, and reopen the app.
     */
    private fun buildScript(pkg: String, apkPath: String): String = """
        tmp=/data/local/tmp/voiceagent-full.apk
        cp '$apkPath' ${'$'}tmp || exit 1
        pm install -r -g ${'$'}tmp || exit 2
        rm -f ${'$'}tmp
        svc=$SERVICE
        cur=${'$'}(settings get secure enabled_accessibility_services)
        case "${'$'}cur" in
          *"${'$'}svc"*) ;;
          null|"") settings put secure enabled_accessibility_services "${'$'}svc" ;;
          *) settings put secure enabled_accessibility_services "${'$'}cur:${'$'}svc" ;;
        esac
        settings put secure accessibility_enabled 1
        appops set $pkg MANAGE_EXTERNAL_STORAGE allow
        am start -n $pkg/$pkg.MainActivity
        echo done
    """.trimIndent()
}
