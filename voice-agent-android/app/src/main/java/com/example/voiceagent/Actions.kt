package com.example.voiceagent

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Carries out a [Command] on the phone. */
class Actions(private val svc: AgentAccessibilityService, private val voice: Voice) {

    private data class Target(val name: String, val number: String)

    private fun tr(en: String, mr: String) = svc.tr(en, mr)
    private suspend fun say(en: String, mr: String) = voice.speak(tr(en, mr))
    private fun start(i: Intent): Boolean = try {
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    suspend fun execute(cmd: Command) {
        when (cmd) {
            is Command.Call -> phoneCall(cmd.who)
            is Command.WaMessage -> resolve(cmd.who)?.let { waMessage(it, cmd.message) }
            is Command.WaUnsplit -> unsplit(cmd)
            is Command.WaCall -> waCall(cmd.who, cmd.video)
            is Command.ShareFile -> shareFile(cmd.who, cmd.query)
            is Command.ShareThis -> shareThis(cmd.who)
            is Command.Sms -> resolve(cmd.who)?.let { sms(it, cmd.message) }
            is Command.OpenApp -> openApp(cmd.name)
            is Command.OpenUrl -> {
                val url = if (cmd.url.startsWith("http")) cmd.url else "https://${cmd.url}"
                voice.speak(svc.openUrl(url).let { tr("Opening ${cmd.url}.", "${cmd.url} उघडत आहे.") })
            }
            is Command.Toggle -> toggle(cmd.what, cmd.on)
            is Command.Alarm -> alarm(cmd.hour, cmd.minute)
            Command.ShowAlarms -> { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)); say("Here are your alarms. Say a time next time, like: set an alarm for 6:30 AM.", "हे तुमचे अलार्म. पुढच्या वेळी वेळ सांगा, जसं: सकाळी साडे सहाचा अलार्म लाव.") }
            is Command.Timer -> timer(cmd.seconds)
            is Command.Navigate -> navigate(cmd.place)
            is Command.Search -> search(cmd.query, cmd.site)
            is Command.Play -> play(cmd.query, cmd.app)
            is Command.Volume -> volume(cmd.action)
            is Command.Global -> global(cmd.action)
            is Command.SetLanguage -> {
                Prefs.setLang(svc, cmd.tag)
                say("Okay, I'll speak English now.", "ठीक आहे, आता मी मराठीत बोलेन.")
            }
            is Command.ScreenTask -> ScreenLoop(svc, voice).run(cmd.goal)
            is Command.Answer -> voice.speak(cmd.text)
            Command.Camera -> { start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) || start(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) }
            Command.Time -> {
                val c = Calendar.getInstance()
                val h = c.get(Calendar.HOUR); val m = c.get(Calendar.MINUTE)
                val hh = if (h == 0) 12 else h
                val ampm = if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                say("It's $hh:${"%02d".format(m)} $ampm.", "आता $hh वाजून $m मिनिटे झाली आहेत.")
            }
            Command.Date -> {
                val en = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
                val mr = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.forLanguageTag("mr-IN")).format(Date())
                say("Today is $en.", "आज $mr आहे.")
            }
            Command.Battery -> {
                val pct = svc.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("Battery is at $pct percent.", "बॅटरी $pct टक्के आहे.")
            }
            Command.ReadScreen -> {
                val text = svc.visibleText(700)
                if (text.isBlank()) say("I can't read anything on this screen.", "या स्क्रीनवर वाचण्यासारखं काही नाही.") else voice.speak(text)
            }
            Command.Help -> say(
                "You can say things like: call Rahul. Send hello to Priya on WhatsApp. Send the latest PDF to Amit on WhatsApp. " +
                    "Share this with Mom. WhatsApp video call Dad. Open YouTube. Turn on the torch. Set an alarm for 6:30 AM. " +
                    "Play a song on YouTube. Navigate to Pune station. Read the screen. Or: speak Marathi.",
                "तुम्ही असं म्हणू शकता: राहुलला फोन लाव. प्रियाला व्हॉट्सॲपवर मेसेज पाठव की मी पोहोचलो. शेवटची पीडीएफ अमितला व्हॉट्सॲपवर पाठव. " +
                    "हा फोटो आईला पाठव. बाबांना व्हिडिओ कॉल कर. यूट्यूब उघड. टॉर्च चालू कर. सकाळी सहाचा अलार्म लाव. " +
                    "यूट्यूबवर गाणं वाजव. स्क्रीनवर काय आहे वाचून दाखव. किंवा: इंग्रजीत बोल."
            )
            Command.Stop -> say("Okay.", "ठीक आहे.")
        }
    }

    // ------------------------------------------------------------------ people

    private fun isNumber(who: String) = who.count { it.isDigit() } >= 5 && who.none { it.isLetter() }

    /** Turn a spoken name into a contact with a phone number, asking if it's ambiguous. */
    private suspend fun resolve(who: String): Target? {
        if (isNumber(who)) return Target(who, who.filter { it.isDigit() || it == '+' })
        if (!Contacts.hasPermission(svc)) {
            say("I need permission to read your contacts. Open the Voice Agent app and allow it.", "मला कॉन्टॅक्ट्स वाचायची परवानगी हवी आहे. Voice Agent ॲप उघडून परवानगी द्या.")
            return null
        }
        val found = Contacts.search(svc, who)
        if (found.isEmpty()) {
            say("I couldn't find $who in your contacts.", "$who हे नाव तुमच्या कॉन्टॅक्ट्समध्ये सापडलं नाही.")
            return null
        }
        var pick = found[0]
        val close = found.drop(1).filter { it.score >= pick.score - 0.04 && it.person.name != pick.person.name }.take(2)
        if (close.isNotEmpty()) {
            val options = listOf(pick) + close
            val answer = svc.ask(tr("Which one: ${options.joinToString(", or ") { it.person.name }}?", "कोणता: ${options.joinToString(", की ") { it.person.name }}?"))
            if (answer.isNullOrBlank()) { say("Okay, cancelled.", "ठीक आहे, रद्द केलं."); return null }
            val ordinal = when {
                Regex("(?i)\\b(first|1st|one)\\b|पहिल").containsMatchIn(answer) -> 0
                Regex("(?i)\\b(second|2nd|two)\\b|दुसर").containsMatchIn(answer) -> 1
                Regex("(?i)\\b(third|3rd|three)\\b|तिसर").containsMatchIn(answer) -> 2
                else -> -1
            }
            pick = if (ordinal in options.indices) options[ordinal] else options.maxBy { Text.nameScore(answer, it.person.name) }
        }
        val number = pick.person.numbers.firstOrNull()
        if (number == null) {
            say("${pick.person.name} has no phone number saved.", "${pick.person.name} यांचा नंबर सेव्ह केलेला नाही.")
            return null
        }
        return Target(pick.person.name, number)
    }

    private suspend fun phoneCall(who: String) {
        val t = resolve(who) ?: return
        say("Calling ${t.name}.", "${t.name} यांना फोन लावत आहे.")
        val canCall = svc.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", t.number, null)))
    }

    private suspend fun unsplit(cmd: Command.WaUnsplit) {
        val split = if (Contacts.hasPermission(svc)) Contacts.splitLeadingName(svc, cmd.text) else null
        val target: Target
        var message: String? = null
        if (split == null) {
            target = resolve(cmd.text) ?: return
        } else {
            val (scored, rest) = split
            val number = scored.person.numbers.firstOrNull() ?: return say("${scored.person.name} has no phone number saved.", "${scored.person.name} यांचा नंबर सेव्ह केलेला नाही.")
            target = Target(scored.person.name, number)
            message = rest.replace(Regex("^(?:that|to|saying|ki|की)\\s+"), "").trim().ifBlank { null }
        }
        if (cmd.sms) sms(target, message) else waMessage(target, message)
    }

    // ------------------------------------------------------------------ WhatsApp

    private fun installed(pkg: String) = try { svc.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
    private fun waPackage(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }

    /** WhatsApp wants the full international number without "+" (e.g. 919876543210). */
    private fun waNumber(n: String): String {
        if (n.trim().startsWith("+")) return n.filter { it.isDigit() }
        var d = n.filter { it.isDigit() }
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) d = d.drop(1)
        return if (d.length <= 10) Prefs.countryCode(svc) + d else d
    }

    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text ?: "").toString().trim().lowercase()

    private fun isWaSend(n: AccessibilityNodeInfo, pkg: String): Boolean {
        if (n.packageName != pkg || n.isEditable) return false
        val l = label(n)
        return l == "send" || l == "पाठवा" || l == "पाठवा." || l == "भेजें"
    }

    private fun isWaPicker(n: AccessibilityNodeInfo, pkg: String): Boolean {
        if (n.packageName != pkg) return false
        val id = n.viewIdResourceName ?: ""
        val l = label(n)
        return id.endsWith(":id/contactpicker_row_name") || l.startsWith("send to") || l == "search" && id.endsWith("menuitem_search")
    }

    private fun isNotOnWhatsApp(n: AccessibilityNodeInfo, pkg: String) =
        n.packageName == pkg && Regex("(?i)(isn't|is not|not) on whatsapp|invalid|व्हॉट्सॲपवर नाही").containsMatchIn(label(n))

    private fun openWaChat(pkg: String, number: String, text: String?) {
        val url = "https://api.whatsapp.com/send?phone=${waNumber(number)}" + (text?.let { "&text=" + Uri.encode(it) } ?: "")
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
    }

    private suspend fun waMessage(t: Target, message: String?) {
        val pkg = waPackage() ?: return say("WhatsApp isn't installed.", "WhatsApp इन्स्टॉल केलेलं नाही.")
        val text = message ?: svc.ask(tr("What should I say to ${t.name}?", "${t.name} यांना काय पाठवू?"))
        if (text.isNullOrBlank()) return say("Okay, cancelled.", "ठीक आहे, रद्द केलं.")
        openWaChat(pkg, t.number, text)
        if (!Prefs.autoSend(svc)) return say("Your message to ${t.name} is ready. Tap send.", "${t.name} यांचा मेसेज तयार आहे. सेंड दाबा.")
        val n = svc.waitForNode(9000) { isWaSend(it, pkg) || isNotOnWhatsApp(it, pkg) }
            ?: return say("I opened the chat but couldn't find the send button.", "चॅट उघडला पण सेंड बटण सापडलं नाही.")
        if (isNotOnWhatsApp(n, pkg)) return say("${t.name} doesn't seem to be on WhatsApp.", "${t.name} WhatsApp वर दिसत नाहीत.")
        svc.clickNode(n)
        delay(500)
        say("Sent to ${t.name}.", "${t.name} यांना पाठवलं.")
    }

    private suspend fun waCall(who: String, video: Boolean) {
        val pkg = waPackage() ?: return say("WhatsApp isn't installed.", "WhatsApp इन्स्टॉल केलेलं नाही.")
        val t = resolve(who) ?: return
        if (video) say("Video calling ${t.name} on WhatsApp.", "${t.name} यांना WhatsApp व्हिडिओ कॉल करत आहे.")
        else say("Calling ${t.name} on WhatsApp.", "${t.name} यांना WhatsApp कॉल करत आहे.")
        openWaChat(pkg, t.number, null)
        val want = if (video) Regex("^(video call|व्हिडिओ कॉल)$") else Regex("^(voice call|audio call|call|व्हॉइस कॉल|ऑडिओ कॉल)$")
        val btn = svc.waitForNode(9000) { it.packageName == pkg && want.matches(label(it)) || isNotOnWhatsApp(it, pkg) }
            ?: return say("I couldn't find the call button.", "कॉलचं बटण सापडलं नाही.")
        if (isNotOnWhatsApp(btn, pkg)) return say("${t.name} doesn't seem to be on WhatsApp.", "${t.name} WhatsApp वर दिसत नाहीत.")
        svc.clickNode(btn)
        // Some versions ask "Start call?" first.
        svc.waitForNode(2500) { it.packageName == pkg && Regex("^(call|start call|voice call|video call|कॉल करा)$").matches(label(it)) && it.isClickable }
            ?.let { svc.clickNode(it) }
    }

    /** After WhatsApp opens from a share: pick the person if asked, then press Send. */
    private suspend fun finishWaShare(pkg: String, name: String) {
        val first = svc.waitForNode(9000) { isWaSend(it, pkg) || isWaPicker(it, pkg) }
            ?: return say("WhatsApp opened but I couldn't finish. Please check the screen.", "WhatsApp उघडलं पण पूर्ण करता आलं नाही. स्क्रीन बघा.")
        if (isWaPicker(first, pkg) && !waPickContact(pkg, name))
            return say("I couldn't find $name in WhatsApp.", "WhatsApp मध्ये $name सापडले नाहीत.")
        if (!Prefs.autoSend(svc)) return say("It's ready. Tap send.", "तयार आहे. सेंड दाबा.")
        var sent = false
        repeat(2) { i ->
            val b = svc.waitForNode(if (i == 0) 7000 else 2500) { isWaSend(it, pkg) } ?: return@repeat
            svc.clickNode(b)
            sent = true
            delay(1200)
        }
        if (sent) say("Sent to $name.", "$name यांना पाठवलं.")
        else say("I couldn't find the send button.", "सेंड बटण सापडलं नाही.")
    }

    private suspend fun waPickContact(pkg: String, name: String): Boolean {
        svc.findNode { it.packageName == pkg && (it.viewIdResourceName ?: "").endsWith("menuitem_search") || it.packageName == pkg && label(it) == "search" }
            ?.let { svc.clickNode(it); delay(700) }
        val field = svc.waitForNode(3000) { it.packageName == pkg && it.isEditable } ?: return false
        svc.setText(field, name)
        delay(1500)
        val row = svc.waitForNode(4000) { n ->
            n.packageName == pkg && !n.isEditable && n.text != null && Text.nameScore(name, n.text.toString()) >= 0.85
        } ?: return false
        svc.clickNode(row)
        delay(800)
        return true
    }

    private suspend fun shareFile(who: String, q: FileQuery) {
        if (!FileFinder.hasAccess())
            return say("I need access to your files. Open the Voice Agent app and allow all files access.", "मला फाईल्स बघायची परवानगी हवी आहे. Voice Agent ॲप उघडून ऑल फाईल्स ॲक्सेस द्या.")
        val pkg = waPackage() ?: return say("WhatsApp isn't installed.", "WhatsApp इन्स्टॉल केलेलं नाही.")
        val f = FileFinder.find(svc, q) ?: return say("I couldn't find that file.", "ती फाईल सापडली नाही.")
        val t = resolve(who) ?: return
        val what = FileFinder.spokenName(f, svc)
        say("Sending $what to ${t.name}.", "$what ${t.name} यांना पाठवत आहे.")
        val uri = FileProvider.getUriForFile(svc, "${svc.packageName}.files", File(f.path))
        val i = Intent(Intent.ACTION_SEND)
            .setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("jid", waNumber(t.number) + "@s.whatsapp.net")   // opens that person's chat directly
            .setPackage(pkg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        if (!start(i)) return say("WhatsApp couldn't open the file.", "WhatsApp ला फाईल उघडता आली नाही.")
        finishWaShare(pkg, t.name)
    }

    /** "Send this to Rahul": use the Share button of whatever app is open. */
    private suspend fun shareThis(who: String) {
        val pkg = waPackage() ?: return say("WhatsApp isn't installed.", "WhatsApp इन्स्टॉल केलेलं नाही.")
        val t = resolve(who) ?: return
        val shareRe = Regex("^(share|शेअर|शेअर करा|share via|share…|share\\.\\.\\.)$")
        var share = svc.findNode { !it.isEditable && shareRe.matches(label(it)) }
        if (share == null) {
            // Many apps keep Share inside the ⋮ menu.
            svc.findNode { Regex("^(more options|more|आणखी पर्याय)$").matches(label(it)) }?.let {
                svc.clickNode(it)
                share = svc.waitForNode(2500) { n -> !n.isEditable && shareRe.matches(label(n)) }
            }
        }
        val s = share ?: return say("I can't find a Share button on this screen.", "या स्क्रीनवर शेअर बटण सापडलं नाही.")
        say("Sharing with ${t.name}.", "${t.name} यांना शेअर करत आहे.")
        svc.clickNode(s)
        var wa = svc.waitForNode(5000) { (it.text ?: "").toString().trim().equals("WhatsApp", true) }
        if (wa == null) {
            svc.scroll("down", null)
            wa = svc.waitForNode(2500) { (it.text ?: "").toString().trim().equals("WhatsApp", true) }
        }
        if (wa == null) return say("WhatsApp isn't in the share list.", "शेअर लिस्टमध्ये WhatsApp नाही.")
        svc.clickNode(wa)
        finishWaShare(pkg, t.name)
    }

    private suspend fun sms(t: Target, message: String?) {
        val text = message ?: svc.ask(tr("What should the message say?", "मेसेजमध्ये काय लिहू?"))
        if (text.isNullOrBlank()) return say("Okay, cancelled.", "ठीक आहे, रद्द केलं.")
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(t.number))).putExtra("sms_body", text))
        if (!Prefs.autoSend(svc)) return say("Your text to ${t.name} is ready. Tap send.", "${t.name} यांचा मेसेज तयार आहे. सेंड दाबा.")
        val b = svc.waitForNode(7000) { n ->
            !n.isEditable && Regex("^send( sms| message| mms| text)?$|^send (sms|message)\\b.*|^पाठवा").containsMatchIn(label(n))
        } ?: return say("The text is ready but I couldn't find the send button.", "मेसेज तयार आहे पण सेंड बटण सापडलं नाही.")
        svc.clickNode(b)
        say("Text sent to ${t.name}.", "${t.name} यांना मेसेज पाठवला.")
    }

    // ------------------------------------------------------------------ apps, settings, media

    private suspend fun openApp(name: String) {
        val n = name.lowercase()
        val settings = when {
            Regex("wi-?fi|वाय ?फाय").containsMatchIn(n) -> Settings.ACTION_WIFI_SETTINGS
            Regex("bluetooth|ब्लूटूथ").containsMatchIn(n) -> Settings.ACTION_BLUETOOTH_SETTINGS
            Regex("display|brightness|डिस्प्ले").containsMatchIn(n) -> Settings.ACTION_DISPLAY_SETTINGS
            Regex("sound|आवाज").containsMatchIn(n) && "setting" in n -> Settings.ACTION_SOUND_SETTINGS
            Regex("location|लोकेशन").containsMatchIn(n) -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            Regex("battery|बॅटरी").containsMatchIn(n) -> Intent.ACTION_POWER_USAGE_SUMMARY
            Regex("accessibility").containsMatchIn(n) -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> null
        }
        if (settings != null && start(Intent(settings))) return say("Opening settings.", "सेटिंग्ज उघडत आहे.")
        val (ok, message) = svc.openApp(name)
        voice.speak(message)
        if (!ok) return
    }

    private suspend fun toggle(what: String, on: Boolean) {
        when (what) {
            "torch" -> try {
                val cm = svc.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return say("This phone has no torch.", "या फोनमध्ये टॉर्च नाही.")
                cm.setTorchMode(id, on)
                if (on) say("Torch on.", "टॉर्च चालू केला.") else say("Torch off.", "टॉर्च बंद केला.")
            } catch (e: Exception) {
                say("I couldn't use the torch.", "टॉर्च वापरता आला नाही.")
            }
            "wifi" -> switchOnScreen(Intent(Settings.Panel.ACTION_WIFI), on, "Wi-Fi", "वाय-फाय")
            "bluetooth" -> switchOnScreen(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), on, "Bluetooth", "ब्लूटूथ")
            "airplane" -> switchOnScreen(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), on, "Airplane mode", "एअरप्लेन मोड")
            "data" -> { start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); say("Here are your internet settings.", "ही इंटरनेट सेटिंग्ज.") }
            else -> { start(Intent(Settings.ACTION_WIRELESS_SETTINGS)); say("Here are your network settings.", "ही नेटवर्क सेटिंग्ज.") }
        }
    }

    /** Android doesn't let apps flip Wi-Fi/Bluetooth directly, so open the switch and tap it. */
    private suspend fun switchOnScreen(intent: Intent, on: Boolean, en: String, mr: String) {
        if (!start(intent)) return say("I couldn't open $en settings.", "$mr सेटिंग्ज उघडता आल्या नाहीत.")
        val sw = svc.waitForNode(5000) { it.isCheckable && (it.className?.toString() ?: "").contains("Switch") }
        if (sw == null) return say("Here is $en. Please tap the switch.", "हे $mr. कृपया स्विच दाबा.")
        if (sw.isChecked != on) svc.clickNode(sw)
        delay(700)
        svc.press("back")
        if (on) say("$en is on.", "$mr चालू केलं.") else say("$en is off.", "$mr बंद केलं.")
    }

    private suspend fun alarm(h: Int, m: Int) {
        val ok = start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        val hh = if (h % 12 == 0) 12 else h % 12
        val time = "$hh:${"%02d".format(m)} ${if (h < 12) "AM" else "PM"}"
        if (ok) say("Alarm set for $time.", "$time चा अलार्म लावला.") else say("I couldn't set the alarm.", "अलार्म लावता आला नाही.")
    }

    private suspend fun timer(seconds: Int) {
        val ok = start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        val mins = seconds / 60; val secs = seconds % 60
        val en = listOfNotNull(mins.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" }, secs.takeIf { it > 0 }?.let { "$it seconds" }).joinToString(" ")
        val mr = listOfNotNull(mins.takeIf { it > 0 }?.let { "$it मिनिटे" }, secs.takeIf { it > 0 }?.let { "$it सेकंद" }).joinToString(" ")
        if (ok) say("Timer set for $en.", "$mr चा टायमर लावला.") else say("I couldn't set the timer.", "टायमर लावता आला नाही.")
    }

    private suspend fun navigate(place: String) {
        say("Getting directions to $place.", "$place चा रस्ता दाखवत आहे.")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps")) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
    }

    private suspend fun search(q: String, site: String?) {
        say("Searching for $q.", "$q शोधत आहे.")
        val ok = when (site) {
            "youtube" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            "amazon" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.in/s?k=" + Uri.encode(q))))
            "flipkart" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flipkart.com/search?q=" + Uri.encode(q))))
            "maps", "google maps" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            else -> start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        }
        if (!ok) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    private suspend fun play(q: String, app: String?) {
        say("Playing $q.", "$q लावत आहे.")
        when (app) {
            "spotify" -> { start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))); return }
            "youtube music" -> { start(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))); return }
        }
        val yt = "com.google.android.youtube"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        if (!(installed(yt) && start(Intent(i).setPackage(yt)) || start(i))) return
        // Tap the first video in the results.
        svc.waitForNode(8000) { it.packageName == yt && (it.contentDescription ?: "").toString().contains("play video", true) }
            ?.let { svc.clickNode(it) }
    }

    private suspend fun volume(action: String) {
        val am = svc.getSystemService(AudioManager::class.java)
        val ui = AudioManager.FLAG_SHOW_UI
        when (action) {
            "up" -> { repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, ui) }; say("Volume up.", "आवाज वाढवला.") }
            "down" -> { repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, ui) }; say("Volume down.", "आवाज कमी केला.") }
            "max" -> { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), ui); say("Volume at maximum.", "आवाज पूर्ण केला.") }
            "silent" -> {
                say("Phone on vibrate.", "फोन व्हायब्रेटवर ठेवला.")
                try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: SecurityException) {}
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            "unmute" -> {
                try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: SecurityException) {}
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                say("Sound is back on.", "आवाज परत चालू केला.")
            }
        }
    }

    private fun global(action: String) {
        svc.press(action)
    }
}
