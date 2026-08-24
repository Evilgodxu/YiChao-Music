<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="YiChao Music" />

# YiChao Music

**A modern Android music player with a floating music panel, mini player, playlist management, multi-platform online search and USB DAC support.**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![AGP](https://img.shields.io/badge/AGP-9.3.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-28-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**YiChao Music (忆潮音乐)** is a full-featured Android music player built with Jetpack Compose. Beyond a regular in-app player, it provides a **floating music panel** and a **mini player** that work on top of any app, so music is always one tap away — in games, browsers or any other screen.

## Features

- **Floating music panel** — a full-featured playback panel rendered as a system overlay (SYSTEM_ALERT_WINDOW), usable above any app
- **Mini player** — a compact floating bar shown while the app is in the background during playback, displaying the current lyric; tap it to expand back into the full panel. Can be toggled in settings
- **Local library** — scans device storage via MediaStore, extracts embedded covers and lyrics, and imports audio through `VIEW`/`SEND` intents and the system file picker
- **Multi-platform online search** — aggregated search across Netease (网易云), QQ Music and Kugou (酷狗), with search history and direct online playback
- **Playlist system** — smart playlists (Recently Played / Favorites / Albums / Artists) and custom playlists (create / rename / delete / batch add tracks / drag to reorder / quick switch), persisted as JSON
- **Synced lyrics** — scrolling lyrics with word-level timing (toggleable), online lyric matching/refresh, local lyric file import and embedded lyrics, plus fine-grained lyric offset tuning
- **Cover management** — embedded art, local image candidates and online cover search; the new cover can be written back into the audio file
- **Metadata editing** — rename song title / artist, written back to the file tags, with one-tap copy
- **USB audio exclusive output** — automatic detection of USB DACs / sound cards with exclusive-mode routing, plus a real-time audio signal path view (format, source/output sample rates, bit depth, channels, DSD mode, route, output strategy & device)
- **Bluetooth headset support** — connection detection with per-session volume initialization
- **Playback controls** — Media3 media session with notification & lock-screen controls, play modes (repeat all / repeat one / shuffle), favorites sorted to the top, play-next and a sleep timer (stop after current track)
- **Home gestures** — swipe right for online search, swipe left for the playlist panel, and vertical swipes to switch tracks (toggleable); immersive landscape mode with a rotating disc and auto-hiding floating controls
- **Adaptive layout** — responsive UI based on WindowSizeClass
- **State persistence** — playlist, playback position and play mode are restored across restarts
- **Theme & localization** — System / Light / Dark themes with a circular reveal transition; in-app hot switching between 简体中文 / English / Follow System without recreating the activity
- **Crash logging** — uncaught and caught exceptions written to app-specific external storage with automatic cleanup
- **In-app update** — automatically checks GitHub Releases once a day when returning to the foreground (also manual check on the About screen), showing a dialog with the changelog; the APK can be downloaded and installed in-app or opened in the browser

## Screens

| Screen | Contents |
| --- | --- |
| Home | Permission onboarding dialog (auto-hides once all are granted), immersive player with a rotating disc cover on a cover-colored gradient background, 5-line synced lyrics (fine-tunable), refreshable playlist, favorites, sleep timer, landscape mode, online search via right swipe and playlist panel via left swipe, vertical swipe to switch tracks (long-press the cover / title for cover & lyrics refresh and rename) |
| Settings | Appearance (theme), Language, Playback (mini player / word-by-word rendering / swipe to change track), About (version, update check, GitHub link) |

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.10 |
| UI | Jetpack Compose (BOM 2026.08.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.0 + MediaSessionService |
| Navigation | AndroidX Navigation3 1.1.6 (typed routes) |
| DI | Koin 4.2.2 |
| Persistence | DataStore Preferences 1.2.1 |
| Image loading | Coil 3.5.0 |
| Network | OkHttp 5.4.0 |
| Serialization | kotlinx.serialization 1.11.0 |
| Adaptive layout | androidx.window 1.5.1, material3-adaptive 1.3.0 |
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Build | AGP 9.3.1, Gradle 9.7.0, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Global data layer (permission monitor / settings repository / DataStore)
│       │   ├── di/                      # Koin modules
│       │   ├── log/                     # CrashLogManager
│       │   ├── musicpanel/              # Floating panel / mini player / playback core
│       │   │   ├── api/                 #   Online music sources (Netease / QQ / Kugou)
│       │   │   ├── cover/               #   Cover management & metadata read/write
│       │   │   ├── hardware/            #   USB DAC / Bluetooth / audio signal path
│       │   │   ├── lyrics/              #   Lyric parsing & rendering
│       │   │   ├── model/               #   Music scanning & data models
│       │   │   ├── player/              #   Playback service & playback state
│       │   │   ├── ui/                  #   Floating panel UI
│       │   │   └── view/                #   Overlay / mini player view managers
│       │   ├── navigation/              # Navigation3 typed routes
│       │   ├── screens/                 # Screens (home / settings)
│       │   │   ├── home/                #   Home player + permission flow + playlists
│       │   │   └── settings/            #   Appearance / language / playback / about
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (adaptive layout / icons)
│       │   ├── update/                  # Version check & in-app update
│       │   ├── utils/localization/      # In-app localization manager
│       │   ├── YiChaoActivity.kt
│       │   ├── YiChaoActivityViewModel.kt
│       │   └── YiChaoApplication.kt
│       └── res/                         # Resources (values / values-en)
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependencies)
│   └── wrapper/
├── docs/                                # Architecture notes
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Architecture

The app follows **MVVM with unidirectional data flow**: state flows down from `ViewModel` → `UiState` → UI, while events flow up from the UI to the `ViewModel`. Shared data logic lives in the `data/` layer behind a repository, and everything is wired together by Koin.

Screens are organized with a **zone-based (assembly/area) pattern**:

- `{Screen}Screen.kt` — screen entry, wires the ViewModel to the UI
- `{Screen}Assembly.kt` — composes the areas of the screen
- `{Name}Area.kt` — a self-contained UI zone with a single semantic responsibility

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`); feature-specific code stays inside the feature module. The `musicpanel/` package is split into `api` / `cover` / `hardware` / `lyrics` / `model` / `player` / `ui` / `view` subpackages: the overlay UI (full panel + mini player) and the playback engine are driven by Media3 ExoPlayer + `MediaSessionService` and shared through a window-level state holder.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`, ≤ API 32: `READ_EXTERNAL_STORAGE`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates |
| Bluetooth (`BLUETOOTH_CONNECT`) | Bluetooth headset control |
| Foreground service (`mediaPlayback`) | Background playback with notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Update download completion notification (Android 13+) |
| USB host (optional feature) | USB DAC exclusive audio output |

Permissions are requested through a transparent onboarding activity that chains them one by one and closes automatically once all are granted.

## Getting Started

### Prerequisites

- JDK 21
- Android Studio (latest stable recommended)
- Android SDK with API 37 (`compileSdk`)

### Build

```bash
git clone https://github.com/Evilgodxu/YiChao-Music.git
cd YiChao-Music

# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config, see below)
./gradlew assembleRelease
```

APKs are emitted as `YiChaoMusic-<versionName>-arm64.apk` under `app/build/outputs/apk/`. Only the `arm64-v8a` ABI is built.

### Release Signing

The release build reads signing credentials from `local.properties` in the project root:

```properties
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=jh
KEY_PASSWORD=your_key_password
```

The keystore file is expected at `jh.keystore` in the project root (adjust `storeFile` in `app/build.gradle.kts` if needed). Both files are git-ignored — never commit them.

## Disclaimer

Online music search relies on third-party public web endpoints (Netease / QQ Music / Kugou), whose availability and playback policy may vary by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- Lyric animations and NetEase cloud music parsing originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- Drag-reorder of list items originally referenced from [Reorderable](https://github.com/Calvin-LL/Reorderable); now self-implemented in-app (algorithm-equivalent)
- QQ Music and Kugou Kotlin-native audio source parsing is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
