# VoiceMail (Android, Kotlin)

Double-tap the back of your phone -> speak -> it does it in Gmail.
Examples: "read my unread emails", "mark all promotions as read", "archive emails from Swiggy",
"send an email to john at gmail dot com saying I'll be late". Mark-read / archive / trash / send ask for a tap on Confirm first.

## 1. Google setup (once)
1. console.cloud.google.com -> new project -> enable **Gmail API**.
2. OAuth consent screen -> External -> add your own Gmail as a **Test user**.
3. Credentials -> Create OAuth client ID -> type **Android**
   - Package name: `com.example.voicemail`
   - SHA-1: run `./gradlew signingReport` in Android Studio's terminal and copy the **debug** SHA-1.
   (No client ID goes in the code; Google matches the package + SHA-1.)

## 2. Build and install
1. Android Studio -> Open this folder -> let Gradle sync.
2. On the Motorola: Settings -> About phone -> tap Build number 7 times -> Developer options -> USB debugging. Plug in and press Run.

## 3. In the app (follow buttons 1-4)
- Paste your Anthropic API key (console.anthropic.com) and Save. Only the spoken sentence is sent to Claude, never your emails.
- Allow microphone/notifications, allow "display over other apps", Connect Gmail, Start listener.
- Settings -> Battery -> VoiceMail -> **Unrestricted**, or Motorola will kill the listener.

## 4. Tuning
- Back-tap is detected from the accelerometer (Motorola has no native back-tap). Use the sensitivity slider; restart the listener after changing it.
- It works best with the screen on. With the screen off many phones pause sensors.
- Walking/jogging can cause false triggers; raise the threshold.

## Alternative gesture
Settings -> Apps -> Default apps -> Digital assistant app -> VoiceMail. Then your phone's assistant gesture (corner swipe / power-button hold) opens the same voice panel, with no sensor needed.

## Notes
- Untested on a device; expect small tweaks. Consent screen in Testing mode may ask you to reconnect Gmail about weekly.
- The Claude model is set in ClaudeClient.kt (`claude-haiku-5-5`).

## Build without Android Studio (GitHub Actions)
1. Create a **private** GitHub repo and upload this folder's contents (the folder containing `app`, `.github`, `settings.gradle.kts`).
2. Repo -> Actions -> "Build APK" -> Run workflow. After ~5 minutes download `VoiceMail-apk` and unzip to get `app-debug.apk`.
3. Copy the APK to the phone and install it (allow "install unknown apps").
The APK is signed with the bundled `app/voicemail.keystore`, so its SHA-1 stays the same for the Google OAuth client.
