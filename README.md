<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="YiChao Music" />

# YiChao Music

**A modern Android music player with a floating music panel, mini player, playlist management, multi-platform online search and playback speed control.**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![AGP](https://img.shields.io/badge/AGP-9.3.2-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-30-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**YiChao Music (忆潮音乐)** is a full-featured Android music player built with Jetpack Compose. Beyond a regular in-app player, it provides a **floating music panel** and a **mini player** that work on top of any app, so music is always one tap away — in games, browsers or any other screen.

## Features

- **Floating music panel** — a full-featured playback panel rendered as a system overlay (SYSTEM_ALERT_WINDOW), usable above any app
- **Mini player** — a compact floating bar shown while the app is in the background during playback, displaying the current lyric; tap it to expand back into the full panel. Can be toggled in settings
- **Local library** — scans device storage via MediaStore, extracts embedded covers and lyrics, and imports audio through `VIEW`/`SEND` intents and the system file picker
- **Multi-platform online search** — aggregated search across Netease (网易云), QQ Music, Kugou (酷狗), Kuwo (酷我) and Migu (咪咕), with search history, quality selection (lossless / high / standard) and online caching (downloaded to the system Downloads directory, then auto-switched to local playback once cached)
- **Proxy source (代理音源)** — import third-party aggregated music sources (via local file / link / text) to customize search, playback URL, lyric and cover resolution per platform, with enable / disable / remove and automatic fallback to the built-in parser on failure; see the [忆潮代理音源规范](docs/忆潮代理音源规范.md) for the JSON spec
- **Playlist system** — smart playlists (Recently Played / Favorites / Albums / Artists / by format / suspected fake lossless / suspected AI music) and custom playlists (create / rename / delete / batch add tracks / drag to reorder / quick switch), persisted as JSON
- **Synced lyrics** — scrolling lyrics with word-level timing (toggleable), online lyric matching/refresh, local lyric file import and embedded lyrics, plus fine-grained lyric offset tuning
- **Lyric typography** — per-scene font size and visible-line count for the music panel, home portrait and home landscape (with 3D intensity), adjustable in Typography settings
- **Cover management** — embedded art, local image candidates and online cover search; the new cover can be written back into the audio file
- **Metadata editing** — rename song title / artist, written back to the file tags, with one-tap copy
- **Track format display** — shows the currently played source format in the progress area (container format, bit depth, sample rate, bitrate)
- **Library analysis** — long-press the playlist button to open the analysis sheet: a ring chart of the library's audio-format ratio, incremental verification of suspected fake-lossless FLACs (upsampling / brick-wall transcode detection via spectral analysis) and suspected AI-generated tracks (heuristics on stereo correlation, high-shelf notch and harmonic comb), with persistent per-track verdict caches; tap a format or category to jump to the matching smart playlist
- **Lossless upgrade** — for a suspected fake-lossless local track, tap its format info bar to search online originals (switchable source) and download the lossless version to replace the local file
- **Playlist sync from a share link** — paste a platform share link (Netease / QQ / Kuwo) to fetch a remote playlist, skip local duplicates, download the rest at the highest available quality into the library and create a playlist (Netease is parsed built-in; other platforms require a proxy source)
- **Playback speed control** — real-time playback speed adjustment via a dialog (±0.1 steps, tap the value to reset), processed natively by AudioTrack
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
| Home | Permission onboarding dialog (auto-hides once all are granted), immersive player with a rotating disc cover on a cover-colored gradient background, 5-line synced lyrics (font size & line count adjustable), refreshable playlist, favorites, sleep timer, landscape mode, online search (5 platforms with quality selection) via right swipe and playlist panel via left swipe, vertical swipe to switch tracks (long-press the cover / title for cover & lyrics refresh and rename; long-press the playlist button for library analysis; tap the format info bar to upgrade a suspicious track to lossless) |
| Settings | Appearance (theme), Language, Playback (mini player / word-by-word rendering / swipe to change track), Typography (lyric font size & lines), Proxy Source (import / enable / remove third-party sources), About (version, update check, GitHub link) |

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.10 |
| UI | Jetpack Compose (BOM 2026.08.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.0 + MediaSessionService |
| Navigation | AndroidX Navigation3 1.1.7 (typed routes) |
| DI | Koin 4.2.2 |
| Persistence | DataStore Preferences 1.2.1 |
| Image loading | Coil 3.6.1 |
| Network | OkHttp 5.5.0 |
| Serialization | kotlinx.serialization 1.11.0 |
| Adaptive layout | androidx.window 1.5.1, material3-adaptive 1.3.0 |
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Build | AGP 9.3.2, Gradle 9.7.1, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Data layer
│       │   │   ├── music/               #   Music scanning / playback stores / online sources / proxy source
│       │   │   │   ├── api/             #     Online music sources (Netease / QQ / Kugou / Kuwo / Migu)
│       │   │   │   ├── metadata/        #     Cover management & metadata read/write
│       │   │   │   ├── model/           #     Track data models
│       │   │   │   └── proxy/           #     Proxy source (import / parse / engine / store / playlist sync)
│       │   │   ├── playlist/            #   Custom playlist entity & store
│       │   │   ├── permission/          #   Permission & overlay-grant monitors
│       │   │   ├── repository/          #   Settings repository
│       │   │   └── settings/            #   Settings DataStore & lyric layout preferences
│       │   ├── di/                      # Koin modules
│       │   ├── dialog/                  # Floating-panel dialogs (search / rename / timer / speed / settings / update)
│       │   ├── domain/music/            # Domain layer (playback state / download / spectral analysis / utils)
│       │   ├── floatingwindow/          # Floating panel & mini player (view managers + permission flow)
│       │   │   ├── mini_player/         #   Mini player overlay, bar & view manager
│       │   │   └── music_panel/         #   Full music panel view manager
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 typed routes
│       │   ├── screens/                 # Screens (home / settings / typography)
│       │   │   ├── home/                #   Home player (compact / expanded areas + shared components)
│       │   │   │   ├── compact/         #     Portrait player area
│       │   │   │   ├── expanded/        #     Landscape player area
│       │   │   │   ├── component/       #     Shared areas (playlist / online search / permissions / swipe)
│       │   │   │   └── dialog/          #     Home dialogs (playlist import / lossless upgrade)
│       │   │   └── settings/            #   Appearance / language / playback / proxy source / about
│       │   │       ├── settings_assembly/  #     Settings areas (per section)
│       │   │       ├── typography/      #     Typography sub-screen
│       │   │       └── dialog/          #     Settings dialogs (theme / language / proxy import)
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (music panel composables: cover / lyrics / controls)
│       │   ├── update/                  # Version check & in-app update
│       │   ├── utils/localization/      # In-app localization manager
│       │   ├── YiChaoActivity.kt
│       │   └── YiChaoApplication.kt
│       └── res/                         # Resources (values / values-en)
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependencies)
│   └── wrapper/
├── docs/                                # Architecture notes & proxy source spec (忆潮代理音源规范.md)
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

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`); feature-specific code stays inside the feature module. The playback domain lives in `domain/music` (state, download, spectral analysis, helpers) backed by the `data/music` layer and exposed to the UI through a window-level `MusicPanelStateHolder`; the floating UI (full panel + mini player) lives in `floatingwindow/` (view managers, split into `music_panel/` and `mini_player/`) with composables under `ui/music/`, while playback runs in `service/MusicPlaybackService` (Media3 ExoPlayer + `MediaSessionService`).

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates |
| Foreground service (`mediaPlayback`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | Background playback with notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Update download completion notification (Android 13+) |

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

Online music search relies on third-party public web endpoints (Netease / QQ Music / Kugou / Kuwo / Migu), whose availability and playback policy may vary by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- Lyric animations and NetEase cloud music parsing originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- Drag-reorder of list items originally referenced from [Reorderable](https://github.com/Calvin-LL/Reorderable); now self-implemented in-app (algorithm-equivalent)
- QQ Music, Kugou, Kuwo and Migu Kotlin-native audio source parsing is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
