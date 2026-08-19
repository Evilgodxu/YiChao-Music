<div align="center">

# Template

**一个开箱即用的现代化 Android 应用模板，基于 Jetpack Compose、MVVM 与分区架构构建。**

[English](README.md) | **简体中文**

![License](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![AGP](https://img.shields.io/badge/AGP-9.3.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-32-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**Template** 是一个可直接上手的 Android 应用起点工程。工程骨架与常用基础设施已搭建完毕，你可以专注于业务功能，而非重复搭建样板代码。

## 特性

- **单 Activity 架构**：基于 Jetpack Compose + Material 3，支持边到边（edge-to-edge）渲染
- **MVVM + 单向数据流**：每个页面由 `UiState` + `ViewModel` 驱动，状态自上而下、事件自下而上
- **分区架构组织代码**：页面按语义拆分为组装器与分区（`{Name}Assembly` / `{Name}Area`），结构清晰、便于定位与复用
- **Navigation3 导航**：类型安全路由 + 显式返回栈
- **Koin 依赖注入**：在 `Application.onCreate` 中启动
- **DataStore Preferences 持久化**：统一管理应用设置
- **主题模式**：跟随系统 / 浅色 / 深色，切换时带圆形扩散过渡动效
- **应用内多语言**：简体中文 / English / 跟随系统，运行时热切换、无需重建 Activity（已禁用按语言分包，保证切换始终生效）
- **崩溃日志管理**：未捕获异常与捕获异常写入应用专属外部目录，超期自动清理，并链式调用系统默认处理器
- **构建优化**：Release 启用 R8 + 资源压缩、签名构建，仅打 `arm64-v8a` ABI，APK 输出命名固定

## 页面

| 页面 | 内容 |
| --- | --- |
| 首页 | 欢迎与项目简介卡片，设置入口 |
| 设置 | 外观（主题）、语言、关于（版本 + GitHub 链接） |

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.4.10 |
| UI | Jetpack Compose（BOM 2026.08.00）+ Material 3 |
| 导航 | AndroidX Navigation3 1.1.6 |
| 依赖注入 | Koin 4.2.2 |
| 持久化 | DataStore Preferences 1.2.1 |
| 序列化 | kotlinx.serialization 1.11.0 |
| 生命周期 | androidx.lifecycle 2.11.0、activity-compose 1.13.0 |
| 构建 | AGP 9.3.1、Gradle 9.7.0、refreshVersions 0.60.6 |

## 项目结构

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/template/evilgodxu/
│       │   ├── data/                    # 数据层（DataStore）
│       │   │   ├── repository/          #   SettingsRepository
│       │   │   └── settings/            #   设置状态与键
│       │   ├── di/                      # Koin 模块
│       │   ├── log/                     # 崩溃日志管理
│       │   ├── navigation/              # Navigation3 类型安全路由
│       │   ├── screens/                 # 页面模块
│       │   │   ├── home/                #   首页
│       │   │   │   └── home_assembly/   #     HomeAssembly + 分区
│       │   │   └── settings/            #   设置页
│       │   │       ├── settings_assembly/
│       │   │       └── dialog/          #     选择弹窗
│       │   ├── theme/                   # Material 3 配色与字体
│       │   ├── utils/localization/      # 应用内多语言管理
│       │   ├── TemplateActivity.kt
│       │   └── TemplateApplication.kt
│       └── res/                         # 资源（values / values-en）
├── gradle/
│   ├── libs.versions.toml               # 版本目录（依赖管理）
│   └── wrapper/
├── docs/                                # 架构说明
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 架构

应用采用 **MVVM + 单向数据流**：状态由 `ViewModel` → `UiState` → UI 自上而下流动，事件由 UI 自下而上传递；共享数据逻辑位于 `data/` 层并通过 Repository 暴露，全部由 Koin 组装。

代码遵循 **分区架构（assembly/area 模式）**：

- `{ScreenName}Screen.kt` — 页面入口，负责将 ViewModel 与 UI 关联
- `{ScreenName}Assembly.kt` — 页面分区组装器，编排各分区
- `{Name}Area.kt` — 语义单一、自包含的 UI 分区

通用能力（`data/`、`theme/`、`utils/`）被两个及以上页面复用时上提至顶层；仅单页使用的代码保留在页面模块内。

## 快速开始

### 环境要求

- JDK 21
- Android Studio（建议最新稳定版）
- 包含 API 37（`compileSdk`）的 Android SDK

### 构建

```bash
git clone https://github.com/Evilgodxu/android-template.git
cd android-template

# 调试包
./gradlew assembleDebug

# 发布包（需先配置签名，见下文）
./gradlew assembleRelease
```

APK 输出为 `app/build/outputs/apk/` 下的 `Template-<版本号>-arm64.apk`。

### 发布签名

Release 构建从项目根目录的 `local.properties` 读取签名凭据：

```properties
KEYSTORE_PASSWORD=你的签名库密码
KEY_ALIAS=jh
KEY_PASSWORD=你的别名密码
```

签名库文件默认位于项目根目录 `jh.keystore`（如需调整请修改 `app/build.gradle.kts` 中的 `storeFile`）。`jh.keystore` 与 `local.properties` 已被 git 忽略，请勿提交。

## 定制模板

- **修改应用名 / 包名**：同步修改 `app/build.gradle.kts` 中的 `namespace` 与 `applicationId`，移动 `app/src/main/kotlin/` 下对应源码目录，并更新 Manifest，避免与现有 `com.template.evilgodxu` 冲突
- **应用显示名**：修改 `app/src/main/res/values/strings.xml` 中的 `app_name`
- **主题配色**：修改 `app/src/main/kotlin/.../theme/Color.kt`
- **支持的 ABI**：调整 `app/build.gradle.kts` 中的 `ndk.abiFilters`（当前仅 `arm64-v8a`）
- **新增页面**：在 `screens/<name>/` 下创建页面模块（`UiState` + `ViewModel` + `Assembly`/`Area`），在 `navigation/Screen.kt` 注册路由，并加入 `AppNavHost`

## License

[MIT](LICENSE) © 2026 Evilgodxu
