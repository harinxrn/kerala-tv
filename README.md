<p align="center">
  <img src="docs/Polished%20Kerala%20Tv%20logo.png" width="140" alt="Kerala Tv logo">
</p>

<h1 align="center">Kerala Tv</h1>

<p align="center"><strong>Malayalam Live TV for Android TV</strong> — YouTube Live news channels plus free IPTV streams, with self-healing links and no clutter.</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Unlicense-blue" alt="License: Unlicense"></a>
  <a href="https://github.com/harinxrn/kerala-tv/releases/tag/v2.6.1"><img src="https://img.shields.io/badge/release-v2.6.1-brightgreen" alt="Release: v2.6.1"></a>
  <img src="https://img.shields.io/badge/platform-Android%20TV-green" alt="Platform: Android TV">
  <img src="https://img.shields.io/badge/Android-5.0%2B-3DDC84" alt="Android 5.0+">
</p>

<p align="center">
  <a href="https://github.com/harinxrn/kerala-tv/releases/download/v2.6.1/KeralaTv-v2.6.1.apk"><img src="https://img.shields.io/badge/Download%20APK-v2.6.1-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download Kerala Tv v2.6.1 APK"></a>
</p>

A minimal Android TV app for watching live Malayalam TV, built for the remote first. Channels come from YouTube Lives and the free [iptv-org](https://github.com/iptv-org/iptv) playlists and refresh inside the app, so the lists stay current without an app update.

## Preview

![Kerala Tv on a TV](docs/Kerala%20Tv%20promo%20-%20app%20on%20a%20TV.png)

![Home screen - YT News](docs/screenshot-home.png)

![Player](docs/preview-player.svg)

_Shown on a TV: the real home screen. Below: the home screen itself, and the player as an interface illustration._

## Features

- **YT News (home page)** — 11 Malayalam news channels whose YouTube Live is the primary source: Asianet News, Manorama News, Mathrubhumi News, 24 News, Reporter TV, Media One, News18 Kerala, Kairali News, Janam TV, Big TV 24x7 and Jaihind TV. Handles verified 2026-10-23 in `yt-news-channels.md`.
- **Self-healing YouTube links** — the app stores only each channel's permanent `@handle/live` page, never a one-off video ID. On every open it resolves each channel's *current* live video ID (tiles show a red LIVE badge), on every tap it resolves the HLS stream fresh and validates it, and if a stream dies mid-play the player re-resolves and resumes. If YouTube is offline for a channel, the app falls back to that channel's IPTV stream. Hold OK on a news tile to play the TV stream, edit the channel link (any pasted channel link is normalized to its `/live` page), or reset it.
- **TV** — Malayalam IPTV channels only (~47 after dedupe), allowlist-filtered from the iptv-org India + Malayalam playlists: Asianet, Surya, Mazhavil Manorama, Flowers, Kairali, Amrita and the rest that iptv-org actually carries, all the news channels above, plus Raj News Malayalam and Real News Kerala (IPTV-only — no YouTube live found). No HD-only filter any more, so SD-only Malayalam channels are no longer dropped; the best-quality variant wins when a channel appears twice.
- **Apple TV-style UI** — dark gradient, one segmented pill for the two sections, large rounded cards, and a white focus ring + 1.10x scale on the focused card.
- **New player UI** — gradient bottom panel with channel logo, LIVE badge, section + position ("1 of 11"), channel name and remote hints, loading label, and auto-fade.
- **Video-only YouTube playback (v2.5)** — the in-app YouTube player runs with its controls fully hidden: no overlay chrome and no seek bar (a live's seek bar drops you into the rewind window - wrong for news). Failed embed attempts happen behind an opaque loading cover, so YouTube error pages are never shown.
- **Age-gate auto-switch (v2.5.2)** — if YouTube age-restricts a channel's live stream, a signed-out in-app player can never play it, so the app detects the restriction, shows the reason on the loading screen, and switches to that channel's TV stream by itself. No error dialog to deal with.
- **Channel zapping (v2.6)** — while a YT News channel plays, UP / DOWN switch to the next / previous news channel without going back to the grid; each switch resolves that channel's current live fresh (native stream first, then the embed, then its TV stream). The YouTube quality toggle moved to LEFT.
- **Remote-key hotfix (v2.6.1)** — in the YouTube player the WebView held focus and swallowed every D-pad press for its own in-page focus movement (visible only as a blue focus border), so the app never saw the keys. They are now intercepted before the WebView, which is also set non-focusable.
- **Quality + lip-sync controls (v2.4/v2.3)** — YouTube playback defaults to 1080p; LEFT arrow in the YouTube player toggles 1080p/720p (remembered). A watchdog compares the video clock with wall time and jumps back to the live edge if the picture drifts behind the sound; RIGHT arrow resyncs manually.
- **Logo (v2.5.1)** — a palm + retro TV mark. The icon/banner are supplied image files (binary, so they are not synced to this repo); `assets-img/make_img.py` generated the earlier v2.5 tile art and remains as a fallback if the images are missing.
- **Streams with special headers just work** — per-stream User-Agent / Referer hints from the playlist (EXTVLCOPT) are passed to the player.
- **Fast after first launch** — playlists are cached on the TV for a week; the Refresh button re-fetches them any time.
- Registers as a proper TV app (leanback launcher + banner) and runs fine alongside replacement launchers such as CleanYourTV.

Note that iptv-org streams are free public links: availability varies and some are region-locked. That is a property of the source, not the app.

## Remote controls

| Key | Action |
| --- | --- |
| D-pad | Move between the YT News / TV pill, Refresh and channels (focused card expands) |
| OK | Open channel / pause in player / confirm |
| Hold OK | Options for a YT News tile (YouTube live, TV stream, edit/reset link) |
| Up / Down in player | Next / previous channel |
| Up / Down in YouTube player | Next / previous news channel |
| Left / Right in player | Show the player info panel |
| Left in YouTube player | Toggle quality 1080p / 720p |
| Right in YouTube player | Resync to the live edge (lip-sync fix) |
| Back | Exit player, back to grid |

## Build

No Gradle. The APK is assembled with the Android SDK build-tools directly (aapt2, ECJ, d8, zipalign, apksigner), the same manual pipeline used for the release builds.

Requirements:

- JDK 17 - a full JDK with `javac`, or a JRE plus an [ECJ](https://www.eclipse.org/jdt/) jar; `build.sh` uses whichever it finds
- Android SDK platform 34 + build-tools, located via `ANDROID_HOME` / `ANDROID_SDK_ROOT` (default `~/android-sdk`)
- Python 3 with Pillow (only for the fallback logo script)

```bash
python3 assets-img/make_img.py   # optional fallback: generates the v2.5 tile art if the logo images are missing
bash build.sh                    # produces build/KeralaTv-vX.Y.apk
```

Signing: set the `KEYSTORE` environment variable to your keystore, or let the script generate a project `debug.keystore` on the first build. Android only installs an update when the signature matches the installed app, so a build signed with a fresh key will not update an install signed with a different key - uninstall once in that case. The build script regenerates the images automatically if they are missing.

The current logo images are supplied files that exist only in the built APK (binary files are not synced to this repo). The fallback script uses the Montserrat fonts in `assets-img/fonts/` when present and falls back to DejaVu Sans Bold otherwise (the font files are also binary and not synced).

## Install

Download `KeralaTv-v2.6.1.apk` from the [v2.6.1 release](https://github.com/harinxrn/kerala-tv/releases/tag/v2.6.1) and get it onto the TV any of these ways:

- **File-transfer app (no cable needed):** send the APK from your phone or computer with an app like [LocalSend](https://localsend.org) or Send Files to TV — install the matching receiver app on the TV, send the file, then open it from the TV's file app and install. The TV will ask you to allow installs from that app the first time; say yes.
- **ADB:**

```bash
adb connect <tv-ip>:5555
adb install KeralaTv-v2.6.1.apk
```

- **USB drive:** copy the APK to a USB stick, plug it into the TV, and open it from the TV's file app.

## How the YouTube playback works

For a news tile with a YouTube link, the app first tries to resolve the channel's current live stream (page scrape, then YouTube's player API as several client types) and play it in the native player. If that is refused, it plays YouTube's official embedded player inside the app, sending the Referer/origin the embed now requires. Only channels that also block embedding fall back to the YouTube (or SmartTube) app, and the app offers the IPTV stream in that case.

## Credits

Channel data: [iptv-org](https://iptv-org.github.io/) — a community-maintained collection of publicly available streams. This app is a player for those playlists; it hosts no streams itself.
