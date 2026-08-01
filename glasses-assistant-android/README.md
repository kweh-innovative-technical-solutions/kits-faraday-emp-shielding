# Glasses Assistant (Android)

Turns any Bluetooth smart glasses (starting with Ray-Ban Meta Gen 2, worn as a
normal BT headset) into a voice interface for the phone:

> speak → capture audio → transcribe → snapshot the current screen → ask the
> Anthropic API for ONE phone action → perform it via an AccessibilityService →
> speak the result back out the glasses.

Cross-brand by design: vendor SDKs live behind the `GlassesCore` driver
interface; the fallback is any A2DP/HFP Bluetooth headset, which needs no SDK.

## Module layout

```
com.kits.glasses/
  GlassesForegroundService.kt   # runtime loop; owns device + command loop
  MainActivity.kt               # permissions, enable-accessibility deep link, Talk button
  ApiKeys.kt                    # reads BuildConfig.ANTHROPIC_KEY (never hardcoded)
  Stt.kt                        # speech-to-text via Android SpeechRecognizer (v1)
  core/GlassesCore.kt           # device-agnostic capability contract
  driver/bluetooth/GenericBluetoothDriver.kt   # BT headset -> audio in/out (TTS)
  automation/PhoneControlService.kt            # AccessibilityService: read screen + act
  brain/CommandPlanner.kt       # Anthropic API: transcript + screen -> one PhoneCommand
res/xml/phone_control_config.xml
AndroidManifest.xml
```

## The Anthropic API key (never committed)

The key is read from `local.properties` (gitignored) into `BuildConfig` at
build time. Add this line to `local.properties` at the module root:

```
ANTHROPIC_KEY=sk-ant-...
```

`local.properties` also holds `sdk.dir` (your Android SDK path). Both stay out
of version control. Without a key the app builds and runs but replies
"No API key is configured."

The planner defaults to `claude-opus-5`; change `MODEL` in `CommandPlanner.kt`
to a lower-latency model (e.g. `claude-haiku-4-5`) if turn speed matters more.

## Build

```
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

minSdk 29, targetSdk 34. Requires JDK 17+ and an Android SDK with
`platforms;android-34` and `build-tools;34.0.0`.

## Deploy to a phone (physical steps — run on your own machine)

```
adb devices                                    # confirm one authorized device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If no device shows: enable Developer Options (tap Build Number 7×), turn on USB
debugging, plug in, and accept the RSA prompt on the phone.

## Guided first run (on the phone)

1. Pair your Ray-Ban Meta (or any BT headset) in Bluetooth settings; confirm
   audio routes to it (play something).
2. Open **Glasses Assistant**, tap **1. Grant permissions** (allow mic,
   Bluetooth, notifications).
3. Tap **2. Enable phone control** → in Accessibility settings, enable
   *Glasses Assistant*.
   - **Android 13+ sideload note:** if the toggle is greyed out, open the app's
     **App info** → tap **⋮ (top-right)** → **Allow restricted settings**, then
     return and enable it.
4. Tap **3. Start assistant**, then the big **TALK** button, and say
   *"open messages"*.

**What correct behavior looks like:** you hear "Listening", the Messages app
opens, and you hear a short spoken confirmation out the glasses.

**Top 2 failure modes:**
- **Nothing happens** → the accessibility service isn't enabled (`snapshotScreen`
  returns null; the app says "Phone control isn't enabled"). Re-do step 3, and
  the "Allow restricted settings" step on Android 13+.
- **Mic contention with the Meta assistant** → if the glasses' own assistant grabs
  the mic, transcription comes back empty ("I didn't catch that"). Disable/avoid
  the glasses' native hotword while testing, or trigger via the TALK button.

## v1 notes / limits

- `SpeechRecognizer` captures the mic itself; the driver deliberately does **not**
  open a SCO session during recognition (that would fight the recognizer for the
  mic). TTS output still routes to the connected BT device automatically.
- One action per turn: `open_app`, `tap`, `type`, `scroll`, `back`, `home`,
  `recents`, `none`/`speak`.
