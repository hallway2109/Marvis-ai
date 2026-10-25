# Marvis 2.0: Android AI companion (OpenRouter)

## Package structure
```
app/src/main/java/com/marvis/ai/
├── MarvisApp.kt                 Application: Room DB, notification channel
├── MainActivity.kt              Permissions, MediaProjection consent, Compose host
├── ai/
│   ├── GeminiClient.kt          Multimodal REST client + function-calling loop
│   ├── MarvisTools.kt           Tool (function) declarations sent to Gemini
│   ├── SystemPrompt.kt          Persona, emotion tags, safety rules
│   └── Emotion.kt               warm/friendly/calm/enthusiastic -> TTS pitch/rate + orb colour
├── device/
│   ├── MarvisAccessibilityService.kt  scroll, tap, type, screenshot, settings-switch toggles, global buttons
│   ├── ActionRouter.kt          tool name -> device action (open app, rotate, email/SMS, reminders)
│   ├── ScreenCaptureHelper.kt   screenshot -> JPEG/base64 (a11y first, MediaProjection fallback)
│   ├── ScreenCaptureService.kt  foreground service holding MediaProjection
│   └── AppForeground.kt         lets Marvis step aside so tools act on the OTHER app
├── reminders/
│   ├── Reminder.kt              Room entity + DAO + database
│   ├── TaskReminderManager.kt   Room + AlarmManager scheduling, repeats
│   └── ReminderWorkers.kt       Receiver -> WorkManager worker -> notification; BootReceiver
├── voice/VoiceManagers.kt       SpeechRecognizer (STT) + emotion-tuned TextToSpeech
└── ui/                          MarvisViewModel, Theme, MarvisMainScreen (+ MarvisOrb)
```

## Setup
1. `cp local.properties.example local.properties`; set `sdk.dir` and `GEMINI_API_KEY` (https://aistudio.google.com).
2. Model ID lives in `app/build.gradle.kts` (`GEMINI_MODEL`). Newer Flash IDs (e.g. `gemini-3.8-flash`) are a one-line change.
3. Install, then:
   - Settings > Accessibility > Marvis > enable.
   - Android 13+ sideloaded apps: App info > ⋮ > **Allow restricted settings** first, or the accessibility toggle is greyed out.
   - Grant mic + notifications; for rotation allow "Modify system settings"; for exact reminders allow "Alarms & reminders".

## Build options
### A) Android Studio (most reliable)
Open the folder > Sync > Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

### B) Command line (PC)
```
gradle wrapper --gradle-version 8.10.2     # once, generates ./gradlew
./gradlew :app:assembleDebug
```
Release: create a keystore (`keytool -genkeypair -v -keystore marvis.jks -alias marvis -keyalg RSA -keysize 2048 -validity 10000`), add a `signingConfig`, run `./gradlew :app:assembleRelease`.

### C) GitHub Actions (no local setup; best if you only have a phone)
Push this folder to a repo, add secret `GEMINI_API_KEY`, run the "Build Marvis APK" workflow, download the artifact.

### D) Termux (possible, but fiddly)
Google's SDK build tools are x86 binaries, so on ARM Termux you need community aarch64 builds. Needs ~6 GB+ free RAM.
```
pkg update && pkg install openjdk-17 gradle git wget unzip aapt2 -y
# 1. Get an aarch64 Android SDK (platform android-35 + build-tools 35.x), e.g. from the community
#    "android-sdk-tools" aarch64 releases for Termux; unpack to $HOME/android-sdk.
# 2. Point Gradle at it:
echo "sdk.dir=$HOME/android-sdk"  >> local.properties
echo "GEMINI_API_KEY=xxxx"        >> local.properties
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> gradle.properties
echo "org.gradle.jvmargs=-Xmx2g"  >> gradle.properties
# 3. Build
gradle :app:assembleDebug --no-daemon
termux-open app/build/outputs/apk/debug/app-debug.apk
```
If this fails on AAPT2/KSP errors, use option C.

## Known platform limits (by design of Android, not bugs)
- **Wi-Fi / Bluetooth / mobile data**: no public API lets third-party apps flip these silently on Android 10+. Marvis opens the Settings page and presses the real switch via Accessibility. Labels vary by OEM; tune the label lists in `ActionRouter`.
- **Screenshots** of secure windows (banking, DRM video) are black.
- **Staying out of the way**: when Marvis is foreground it minimizes itself before acting. Next step for true Jarvis behaviour: a floating overlay bubble (`SYSTEM_ALERT_WINDOW`) or `VoiceInteractionService` so you can summon Marvis over any app.
- **API key in APK is extractable.** For anything you distribute, proxy requests through your own backend.
- Google Play restricts Accessibility apps to genuine accessibility use; plan for sideloading.


## v2 changes
- OpenRouter (`openrouter.ai/api/v1/chat/completions`). Key is entered in-app (status chip, top right), stored with EncryptedSharedPreferences. No key in the APK; the GitHub secret is no longer needed.
- 503/429 handling: 3 retries with backoff, then automatic fallback to `openrouter/free`.
- `MarvisVoiceService`: microphone foreground service, wake words "Hey Marvis" / "Marvis" / "Open Air" (edit `WAKE` regex).
- Emotion tags per sentence -> TTS pitch/rate + orb colour.
- Actions use a text protocol (`[[do:{...}]]`) so they work on free models that lack tool-calling.


## v4 (voice-first)
- No text input: orb + captions + 3 buttons (screen share, talk/stop, settings).
- Floating orb (`MarvisOverlayService`): tap = mic on/off with chime (tap while busy = stop), long-press = menu, drag = move.
- Wake word: Marvis / Jarvis / Friday / custom; recognised in Latin, Devanagari and katakana spellings.
- "I can't hear it, can you repeat that?" once when a command was expected but audio was unclear; then listening ends cleanly.
- "Let me think..." in the active language when processing starts.
- 50 MB Room memory (`MemoryManager`), editable in Settings; voice: "Remember this", "Ye memory me save karo".
- `onTaskRemoved` in voice, overlay, accessibility and capture services calls `MarvisRuntime.shutdown()`.

## v5: repeat guard
- Same task said more than once ("scroll down, scroll down, scroll down", or again within 60 s): Marvis asks "once or several times?", then "how many?", and does exactly that.
- Explicit counts ("3 times", "teen baar", "三回") skip the question. Max 10. Only scroll / tap / click / system buttons repeat; everything else runs once.
- Identical commands arriving while the first is still running are dropped. Toggle: Settings > Listening > "Ask before repeating a task".
