# Kerala Tv - Agent Rules

Read this file fully before making any change to this project.

## What this app is
- Native Java Android TV app (no Gradle, no Kotlin) for watching Malayalam live news and TV channels.
- Built for the owner's mother, who found YouTube on TV hard to use for switching Malayalam news channels. Simplicity for her is the top priority in every decision.
- Package name: com.hari.indiatv - NEVER change it. Changing it makes the app a different app and breaks updates.
- minSdk 21, targetSdk 34.
- There are two sections in the app: YT News and TV (IPTV).

## How to work on this project
- Plan first. Before editing any code, explain the change you propose and wait for the owner's approval. A question or observation from the owner is NOT an instruction to change code.
- One change at a time. Do not bundle unrelated changes.
- Keep changes small and reviewable. Show a summary of what changed after editing.
- Do not edit the README unless the owner explicitly asks.
- Do not commit APK files or the build folder. They are ignored on purpose.
- NEVER commit any keystore file. The signing keystore lives outside this project folder and must stay private. The repo is public.

## Building
- Build with ./build.sh from a Git Bash terminal. There is no Gradle build.
- Requirements: JDK 17 with javac, Android SDK platform android-34, build-tools 34.0.0, and the ANDROID_HOME environment variable pointing at the SDK.
- The KEYSTORE environment variable must point to the historical debug keystore kept outside the project. Builds signed with any other key will NOT install as an update on the existing TV install.
- The app version is hardcoded in build.sh, not in the manifest. For a new release, in build.sh change: --version-code (increase by 1), --version-name, and the output APK file name in the last two lines. All must match.
- A successful build ends with: OK: .../build/KeralaTv-v<version>.apk
- Launcher images (app/src/main/res/drawable/banner.png and app/src/main/res/mipmap-xxxhdpi/ic_launcher.png) are committed in the repo. Do not regenerate them unless the owner asks for a logo change. assets-img/make_img.py has Linux-specific paths and does not run correctly on Windows.

## YouTube live rules - do not break these
- NEVER store a fixed YouTube video ID for a news channel. Live streams change, a stored ID goes dead.
- Store only the channel handle or channel ID. The verified channel list is in yt-news-channels.md.
- Resolve the channel's current /live video and stream fresh on app launch and on every channel tap or zap. Validate what you resolved.
- If a resolved stream fails or the channel is offline, re-resolve once, then fall back to that channel's IPTV stream if it has one.
- Raj News Malayalam and Real News Kerala have no YouTube live channel. They are IPTV only.

## Player remote keys - do not remap without asking
- UP / DOWN: switch (zap) to previous / next channel in the YT News queue
- LEFT: quality toggle (1080p / 720p)
- RIGHT: resync
- OK: pause / resume
- In the WebView player, the Activity must intercept remote keys in dispatchKeyEvent and the WebView must stay non-focusable. This was the v2.6.1 hotfix: if the WebView takes focus it swallows D-pad presses and shows a blue border, and zapping stops working. Do not undo this.

## UI rules
- The user is a non-technical elderly viewer using a TV remote from a distance.
- The focused or selected item must be clearly noticeable at a distance. When in doubt, make focus more obvious, not less.
- Design should stay clean and modern, similar to Apple TV.
- No small text, no hidden gestures, no features that need a phone or a keyboard.

## Known open items
- Audio/video sync offset in YouTube embed playback is unresolved. TV speakers are confirmed, so it is not Bluetooth delay. Suspect is the WebView embed decode path.
- Before adding features on top of v2.6.1, the on-TV retest of channel zapping in v2.6.1 should be confirmed by the owner.
