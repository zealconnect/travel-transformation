# Voice Agent (Android)

Talk to your phone the way you normally speak, in **English, Marathi or a mix**, and it does the work: calls, WhatsApp messages, calls and files, apps, music, maps, alarms, the torch and more. It can handle **several requests in one sentence**.

It is **free and private**:

| Part | What does it | Cost |
|---|---|---|
| Understanding | Google's **Gemma 4** AI model running **on the phone** (LiteRT-LM) | Free, no account, works offline |
| Listening | Android speech recognition | Free |
| Talking back | Android text-to-speech | Free |
| Doing things | Android intents: calls, WhatsApp, sharing files, apps, alarms… | Free |

Built for a **Pixel 7** (Android 11 or newer works; the AI needs about 6 GB of RAM or more).

## Install

1. Download **`release/VoiceAgent.apk`** on the phone and install it. Play Protect doesn't block this version.
2. Open **Voice Agent**. The *Finish setup* card lists what's left:
   - **Microphone, contacts & calls**
   - **Files** (to send files, photos and PDFs)
   - **Make it your assistant** (recommended): *Digital assistant app → Voice Agent*. Then holding the power button starts it, and the wake word can open apps directly. This replaces Gemini on the power button; switch back on the same settings page any time.
3. Under **Understanding**, tap **Download · 2.6 GB**. Use Wi-Fi; it continues in the background.
4. Turn on **Always listening**, then say "**Agent, …**".

## Using it

- **Wake word:** "Agent, tell Priya I'll be 10 minutes late" / "एजंट, आईला फोन लाव". Change "Agent" in Preferences.
- **Without the wake word:** hold the power button (once it's your assistant), tap the **Voice Agent** tile in Quick Settings, tap **Talk** in its notification, or long-press the app icon → **Talk**.
- **Discreet by design:** nothing floats on your screen. While it listens, a thin coloured glow appears at the bottom edge, and a short vibration confirms when it's done. It speaks only to answer a question, ask something it needs, or report a problem (*Spoken replies → Only when needed*). Choose *Confirm everything* if you want spoken confirmations.
- **Pause / resume:** the switch in the app, *Pause* in the notification, or say "Agent, stop listening".

### Examples

| English / Hinglish | मराठी |
|---|---|
| Tell Priya I'm stuck in traffic and will be 10 minutes late | प्रियाला सांग की आज मीटिंग पाच वाजता आहे |
| Send the hotel voucher PDF to Rahul and then call him | शेवटचा फोटो राहुलला पाठव आणि सकाळी सहाचा अलार्म लाव |
| Mom ko call karo · WhatsApp video call Dad | बाबांना व्हिडिओ कॉल कर · माझ्या भावाला कॉल कर |
| Put on some Arijit Singh and turn the volume up | यूट्यूबवर मराठी गाणी लाव |
| Remind me in 20 minutes · Wake me up at 6:30 | १० मिनिटांचा टायमर लाव |
| Switch off Wi-Fi and Bluetooth · Torch on | टॉर्च चालू कर · आवाज कमी कर |
| What's 18% of 2500? · What time is it? | किती वाजले · आज कोणता वार आहे |

If something essential is missing ("message Amit"), it asks ("What should I tell Amit?").

## How it understands

1. **Obvious one-liners** ("torch on", "what time is it") run instantly from fixed patterns.
2. **Everything else** goes to Gemma 4 on the phone. Its instructions are cached once, so each command only processes what you said. In testing on a desktop processor that took about 1.5–3 seconds per command; speed on the phone depends on its GPU, and the first load after starting takes several seconds.
3. The app **double-checks the model** where small models slip: durations and alarm times are recalculated from your words, and a simple extra request the model skipped ("…आणि मग टॉर्च चालू कर") is added back.
4. **Without the brain installed,** only the fixed English/Marathi phrases work.

The instructions the model follows are in `app/src/main/assets/brain/`. They were tested against the exact model file the app downloads, on 31 English, Marathi and Hinglish commands.

## Pressing Send for you (auto-send)

Out of the box the agent **drafts** WhatsApp and SMS messages and leaves you to tap Send. To make it press Send itself, it needs Android's Accessibility access, which Google blocks for apps installed outside the Play Store. You can unlock it in either of two ways; **no computer is required.**

**On the phone (Shizuku):** in the app, open the **Press Send for me** card and follow the three steps. It installs the free [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) app, which you start once via Android's **Wireless debugging** (a few taps in Developer options). The agent then installs its own full version over itself and turns on screen control; your settings and the downloaded brain are kept. Afterwards Shizuku can be removed. If you restart the phone and Send stops working, just open Shizuku and start it again.

**From a computer (alternative):** `adb install release/VoiceAgent-full.apk`, then turn on *Screen control* in the app.

Once set up, the full version also:

| | Drafts only (default) | After auto-send upgrade |
|---|---|---|
| WhatsApp / SMS messages and files | You tap Send | Presses Send for you |
| Wi-Fi / Bluetooth | Opens the switch | Flips it for you |
| "Send this to Mom" (Share button of the open app), read the screen, go back, lock | ❌ | ✅ |

`VoiceAgent.apk` carries the full version inside it, so the in-app upgrade needs no further download.

When a request has several steps and one of them waits for you to tap Send, the following steps that would open another app wait in your notifications, so nothing covers the message you're about to send.

## Limits

- **Speech recognition** is done by Google's speech service on the phone (online or on-device, depending on the language). Understanding runs fully on the phone.
- Android shows its green microphone dot while Voice Agent listens; apps can't hide it.
- **Always listening uses battery.** By default it pauses while the screen is off and during calls.
- The AI uses about 2–3 GB of memory while loaded. It unloads after 20 minutes without use, or when the phone needs the memory.
- **Not on the Play Store.** It's meant for your own phone.

## Build

```bash
./gradlew assembleLiteDebug      # main version → app/build/outputs/apk/lite/debug/
./gradlew assembleFullDebug      # full version → app/build/outputs/apk/full/debug/
./gradlew testLiteDebugUnitTest  # command, wake-word and AI-reply tests, plus screenshots in app/build/screenshots/
```

Signed with `app/voiceagent.keystore` (kept in the repo on purpose), so new builds install over old ones.

## Code map (`app/src/main/java/com/example/voiceagent/`)

| File | Job |
|---|---|
| `AgentService.kt` | The agent in the background: wake word, sessions, haptics, notification |
| `Agent.kt` | Instant patterns vs. the AI, follow-up questions, step-by-step screen control (full) |
| `Brain.kt` | Gemma 4 on the phone (LiteRT-LM), cached instructions, checking its answers |
| `ModelDownload.kt` | One-tap download of the AI model |
| `Actions.kt` | Does each step (calls, WhatsApp, files, settings, media…) |
| `CommandParser.kt`, `Text.kt` | Fixed English/Marathi patterns, Marathi→Latin spelling, wake word |
| `Contacts.kt`, `FileFinder.kt` | Finding people (incl. "Mom"/"आई", "brother"/"भाऊ") and files |
| `Voice.kt` | Listening and speaking |
| `TalkActivity.kt`, `GlowView.kt`, `Assistant.kt`, `AgentTileService.kt` | Ways to start talking, and the bottom-edge glow |
| `Upgrade.kt` | Phone-only one-time upgrade to the full (auto-send) version via Shizuku |
| `MainActivity.kt`, `Prefs.kt` | The Material 3 settings screen |

`app/src/full/`: `AgentAccessibilityService.kt` (screen control), full version only.
