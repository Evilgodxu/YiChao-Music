<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="忆潮音乐" />

# 忆潮音乐

**一款现代化的 Android 音乐播放器,支持悬浮音乐面板、迷你播放器、歌单管理、多平台在线搜索与播放变速。**

[English](README.md) | **简体中文**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![AGP](https://img.shields.io/badge/AGP-9.3.2-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-30-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**忆潮音乐(YiChao Music)** 是一款基于 Jetpack Compose 构建的全功能 Android 音乐播放器。除常规应用内播放外,它还提供可悬浮于任意应用之上的**悬浮音乐面板**与**迷你播放器**——无论正在游戏、浏览器还是其他界面,音乐始终触手可及。

## 特性

- **悬浮音乐面板**:以系统悬浮窗(SYSTEM_ALERT_WINDOW)形式渲染的全功能播放面板,可在任意应用上层使用
- **迷你播放器**:应用退到后台播放时显示的紧凑悬浮条,显示当前歌词,点击即可展开回完整面板,可在设置中开关
- **本地曲库**:基于 MediaStore 扫描设备存储,提取内嵌封面与歌词,并支持通过 `VIEW`/`SEND` 意图与系统文件选择器导入音频
- **多平台在线搜索**:聚合网易云、QQ 音乐、酷狗、酷我、咪咕五平台搜索,支持搜索历史、音质选择(无损 / 高品 / 标准)与在线缓存(下载到系统下载目录,缓存完成后自动切换到本地播放)
- **代理音源**:在「设置 → 代理音源」中导入第三方聚合音源(本地文件 / 链接 / 文本),可定制各平台的搜索、播放地址、歌词与封面解析,支持启用 / 停用 / 移除,失败自动回退内置解析;JSON 规范见 [忆潮代理音源规范](docs/忆潮代理音源规范.md)
- **歌单系统**:智能歌单(常听 / 收藏 / 专辑 / 歌手)与自定义歌单(新建 / 重命名 / 删除 / 批量添加歌曲 / 拖拽排序 / 快捷切换),JSON 持久化
- **同步歌词**:滚动歌词 + 逐字级时间轴(可开关),支持在线歌词匹配 / 刷新、本地歌词导入与内嵌歌词,并可对歌词时间进行微调
- **歌词排版**:音乐面板 / 首页竖屏 / 首页横屏三场景独立调节歌词字号与显示行数(横屏另含 3D 强度),在「设置 → 排版」中调整
- **封面管理**:内嵌封面、本地图片候选与在线封面搜索,新封面可写回音频文件
- **元数据编辑**:重命名歌曲名 / 艺术家并写回文件标签,支持一键复制
- **音源格式显示**:在进度区展示当前播放音源的格式信息(容器格式、位深、采样率、码率)
- **播放变速**:通过对话框实时调节播放速度(±0.1 步进,点击数值重置),由 AudioTrack 原生处理
- **播放控制**:基于 Media3 媒体会话,支持通知栏 / 锁屏控制、播放模式(列表循环 / 单曲循环 / 随机)、收藏置顶、下一首播放与定时关闭(当前曲目播完即停)
- **首页手势交互**:右滑呼出在线搜索、左滑呼出歌单面板,上下滑动切歌(可开关);沉浸式横屏模式,支持旋转碟片与自动隐藏的悬浮控制栏
- **自适应布局**:基于 WindowSizeClass 的响应式界面
- **状态持久化**:重启后恢复播放列表、播放位置与播放模式
- **主题与多语言**:跟随系统 / 浅色 / 深色主题(切换带圆形扩散过渡动效);应用内简体中文 / English / 跟随系统热切换,无需重建 Activity
- **崩溃日志**:未捕获异常与捕获异常写入应用专属外部目录,超期自动清理
- **应用内更新**:回到前台时每日自动检查 GitHub Releases(也可在「关于」页手动检查),有新版时弹出带更新日志的对话框,可应用内下载并直接安装,也可在浏览器中打开

## 页面

| 页面 | 内容 |
| --- | --- |
| 首页 | 权限引导对话框(全部授权后自动关闭)、沉浸式播放器(旋转碟片封面 + 封面取色渐变背景)、5 行同步歌词(字号与行数可调)、可刷新的播放列表、收藏、定时关闭、横屏模式、右滑在线搜索(五平台切换 + 音质选择)与左滑歌单面板、上下滑动切歌(长按封面 / 标题可刷新封面、歌词及重命名) |
| 设置 | 外观(主题)、语言、播放(迷你播放器 / 逐字渲染 / 滑动切歌)、排版(歌词字号与显示行数)、代理音源(导入 / 启停 / 移除第三方音源)、关于(版本、检查更新、GitHub 链接) |

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.4.10 |
| UI | Jetpack Compose(BOM 2026.08.00)+ Material 3 |
| 播放 | Media3 ExoPlayer 1.11.0 + MediaSessionService |
| 导航 | AndroidX Navigation3 1.1.7(类型安全路由) |
| 依赖注入 | Koin 4.2.2 |
| 持久化 | DataStore Preferences 1.2.1 |
| 图片加载 | Coil 3.6.1 |
| 网络 | OkHttp 5.5.0 |
| 序列化 | kotlinx.serialization 1.11.0 |
| 自适应布局 | androidx.window 1.5.1、material3-adaptive 1.3.0 |
| 生命周期 | androidx.lifecycle 2.11.0、activity-compose 1.13.0 |
| 构建 | AGP 9.3.2、Gradle 9.7.1、refreshVersions |

## 项目结构

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # 数据层
│       │   │   ├── music/               #   曲库扫描 / 在线音源 / 元数据 / 代理音源
│       │   │   │   ├── api/             #     在线音乐源(网易云 / QQ / 酷狗 / 酷我 / 咪咕)
│       │   │   │   ├── metadata/        #     封面管理与元数据读写
│       │   │   │   ├── model/           #     曲目数据模型
│       │   │   │   └── proxy/           #     代理音源(导入 / 解析 / 引擎 / 存储)
│       │   │   ├── permission/          #   权限与悬浮窗授权监控
│       │   │   ├── repository/          #   设置仓库
│       │   │   └── settings/            #   设置 DataStore 与歌词排版偏好
│       │   ├── di/                      # Koin 模块
│       │   ├── dialog/                  # 悬浮窗内对话框(搜索 / 重命名 / 定时 / 变速 / 设置 / 更新)
│       │   ├── domain/music/            # 领域层(播放状态 / 下载 / 信号路径 / 工具)
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 类型安全路由
│       │   ├── overlay/                 # 悬浮窗 / 迷你播放器 UI 与视图管理(含权限流程)
│       │   ├── screens/                 # 页面(首页 / 设置)
│       │   │   ├── home/                #   首页播放器 + 权限流程 + 歌单 + 在线搜索
│       │   │   └── settings/            #   外观 / 语言 / 播放 / 排版 / 代理音源 / 关于
│       │   ├── service/                 # MediaSessionService 播放引擎
│       │   ├── theme/                   # Material 3 配色与字体
│       │   ├── ui/                      # 全局共享 UI(自适应布局 / 图标 / 音乐面板组件)
│       │   ├── update/                  # 检查更新与应用内更新
│       │   ├── utils/localization/      # 应用内多语言管理
│       │   ├── YiChaoActivity.kt
│       │   └── YiChaoApplication.kt
│       └── res/                         # 资源(values / values-en)
├── gradle/
│   ├── libs.versions.toml               # 版本目录(依赖管理)
│   └── wrapper/
├── docs/                                # 架构说明与代理音源规范(代理音源开发规范.md)
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 架构

应用遵循 **MVVM + 单向数据流**:状态由 `ViewModel` → `UiState` → UI 自上而下流动,事件由 UI 自下而上传递;共享数据逻辑位于 `data/` 层并通过 Repository 暴露,全部由 Koin 组装。

页面代码采用**分区架构(assembly/area 模式)**:

- `{Screen}Screen.kt` — 页面入口,负责将 ViewModel 与 UI 关联
- `{Screen}Assembly.kt` — 页面分区组装器,编排各分区
- `{Name}Area.kt` — 语义单一、自包含的 UI 分区

被两个及以上功能复用的代码上提至顶层(`data/`、`theme/`、`utils/`、`ui/`),仅单页使用的代码保留在页面模块内。播放领域逻辑位于 `domain/music`(状态 / 下载 / 工具),底层由 `data/music` 层支撑,并通过窗口级 `MusicPanelStateHolder` 暴露给 UI;悬浮 UI(完整面板 + 迷你播放器)拆分为 `overlay/`(视图管理)与 `ui/music`(可组合项),实际播放由 `service/MusicPlaybackService`(Media3 ExoPlayer + `MediaSessionService`)驱动。

## 权限

| 权限 | 用途 |
| --- | --- |
| 悬浮窗 | 悬浮音乐面板与迷你播放器 |
| 全部文件访问 | 导入与管理本地音乐文件 |
| 音乐访问(`READ_MEDIA_AUDIO`) | 读取设备曲库并播放 |
| 图片(`READ_MEDIA_IMAGES`) | 内嵌封面与本地封面候选 |
| 前台服务(`mediaPlayback`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | 后台播放 + 通知栏 / 锁屏控制 |
| 通知(`POST_NOTIFICATIONS`) | 版本更新下载完成通知(Android 13+) |

权限通过一个透明引导页链式申请,全部授予后自动关闭。

## 快速开始

### 环境要求

- JDK 21
- Android Studio(建议最新稳定版)
- 包含 API 37(`compileSdk`)的 Android SDK

### 构建

```bash
git clone https://github.com/Evilgodxu/YiChao-Music.git
cd YiChao-Music

# 调试包
./gradlew assembleDebug

# 发布包(需先配置签名,见下文)
./gradlew assembleRelease
```

APK 输出为 `app/build/outputs/apk/` 下的 `YiChaoMusic-<版本号>-arm64.apk`,仅构建 `arm64-v8a` ABI。

### 发布签名

Release 构建从项目根目录的 `local.properties` 读取签名凭据:

```properties
KEYSTORE_PASSWORD=你的签名库密码
KEY_ALIAS=jh
KEY_PASSWORD=你的别名密码
```

签名库文件默认位于项目根目录 `jh.keystore`(如需调整请修改 `app/build.gradle.kts` 中的 `storeFile`)。两个文件均已被 git 忽略,请勿提交。

## 免责声明

在线音乐搜索依赖第三方公共网络接口(网易云 / QQ 音乐 / 酷狗 / 酷我 / 咪咕),其可用性与播放策略可能随地区与歌曲而异。应用仅供个人学习交流使用,请支持正版版权方。

## 致谢

- 歌词动效与网易云音乐解析早期参考 [Qplayer](https://github.com/TIMER-err/qplayer)
- 列表拖拽排序基于 [Reorderable](https://github.com/Calvin-LL/Reorderable)实现
- 基于 [musicdl](https://github.com/CharlesPikachu/musicdl) 实现 QQ 酷狗 酷我 咪咕 的 Kotlin 原生音源解析

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
