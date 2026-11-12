package com.example.voiceagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Settings and setup. The agent itself runs in [AgentService]. */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var swEnabled: MaterialSwitch
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swEnabled = findViewById(R.id.swEnabled)
        swEnabled.setOnCheckedChangeListener { sw, on ->
            if (on == Prefs.enabled(this)) return@setOnCheckedChangeListener
            if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
                sw.isChecked = false
                askPermissions()
                return@setOnCheckedChangeListener
            }
            Prefs.setEnabled(this, on)
            refresh()
        }

        findViewById<MaterialButtonToggleGroup>(R.id.langGroup).apply {
            check(if (Prefs.marathi(this@MainActivity)) R.id.langMr else R.id.langEn)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) Prefs.setLang(this@MainActivity, if (id == R.id.langMr) "mr-IN" else "en-IN")
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.talkGroup).apply {
            check(if (Prefs.talkBack(this@MainActivity) == "full") R.id.talkFull else R.id.talkMinimal)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) Prefs.setTalkBack(this@MainActivity, if (id == R.id.talkFull) "full" else "minimal")
            }
        }

        findViewById<TextInputEditText>(R.id.etWake).apply {
            setText(Prefs.wakeWord(this@MainActivity))
            addTextChangedListener(watcher { Prefs.setWakeWord(this@MainActivity, it) })
        }
        findViewById<TextInputEditText>(R.id.etCc).apply {
            setText(Prefs.countryCode(this@MainActivity))
            addTextChangedListener(watcher { Prefs.setCountryCode(this@MainActivity, it) })
        }
        findViewById<MaterialSwitch>(R.id.swScreenOff).apply {
            isChecked = Prefs.listenScreenOff(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setListenScreenOff(this@MainActivity, on) }
        }
        findViewById<MaterialSwitch>(R.id.swAutoSend).apply {
            visibility = if (BuildConfig.FULL) View.VISIBLE else View.GONE
            isChecked = Prefs.autoSend(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAutoSend(this@MainActivity, on) }
        }

        findViewById<Button>(R.id.btnBrain).setOnClickListener { onBrainButton() }
        findViewById<Button>(R.id.btnBrainRemove).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove the brain?")
                .setMessage("Frees 2.6 GB. Voice Agent will only understand fixed phrases until you download it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ -> ModelDownload.delete(this); refresh() }
                .show()
        }

        val tilTry = findViewById<TextInputLayout>(R.id.tilTry)
        val etTry = findViewById<TextInputEditText>(R.id.etTry)
        val run = {
            val text = etTry.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                AgentService.start(this)
                AgentService.whenReady { it.runTyped(text) }
                etTry.setText("")
            }
        }
        tilTry.setEndIconOnClickListener { run() }
        etTry.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { run(); true } else false }

        findViewById<Button>(R.id.btnExamples).setOnClickListener { showExamples() }
        findViewById<TextView>(R.id.footer).text =
            "Understanding runs privately on this phone. Speech is recognised by Google's speech service."
    }

    override fun onStart() { super.onStart(); Ui.visible++ }
    override fun onStop() { super.onStop(); Ui.visible-- }

    override fun onResume() {
        super.onResume()
        if (AgentService.instance == null && granted(Manifest.permission.RECORD_AUDIO)) AgentService.start(this)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (granted(Manifest.permission.RECORD_AUDIO)) AgentService.start(this)
        refresh()
    }

    // ------------------------------------------------------------------ helpers

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange(s.toString())
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun askPermissions() = requestPermissions(
        arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS
        ), 1
    )

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_000_000_000.0)

    // ------------------------------------------------------------------ brain

    private fun onBrainButton() {
        when {
            Brain.installed(this) -> {}
            Prefs.downloadId(this) >= 0 -> { ModelDownload.cancel(this); refresh() }
            !ModelDownload.enoughSpace(this) -> MaterialAlertDialogBuilder(this)
                .setTitle("Not enough space")
                .setMessage("The brain needs about 2.9 GB free. You have ${gb(ModelDownload.freeSpace(this))} GB.")
                .setPositiveButton("OK", null).show()
            getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered -> MaterialAlertDialogBuilder(this)
                .setTitle("Download on mobile data?")
                .setMessage("The brain is 2.6 GB. It's best to download it on Wi-Fi.")
                .setNegativeButton("Wait for Wi-Fi") { _, _ -> ModelDownload.start(this, false); refresh() }
                .setPositiveButton("Use mobile data") { _, _ -> ModelDownload.start(this, true); refresh() }
                .show()
            else -> { ModelDownload.start(this, false); refresh() }
        }
    }

    private fun refreshBrain() {
        val text = findViewById<TextView>(R.id.brainText)
        val bar = findViewById<LinearProgressIndicator>(R.id.brainProgress)
        val btn = findViewById<MaterialButton>(R.id.btnBrain)
        val remove = findViewById<Button>(R.id.btnBrainRemove)
        val p = ModelDownload.progress(this)
        remove.visibility = View.GONE
        when {
            Brain.installed(this) -> {
                bar.visibility = View.GONE
                btn.visibility = View.GONE
                remove.visibility = View.VISIBLE
                text.text = when {
                    Brain.ready -> "Gemma 4 is ready. It understands natural speech in English, Marathi or a mix, " +
                        "and can do several things from one sentence. Runs privately on this phone (${Brain.backendName})."
                    Brain.loading -> "Gemma 4 is loading…"
                    else -> "Gemma 4 is installed. It loads automatically when needed."
                }
            }
            p != null && p.running -> {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = p.done <= 0
                bar.max = 1000
                bar.setProgressCompat(((p.done * 1000) / p.total).toInt(), true)
                text.text = "Downloading Gemma 4 · ${gb(p.done)} of ${gb(p.total)} GB. You can leave this screen; it keeps going."
                btn.visibility = View.VISIBLE
                btn.text = "Cancel"
                btn.icon = null
            }
            else -> {
                bar.visibility = View.GONE
                btn.visibility = View.VISIBLE
                btn.text = "Download · 2.6 GB"
                btn.setIconResource(R.drawable.ic_download)
                text.text = (if (p?.failed == true) "The download stopped. Try again.\n\n" else "") +
                    "Add Google's Gemma 4 so Voice Agent understands you the way you naturally speak, in English, " +
                    "Marathi or a mix, and handles several requests at once. Free, no account, works offline. " +
                    "Without it, only fixed phrases work."
            }
        }
    }

    // ------------------------------------------------------------------ status & setup

    private data class Step(val done: Boolean, val title: String, val sub: String, val action: String, val go: () -> Unit)

    private fun refresh() {
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val running = AgentService.instance != null
        val assistant = Assistant.isDefault(this)
        val wake = Prefs.wakeWord(this)
        if (swEnabled.isChecked != Prefs.enabled(this)) swEnabled.isChecked = Prefs.enabled(this)

        val (title, sub) = when {
            !mic -> "Let's get started" to "Allow the microphone below."
            !running -> "Starting…" to ""
            Prefs.enabled(this) -> "Listening for “$wake”" to "Say “$wake, call Mom” any time."
            else -> "Ready" to (if (assistant) "Hold the power button to talk." else "Use the Voice Agent tile in Quick Settings to talk.")
        }
        findViewById<TextView>(R.id.statusTitle).text = title
        findViewById<TextView>(R.id.statusSub).apply { text = sub; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
        findViewById<TextView>(R.id.enabledSub).text =
            if (Prefs.enabled(this)) "On. Turn off to stop listening in the background." else "Off. Turn on to talk without tapping anything."

        val steps = buildList {
            add(Step(mic && granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE),
                "Microphone, contacts & calls", "To hear you and reach the right person.", "Allow") { askPermissions() })
            add(Step(FileFinder.hasAccess(), "Files", "To find and send files, photos and PDFs.", "Allow") {
                open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            })
            add(Step(assistant, "Make it your assistant", "Recommended. Hold the power button to talk, and “$wake, …” opens apps directly.", "Set") {
                open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                Toast.makeText(this@MainActivity, "Digital assistant app → Voice Agent", Toast.LENGTH_LONG).show()
            })
            if (BuildConfig.FULL) add(Step(Screen.current != null, "Screen control", "Lets it press Send and switches for you.", "Turn on") {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        renderSetup(steps)
        refreshBrain()
    }

    private fun renderSetup(steps: List<Step>) {
        val card = findViewById<View>(R.id.setupCard)
        val rows = findViewById<LinearLayout>(R.id.setupRows)
        card.visibility = if (steps.all { it.done }) View.GONE else View.VISIBLE
        rows.removeAllViews()
        val d = resources.displayMetrics.density
        for (s in steps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (s.done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            }, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = s.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            texts.addView(TextView(this).apply {
                text = s.sub
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                alpha = 0.75f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (16 * d).toInt()
            })
            if (!s.done) {
                row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = s.action
                    setOnClickListener { s.go() }
                })
            }
            rows.addView(row)
        }
    }

    // ------------------------------------------------------------------ examples

    private fun showExamples() {
        val d = resources.displayMetrics.density
        val wake = Prefs.wakeWord(this)
        val sections = listOf(
            "Talk naturally" to listOf(
                "$wake, tell Priya I'm stuck in traffic and will be 10 minutes late",
                "$wake, send the hotel voucher PDF to Rahul and then call him",
                "$wake, put on some Arijit Singh and turn the volume up",
                "$wake, remind me in 20 minutes",
                "$wake, mom ko call karo",
            ),
            "मराठीत" to listOf(
                "एजंट, आईला फोन लाव",
                "एजंट, प्रियाला सांग की आज मीटिंग पाच वाजता आहे",
                "एजंट, शेवटचा फोटो राहुलला पाठव आणि सकाळी सहाचा अलार्म लाव",
                "एजंट, बाबांना व्हिडिओ कॉल कर",
            ),
            "Quick" to listOf(
                "Torch on · Volume down · What time is it · Battery",
                "WhatsApp video call Dad · Open YouTube · Navigate to Pune station",
                "Speak Marathi · Stop listening",
            ),
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (32 * d).toInt())
        }
        box.addView(TextView(this).apply {
            text = "What can I say?"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        })
        for ((head, lines) in sections) {
            box.addView(TextView(this).apply {
                text = head
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding(0, (20 * d).toInt(), 0, (6 * d).toInt())
            })
            for (l in lines) box.addView(TextView(this).apply {
                text = "“$l”"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                setTextIsSelectable(true)
            })
        }
        BottomSheetDialog(this).apply {
            setContentView(androidx.core.widget.NestedScrollView(this@MainActivity).apply { addView(box) })
            show()
        }
    }
}
