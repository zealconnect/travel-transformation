# 🎤 Voice Agent (Android)

Talk to your phone in **English or Marathi** and it does the work: calls, WhatsApp messages, calls and files, apps, alarms, the torch, Wi-Fi and more.

It is **free**. No API key, no account and no paid service. It uses only what's built into Android:

| Part | What does it | Cost |
|---|---|---|
| Listening | Android speech recognition (`SpeechRecognizer`) | Free |
| Talking back | Android text-to-speech | Free |
| Understanding commands | Built-in English + Marathi command patterns | Free, offline |
| Operating the phone | An Accessibility Service (taps, typing, reading the screen) | Free |
| Unusual requests (optional) | Gemma AI model running **on the phone** (Google MediaPipe) | Free, offline |

Built and tested for a **Pixel 7** (Android 11 or newer works).

---

## Install it on your phone

1. On the phone, download **`release/VoiceAgent.apk`** from this repo and open it.
   Allow "Install unknown apps" for your browser or Files app when asked.
   If Play Protect warns that the app isn't from the Play Store, choose **Install anyway**. The warning appears because the app uses Accessibility.
2. Open **Voice Agent** and go through the 4 setup buttons:
   1. **Allow microphone, contacts, calls and notifications**
   2. **Allow access to files**: turn on *Allow access to manage all files* (needed to find and send files).
   3. **Allow restricted settings**: on the App info page tap **⋮** (top right) → **Allow restricted settings**.
      Android hides this ⋮ option until you've tried step 4 once, so if it isn't there, do step 4, then come back.
   4. **Turn on Voice Agent in Accessibility** → *Use Voice Agent* → Allow.
3. A blue **🎤** button appears on your screen. **Tap it and speak.**

For Marathi replies, make sure Marathi voice data is installed:
Settings → System → Languages → Text-to-speech output → Speech Services by Google ⚙ → Install voice data → मराठी.

## Using it

### Enable / Disable (always listening)

| | What happens | 🎤 button |
|---|---|---|
| **Enabled** | Always listening. Say **"Agent, call Mom"** any time, with no tap. Or say **"Agent"**, wait for "Yes?", then speak. In Marathi: **"एजंट, आईला फोन लाव"**. | 🟢 green |
| **Disabled** | Not listening in the background. Tap 🎤 to give one command. | ⚪ grey |

Ways to switch:
- The big **Enable: always listening** switch at the top of the app
- **Double-tap 🎤**
- The **Voice Agent** Quick Settings tile (swipe down twice from the top; add it via ✏️ the first time)
- The **Disable** button in the "Voice Agent is listening" notification
- By voice: "**Agent, stop listening**" / "**एजंट, ऐकणं बंद कर**" to disable; tap 🎤 and say "**start listening**" / "**ऐकणं सुरू कर**" to enable

It only acts on sentences that **start with the wake word**, so TV and conversations around you are ignored. You can change the wake word in the app (pick something easy to say that you don't use often). While enabled it pauses during phone calls and, by default, while the screen is off. Turn on "Keep listening when the screen is off" if you want that, though it uses more battery.

### Other controls

- **Tap 🎤** → speak one command now (it beeps and turns red 👂). Tap again (⏹) to stop a task.
- **Long-press 🎤** → settings screen. **Drag** it anywhere.
- Switch language in the app or by voice: "speak Marathi" / "इंग्रजीत बोल".

### Things you can say

| English | मराठी |
|---|---|
| Call Mom · Call 98765 43210 | आईला फोन लाव |
| Send hello to Rahul on WhatsApp | राहुलला व्हॉट्सॲपवर मेसेज पाठव की मी पोहोचलो |
| Tell Priya I'm running late | प्रियाला सांग की मी उशिरा येतोय |
| Message Amit on WhatsApp *(asks what to say)* | अमितला मेसेज पाठव |
| Send the latest PDF to Rahul on WhatsApp | शेवटची पीडीएफ राहुलला व्हॉट्सॲपवर पाठव |
| Share the last photo with Priya | शेवटचा फोटो प्रियाला पाठव |
| Send the file named budget to Amit | बजेट नावाची फाईल अमितला पाठव |
| Send this to Mom *(uses the Share button of the open app)* | हा फोटो आईला पाठव |
| WhatsApp video call Dad | बाबांना व्हिडिओ कॉल कर |
| Send an SMS to Rahul saying I'll call later | राहुलला एसएमएस पाठव की नंतर फोन करतो |
| Open YouTube · Play Arijit songs | यूट्यूब उघड · यूट्यूबवर मराठी गाणी लाव |
| Search hotels in Goa · Navigate to Pune station | पुणे स्टेशनचा रस्ता दाखव |
| Turn on the torch · Turn off Wi-Fi · Bluetooth on | टॉर्च चालू कर · वायफाय बंद कर |
| Volume up · Silent | आवाज वाढव · फोन सायलेंट कर |
| Set an alarm for 6:30 AM · Timer for 10 minutes | सकाळी साडे सहाचा अलार्म लाव · १० मिनिटांचा टायमर लाव |
| What's the time · Battery · Read the screen | किती वाजले · बॅटरी किती आहे · स्क्रीनवर काय आहे वाचून दाखव |
| Take a screenshot · Go back · Go home · Lock the phone | स्क्रीनशॉट घे · मागे जा · फोन लॉक कर |
| Help · Stop | मदत · थांब |

**Names:** contacts saved in English are found from Marathi speech ("राहुलला" → *Rahul Sharma*), and "Aai/आई" ↔ "Mom", "Baba/बाबा" ↔ "Dad" and similar work both ways. If two contacts match, it asks which one.
**Files** are searched across the phone (Downloads, WhatsApp media, camera, documents) by type, by words in the file name, and newest first.
**Sending:** by default it presses WhatsApp/SMS *Send* for you. Turn off "Press Send automatically" in the app if you'd rather check first.

## Optional: free offline AI for everything else

The built-in commands cover everyday tasks. For anything else ("like the first post on Instagram", "what's the capital of Japan"), you can add a free AI model that runs **on the phone**, with no internet and no key:

1. On the phone, open <https://huggingface.co/litert-community/Gemma3-1B-IT>, sign in (free) and accept the Gemma license.
2. Download a **`.task`** file, for example `gemma3-1b-it-int4.task` (about 550 MB).
3. In Voice Agent → **Choose model file (.task)** → pick it. Done.

Anything the built-in commands don't recognise then goes to this model. It either picks one of the actions above or works inside apps step by step (reading the screen, tapping, typing). It asks before tapping anything like *Pay*, *Buy* or *Delete*. A 1B model is small, so it's slower and makes more mistakes than the built-in commands. Bigger Gemma `.task` models also work on a Pixel 7 but are slower.

## What it can't do (Android limits)

- **Always listening costs some battery**, as the phone keeps recognising speech. Disable it when you don't need it.
- **WhatsApp has no official way to send silently.** The agent opens the chat with the message or file ready and taps *Send* for you. A WhatsApp update that renames buttons can break this until the app is updated.
- **Password fields:** it refuses to type into them.
- **Banking/UPI apps** often block Accessibility on their screens.
- **Not on the Play Store:** Google restricts apps that use Accessibility like this. It's meant for your own phone.

## Build it yourself

Open this folder in Android Studio and press ▶, or:

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # English + Marathi command tests
```

The app is signed with `app/voiceagent.keystore` (kept in the repo on purpose), so new builds install over old ones without losing settings.

## Code map (`app/src/main/java/com/example/voiceagent/`)

| File | Job |
|---|---|
| `AgentAccessibilityService.kt` | Floating 🎤 button, reads the screen, taps, types, scrolls, opens apps |
| `Voice.kt` | Listening and speaking (Android built-ins) |
| `ListeningService.kt` | "Voice Agent is listening" notification with a Disable button (keeps the mic allowed in the background) |
| `CommandParser.kt` | English + Marathi sentences → commands |
| `Actions.kt` | Does each command (calls, WhatsApp, files, settings, media…) |
| `Contacts.kt`, `FileFinder.kt`, `Text.kt` | Finding people and files; Marathi→Latin spelling and "sounds-alike" matching |
| `Agent.kt`, `LocalBrain.kt` | Fallback to the optional on-phone AI, and its step-by-step screen control |
| `MainActivity.kt`, `AgentTileService.kt`, `Prefs.kt` | Setup screen, Quick Settings tile, saved settings |
