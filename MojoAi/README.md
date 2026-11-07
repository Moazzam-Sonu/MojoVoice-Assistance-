# MOJO — Voice Assistant for Android

> Say your own wake word, then tell MOJO what to do. MOJO is an animated robot mascot that listens, thinks and answers.

## Install

**[Download the latest APK](https://github.com/Moazzam-Sonu/MojoVoice-Assistance-/releases/latest)**

Or visit the download page: **https://moazzam-sonu.github.io/MojoVoice-Assistance-**

## What MOJO can do

- **Custom wake word.** You record your word 3 times and MOJO trains a small neural network on your phone. Wake word detection runs fully on the device.
- **Voice commands:**
  - call someone from your contacts
  - open any app
  - volume up, down or mute
  - brightness up or down
  - flashlight on or off
  - open WiFi or Bluetooth settings
  - take a screenshot
  - lock the phone
  - scroll up or down
  - go home, go back, recent apps
- **Animated mascot.** MOJO floats, blinks and follows your finger. It waves when it hears its name, closes its eyes to listen, shows thinking dots, and celebrates when a command works. Tap it to say hi. Tap it three times quickly and it gets annoyed.
- **Command history** and a **home screen widget**.
- **In-app updates** from GitHub Releases.

## How it works

| Part | Technology |
|------|------------|
| Language / UI | Kotlin, Jetpack Compose (home screen and mascot), XML views |
| Wake word | Own MFCC feature extraction + own neural network (matrix math, backprop, Adam), trained on the phone |
| Speech to text | Android's built-in `SpeechRecognizer` (offline when your phone has an offline voice pack) |
| Understanding | Rule-based phrase matching |
| Voice replies | Android's built-in Text-to-Speech |
| Phone control | Android AccessibilityService |
| Storage | Room |

## First-time setup

1. Install the APK. Allow "Install unknown apps" for your browser or file manager if asked.
2. Open MOJO and allow the microphone, phone, contacts and notification permissions.
3. **Turn on Accessibility for MOJO.** It's needed to open apps, make calls, go home or back, scroll and take screenshots.
   - On Android 13+, apps installed outside the Play Store are blocked from Accessibility at first ("Restricted setting").
   - Open **Settings → Apps → MOJO**, tap the **⋮** menu, then **Allow restricted settings**. Then turn Accessibility on.
4. Tap **Keep MOJO awake** and allow MOJO to run in the background. Xiaomi, Oppo, Vivo, Samsung and other phones may also need MOJO set to "No restrictions" or "Autostart" in their battery settings.
5. Tap **Set wake word**. Type your word, record it 3 times and tap **Train**. A 2–3 syllable word like "Hey Mojo" works best.

## Build from source

```bash
git clone https://github.com/Moazzam-Sonu/MojoVoice-Assistance-.git
cd MojoVoice-Assistance-/MojoAi
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. You need JDK 17 or newer and the Android SDK (API 34).

Signed releases are built by GitHub Actions. You need these repository secrets: `MOJO_KEYSTORE_BASE64`, `MOJO_KEYSTORE_PASSWORD`, `MOJO_KEY_ALIAS` and `MOJO_KEY_PASSWORD`. Without them, CI builds a debug "dev" pre-release instead.

## Requirements

- Android 8.0+ (API 26)
- Microphone
- Accessibility Service enabled

## License

MIT License — free to use, modify, and distribute.

Based on [NOVA](https://github.com/Aniketsingh2104/NOVA) by Aniketsingh2104 (MIT License). The MOJO mascot artwork is ported from the author's own MOJO React component.
