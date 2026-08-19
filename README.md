<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="YiChao Music" />

# YiChao Music

**A modern Android music player with a floating music panel, mini player, multi-platform online search and USB DAC support.**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![AGP](https://img.shields.io/badge/AGP-9.3.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-32-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**YiChao Music (忆潮音乐)** is a full-featured Android music player built with Jetpack Compose. Beyond a regular in-app player, it provides a **floating music panel** and a **mini player** that work on top of any app, so music is always one tap away — in games, browsers or any other screen.

## Features

- **Floating music panel** — a full-featured playback panel rendered as a system overlay (SYSTEM_ALERT_WINDOW), usable above any app
- **Mini player** — a compact floating bar shown while the app is in the background during playback; tap it to expand back into the full panel. Can be toggled in settings
- **Local library** — scans device storage via MediaStore, extracts embedded covers and lyrics, and imports audio through `VIEW`/`SEND` intents and the system file picker
- **Multi-platform online search** — aggregated search across Netease (网易云), QQ Music, Kugou (酷狗) and Jamendo, with search history and direct online playback
- **Synced lyrics** — scrolling lyrics with word-level timing, online lyric matching/refresh, and embedded local lyrics
- **Cover management** — embedded art, local image candidates and online cover search; the new cover can be written back into the audio file
- **Metadata editing** — rename song title / artist, written back to the file tags
- **USB audio exclusive output** — automatic detection of USB DACs / sound cards with exclusive-mode routing, plus a real-time audio signal path view (format, source/output sample rates, bit depth, channels, DSD mode, route, output strategy & device)
- **Bluetooth headset support** — connection detection with per-session volume initialization
- **Playback controls** — Media3 media session with notification & lock-screen controls, play modes (repeat all / repeat one / shuffle), favorites sorted to the top, and a sleep timer (stop after current track)
- **State persistence** — playlist, playback position and play mode are restored across restarts
- **Theme & localization** — System / Light / Dark themes with a circular reveal transition; in-app hot switching between 简体中文 / English / Follow System without recreating the activity
- **Crash logging** — uncaught and caught exceptions written to app-specific external storage with automatic cleanup

## Screens

| Screen | Contents |
| --- | --- |
| Home | Permission status, process memory usage, landscape shortcut, and the full in-app player (cover, progress, lyrics, playlist, favorites, sleep timer, online search) |
| Settings | Appearance (theme), Language, Playback (floating player), About (version) |

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
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Build | AGP 9.3.1, Gradle 9.7.0, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Data layer (DataStore settings, repository)
│       │   ├── di/                      # Koin modules
│       │   ├── log/                     # CrashLogManager
│       │   ├── musicpanel/              # Floating panel / mini player / playback core
│       │   ├── navigation/              # Navigation3 typed routes
│       │   ├── screens/                 # Screens (home / settings)
│       │   │   ├── home/                #   Home player + permission flow
│       │   │   └── settings/            #   Appearance / language / playback / about
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── utils/localization/      # In-app localization manager
│       │   ├── TemplateActivity.kt
│       │   ├── TemplateActivityViewModel.kt
│       │   └── TemplateApplication.kt
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

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`); feature-specific code stays inside the feature module. The `musicpanel/` package hosts the window-overlay UI (full panel + mini player) and the playback engine, which are driven by Media3 ExoPlayer + `MediaSessionService` and shared through a window-level state holder.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`, ≤ API 32: `READ_EXTERNAL_STORAGE`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates |
| Bluetooth (`BLUETOOTH_CONNECT`) | Bluetooth headset control |
| Foreground service (`mediaPlayback`) | Background playback with notification / lock-screen controls |
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

Online music search relies on third-party public web endpoints (Netease / QQ Music / Kugou / Jamendo), whose availability and playback policy may vary by region and song. The app is for personal study and communication only — please support the copyright holders.

## License

[MIT](LICENSE) © 2026 Evilgodxu
