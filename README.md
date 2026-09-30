<p align="center">
  <img src="logo/kiki-square.png" width="128" alt="Kiki icon" />
</p>

# Kiki Browser

A private-use Android browser built for capturing the web: browse, spot a video, keep it.
Inspired by Aloha Browser — fast WebView browsing, unlimited media downloads (including
HLS streams remuxed to MP4), ad blocking, and a biometric app lock.

> Kiki is sideloaded for personal use. It is not on the Play Store and makes no attempt at
> Play Store compliance.

## Features

### Browsing
- Chromium-powered `WebView` engine with a tab switcher (new / close / switch, thumbnails)
- Incognito tabs — no history writes, ephemeral cookies and cache
- Smart URL bar — search-vs-URL heuristic, autocompletion from history
- Bottom toolbar: back, forward, refresh/stop, tabs, menu
- Pull-to-refresh, find-in-page, desktop mode (UA override + wide viewport)
- External links (YouTube, mail, tel…) handed to the right apps via `ACTION_VIEW`

### Privacy
- Ad & tracker blocking with a bundled EasyList + EasyPrivacy snapshot (refreshable in Settings)
- Blocking via request interception and navigation filtering
- Per-tab shield toggle to bypass blocking instantly
- Cookie policy control (allow / block third-party)
- One-tap clear of history, cookies and cache
- Biometric app lock (`BiometricPrompt`) with settings stored in Keystore-backed
  `EncryptedSharedPreferences`

### Media capture & downloads
- **Media sniffer** — JS bridge enumerates `<video>`, `<audio>`, `<source>` and media links,
  surfaces a floating download pill on any page with media
- **HLS → MP4** — M3U8 parser (master + media playlists), segment fetcher, AES-128
  decryption, remux with `ffmpeg-kit`; quality picker when multiple renditions exist
- **Blob / MSE captures** — in-page blob downloads intercepted and completed natively
- **Direct downloads** — APK / PDF / ZIP files with progress UI
- **Bulk download** — grab every quality option of every stream on a page at once, with
  de-duplicated stream grouping
- Downloads screen with progress, pause/resume/cancel, app-private storage plus optional
  export to MediaStore Downloads; clear-all kept copies included
- Background reliability via WorkManager

### Extras
- **Night mode** — CSS-filter based force-dark, global toggle plus per-site overrides
- **Per-site settings** — desktop mode, JavaScript and night mode as Default / On / Off per host
- **Screen recording** — MediaProjection capture with consent flow
- **Auto-scroll** — hands-free scrolling via draggable start/end swipe pins (gesture replay)

## Install

1. Grab the latest APK from [Releases](https://github.com/diegosoriarios/Kiki/releases)
   (`Kiki-<version>.apk`, signed)
2. On the device, allow **Install unknown apps** for your browser/file manager
3. Open the APK and install

## Build from source

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37, Git.

```bash
git clone https://github.com/diegosoriarios/Kiki.git
cd Kiki
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease        # signed if keystore.properties is present
./gradlew test                   # unit tests
```

### Signing

Release signing reads `keystore.properties` at the repo root (gitignored):

```properties
storeFile=kiki-release.jks
storePassword=…
keyAlias=kiki
keyPassword=…
```

Without that file, `assembleRelease` still builds — it just produces an unsigned APK.

## CI / releases

GitHub Actions workflow (`.github/workflows/release.yml`):

- **Manual run** — Actions tab → *Release* → *Run workflow* (optional custom tag input)
- **Automatic** — pushing a tag like `v1.1` builds and publishes a release

The workflow decodes the keystore from secrets, writes `keystore.properties`, runs
`assembleRelease`, and uploads the signed APK to a GitHub Release with generated notes.

Required repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i kiki-release.jks \| pbcopy` (macOS) — the keystore file |
| `KIKI_STORE_PASSWORD` | keystore password |
| `KIKI_KEY_ALIAS` | `kiki` |
| `KIKI_KEY_PASSWORD` | key password (same as store password here) |

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x, coroutines + Flow |
| UI | Jetpack Compose (BOM), Material 3, single Activity |
| Engine | `android.webkit.WebView` |
| Storage | Room (history, bookmarks, downloads, site settings) + EncryptedSharedPreferences |
| Networking | OkHttp |
| Background | WorkManager |
| Media | ffmpeg-kit (HTTPS build) for HLS remux |
| Min / target SDK | 26 / 35 (compileSdk 37) |
| DI | Manual — no framework |

## Permissions

- **Internet** — browsing and downloads
- **Storage / media** — saving downloads and exporting to MediaStore
- **Notifications** — download progress
- **Biometrics** — app lock
- **Screen capture** — only when you start a recording (per-session consent dialog)

## Limitations

- DRM-protected streams (Widevine, FairPlay) cannot and will not be downloaded
- Some HLS streams use unsupported codecs; `-c copy` remux means no transcoding
- Ad-block rules are EasyList/EasyPrivacy snapshots, refreshed manually in Settings

Please respect copyright and the terms of service of the sites you use Kiki with.
Download content you have the right to keep.


## Licence

Private-use software. © Diego. All rights reserved. No licence is granted for
redistribution or commercial use.
