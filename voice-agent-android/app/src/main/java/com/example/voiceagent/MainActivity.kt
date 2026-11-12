package com.example.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** The setup and settings screen. The agent itself lives in [AgentAccessibilityService]. */
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    companion object { private const val PICK_MODEL = 7 }

    private lateinit var status: TextView
    private lateinit var tvModel: TextView
    private var copying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        tvModel = findViewById(R.id.tvModel)

        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 1)
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnRestricted).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        val swEnabled = findViewById<Switch>(R.id.swEnabled)
        swEnabled.isChecked = Prefs.enabled(this)
        swEnabled.setOnCheckedChangeListener { _, on -> Prefs.setEnabled(this, on); refresh() }

        val lang = findViewById<RadioGroup>(R.id.lang)
        lang.check(if (Prefs.marathi(this)) R.id.langMr else R.id.langEn)
        lang.setOnCheckedChangeListener { _, id -> Prefs.setLang(this, if (id == R.id.langMr) "mr-IN" else "en-IN") }

        val swAuto = findViewById<Switch>(R.id.swAutoSend)
        swAuto.isChecked = Prefs.autoSend(this)
        swAuto.setOnCheckedChangeListener { _, on -> Prefs.setAutoSend(this, on) }

        val etCc = findViewById<EditText>(R.id.etCc)
        etCc.setText(Prefs.countryCode(this))
        etCc.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { Prefs.setCountryCode(this@MainActivity, s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<Button>(R.id.btnModel).setOnClickListener {
            if (copying) return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MODEL)
        }
        findViewById<Button>(R.id.btnRemoveModel).setOnClickListener {
            LocalBrain.close()
            Prefs.modelPath(this)?.let { File(it).delete() }
            Prefs.setModelPath(this, null)
            refresh()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val text = findViewById<EditText>(R.id.etTest).text.toString().trim()
            val svc = AgentAccessibilityService.instance
            when {
                text.isEmpty() -> {}
                svc == null -> toast("Turn on Voice Agent in Accessibility first (step 4).")
                else -> { svc.runTyped(text); moveTaskToBack(true) }
            }
        }

        findViewById<TextView>(R.id.tvHelp).text = HELP
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val service = AgentAccessibilityService.instance != null
        status.text = buildString {
            appendLine("${mark(service)} Accessibility service ${if (service) "running" else "off (step 4)"}")
            appendLine("${mark(granted(Manifest.permission.RECORD_AUDIO))} Microphone")
            appendLine("${mark(granted(Manifest.permission.READ_CONTACTS))} Contacts")
            appendLine("${mark(granted(Manifest.permission.CALL_PHONE))} Phone calls")
            appendLine("${mark(Environment.isExternalStorageManager())} Files access")
            append("${mark(Prefs.modelPath(this@MainActivity) != null)} Offline AI model (optional)")
        }
        val model = Prefs.modelPath(this)
        tvModel.text = when {
            copying -> "Copying model… this can take a minute."
            model != null -> "Installed: ${File(model).name} (${File(model).length() / 1_000_000} MB)"
            else -> "No model added. Everyday commands still work."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) return
        var name = "model.task"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        if (!name.endsWith(".task") && !name.endsWith(".litertlm") && !name.endsWith(".bin")) {
            toast("Please choose a .task model file.")
            return
        }
        copying = true
        refresh()
        Thread {
            val dir = File(filesDir, "models").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, name)
            val ok = try {
                contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
                true
            } catch (e: Exception) {
                dest.delete(); false
            }
            runOnUiThread {
                copying = false
                LocalBrain.close()
                if (ok) { Prefs.setModelPath(this, dest.absolutePath); toast("Model added.") } else toast("Couldn't copy that file.")
                refresh()
            }
        }.start()
    }

    private val HELP = """
Tap the floating 🎤 button and speak. Tap it again to stop.
• Double-tap 🎤: turn the agent on/off
• Long-press 🎤: open this screen
• Drag 🎤: move it anywhere
• Quick Settings tile “Voice Agent”: on/off

English examples
• Call Mom / Call 98765 43210
• Send hello to Rahul on WhatsApp
• Tell Priya I'm running late
• Message Amit on WhatsApp (it asks what to say)
• Send the latest PDF to Rahul on WhatsApp
• Share the last photo with Priya
• Send the file named budget to Amit
• Send this to Mom (uses the Share button on screen)
• WhatsApp video call Dad
• Send an SMS to Rahul saying I'll call later
• Open YouTube · Play Arijit songs · Search hotels in Goa
• Navigate to Pune railway station
• Turn on the torch · Turn off Wi-Fi · Volume up · Silent
• Set an alarm for 6:30 AM · Set a timer for 10 minutes
• What's the time · Battery · Read the screen
• Take a screenshot · Go back · Go home · Lock the phone
• Speak Marathi · Help · Stop

मराठी उदाहरणे
• आईला फोन लाव
• राहुलला व्हॉट्सॲपवर मेसेज पाठव की मी पोहोचलो
• प्रियाला सांग की मी उशिरा येतोय
• शेवटची पीडीएफ अमितला व्हॉट्सॲपवर पाठव
• हा फोटो आईला पाठव
• बाबांना व्हिडिओ कॉल कर
• यूट्यूब उघड · यूट्यूबवर मराठी गाणी लाव
• टॉर्च चालू कर · आवाज कमी कर
• सकाळी साडे सहाचा अलार्म लाव · १० मिनिटांचा टायमर लाव
• किती वाजले · बॅटरी किती आहे · स्क्रीनवर काय आहे वाचून दाखव
• इंग्रजीत बोल · मदत · थांब
""".trim()
}
