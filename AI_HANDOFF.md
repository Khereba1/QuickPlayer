# QuickPlayer: handoff notes for an AI assistant

Paste this file together with the project zip when asking an AI to change the app.

## What this is
Android audio/video player (Kotlin, Jetpack Compose Material3, Media3 1.5.0 ExoPlayer + MediaSessionService).
Package/namespace `com.myplayer.app`, applicationId `com.khereba1.quickplayer`. minSdk 29, compileSdk 35.
Arabic + English UI (values/strings.xml, values-ar/strings.xml), developer name "Khereba1".
No INTERNET permission (privacy promise): do not add network features unless the owner asks.

## How it is built (IMPORTANT)
The owner has only a phone. There is NO local build. Code is pushed from Termux to GitHub
and GitHub Actions (.github/workflows/android-build.yml) builds the APK with Gradle 8.10.2.
So you cannot compile: be very careful and conservative. A mistake shows up only as a failed Actions run.
- Avoid Kotlin JVM signature clashes (a `var x` with `private set` plus `fun setX(...)` breaks the build).
- Use only APIs that exist in Media3 1.5.0 / Compose BOM 2024.10.01 (material3 1.3.0).
- Every new string needs BOTH values/strings.xml and values-ar/strings.xml.
- Do not bump library versions without checking they exist.

## Files (app/src/main/java/com/myplayer/app)
- PlaybackService.kt: ExoPlayer + MediaSession, notification close button, ends external audio notification
- PlayerViewModel.kt: library (MediaStore), playlists, favorites, controller state, sleep timer, tracks
- MainActivity.kt: permissions, PiP (with remote actions), PlayerApp root composable
- ExternalAudioActivity.kt: translucent activity for audio opened from file managers (plays in background, no UI)
- LibraryScreen.kt, NowPlayingScreen.kt, VideoPlayerScreen.kt, SettingsScreen.kt, Sheets.kt, Components.kt: UI
- AudioFx.kt: equalizer/bass/loudness. FdDataSource.kt: plays files via file descriptors (external content)
- Core.kt: App, AppLog (in-app error log), LocaleHelper, AppSettings, Thumbs. Theme.kt: colors/accents

## What to send back
Return ONLY the changed files, complete (not snippets), each with its path relative to the project root,
packaged as a zip if possible. The owner unzips over the project and runs git add/commit/push.
If the build fails, the owner will send the lines containing `e: ` from the Actions log.

## Owner workflow (for reference)
cd ~/quickplayer && unzip -o ~/storage/downloads/fix.zip -d . && git add . && git commit -m "msg" && git push
Download: github.com/Khereba1/QuickPlayer/releases/tag/latest (QuickPlayer.apk)
Signing key lives in GitHub Secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). Never print or commit it.
