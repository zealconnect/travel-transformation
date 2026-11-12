# 🎤 Voice Agent (Android)

Talk to your phone in **English or Marathi** and it does the work: calls, WhatsApp messages, calls and files, apps, alarms, the torch, Wi-Fi and more.

It is **free**. No API key, no account and no paid service. It uses only what's built into Android:

| Part | What does it | Cost |
|---|---|---|
| Listening | Android speech recognition (`SpeechRecognizer`) | Free |
| Talking back | Android text-to-speech | Free |
| Understanding commands | Built-in English + Marathi command patterns | Free, offline |
| Doing things | Android intents: calls, WhatsApp chats/calls, sharing files, apps, alarms… | Free |
| Unusual requests (optional) | Gemma AI model running **on the phone** (Google MediaPipe) | Free, offline |

Built and tested for a **Pixel 7** (Android 11 or newer works).

---

## Two versions

| | **VoiceAgent.apk** (use this) | VoiceAgent-full.apk |
|---|---|---|
| Install from the phone | ✅ Play Protect doesn't block it | ❌ Blocked ("App blocked to protect your device") |
| Install from a computer (`adb install`) | ✅ | ✅ |
| Calls, WhatsApp calls, apps, alarms, torch, volume, maps, music, time… | ✅ Fully hands-free | ✅ |
| WhatsApp/SMS messages and files | Opens the chat with everything ready; **you tap Send** | Presses Send for you |
| "Send this to Mom" (Share button of the open app), read the screen, go back, screenshot, lock | ❌ | ✅ |
| Wi-Fi / Bluetooth | Opens the switch; you tap it | Flips it for you |
| AI working inside other apps | ❌ | ✅ |

Why: Play Protect blocks apps installed from a browser if they use **Accessibility**, the only way an app can press buttons inside other apps. The main version doesn't use it, so it installs normally. Both can be installed side by side.

## Install it on your phone

1. On the phone, download **`release/VoiceAgent.apk`** and open it. Allow "Install unknown apps" for your browser or Files app when asked.
   If Play Protect shows a softer warning with **Install anyway**, tap that.
2. Open **Voice Agent** and do the setup steps:
   1. **Allow microphone, contacts, calls and notifications**
   2. **Allow access to files**: turn on *Allow access to manage all files* (to find and send files).
   3. **Start Voice Agent**: a small "Voice Agent" notification appears, with a 🎤 **Talk** button.
   4. **Make Voice Agent your digital assistant** (recommended): *Digital assistant app* → Voice Agent.
      Then **hold the power button** to talk, and "Agent, …" can open WhatsApp and other apps by itself. This replaces Gemini/Google Assistant on the power button; switch back on the same page any time.
   5. *Optional:* the floating 🎤 button ("Display over other apps"). Many phones deny this for apps not from the Play Store. Skip it if so; everything works without it.
3. Turn on **Enable: always listening** at the top if you want hands-free.

**Ways to talk to it:** hold the power button (step 4) · say "Agent …" (when enabled) · the **Voice Agent** Quick Settings tile · **🎤 Talk** in the notification · long-press the app icon → **Talk** · the floating 🎤 (if allowed).

Without step 4, Android doesn't let a background app open other apps. When you use the wake word, the agent then puts a "Tap to continue" notification up instead. Phone calls always work directly.

After restarting the phone, open the app once and it starts again.

For Marathi replies, make sure Marathi voice data is installed:
Settings → System → Languages → Text-to-speech output → Speech Services by Google ⚙ → Install voice data → मराठी.

### Full version (optional, from a computer)

Connect the phone with USB debugging on and run `adb install release/VoiceAgent-full.apk`. Then in that app: **Allow restricted settings** (App info → ⋮) and **turn on screen control in Accessibility**.

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
- The **Disable / Enable** button in the "Voice Agent" notification (**Quit** there stops the agent completely)
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

- **Main version:** it can't press buttons inside other apps, so you tap *Send* yourself (see the table above).
- **Always listening costs some battery**, as the phone keeps recognising speech. Disable it when you don't need it.
- **Full version:** WhatsApp has no official way to send silently. The agent taps *Send* on screen, and a WhatsApp update that renames buttons can break this until the app is updated.
- **Password fields:** it never types into them. **Banking/UPI apps** often block screen control.
- **Not on the Play Store.** It's meant for your own phone.

## Build it yourself

Open this folder in Android Studio and press ▶, or:

```bash
./gradlew assembleLiteDebug     # main version → app/build/outputs/apk/lite/debug/
./gradlew assembleFullDebug     # full version (screen control) → app/build/outputs/apk/full/debug/
./gradlew testLiteDebugUnitTest # English + Marathi command and wake-word tests
```

The app is signed with `app/voiceagent.keystore` (kept in the repo on purpose), so new builds install over old ones without losing settings.

## Code map

`app/src/main/java/com/example/voiceagent/` (both versions):

| File | Job |
|---|---|
| `AgentService.kt` | The agent: floating 🎤 button, notification, always-listening, running commands, opening apps |
| `Voice.kt` | Listening and speaking (Android built-ins) |
| `CommandParser.kt` | English + Marathi sentences → commands |
| `Actions.kt` | Does each command (calls, WhatsApp, files, settings, media…) |
| `Contacts.kt`, `FileFinder.kt`, `Text.kt` | Finding people and files; Marathi→Latin spelling, "sounds-alike" matching, wake word |
| `Agent.kt`, `LocalBrain.kt` | Fallback to the optional on-phone AI, and its step-by-step screen control |
| `Screen.kt` | The screen-control interface (empty in the main version) |
| `MainActivity.kt`, `AgentTileService.kt`, `Prefs.kt` | Setup screen, Quick Settings tile, saved settings |

`app/src/full/`: `AgentAccessibilityService.kt` (screen control), included only in the full version.
