# AndResGuard

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dawnuu/andresguard-plugin)](https://central.sonatype.com/artifact/io.github.dawnuu/andresguard-plugin)

[English](README.md) | [简体中文](README.zh-CN.md)

支持 AAB/APK 资源混淆、重复资源合并、图片无损压缩、文件/文案过滤和签名。

## 迁移教程

从 AabResGuard 切换到当前插件，主要修改接入名称和执行命令：

| 项目 | AabResGuard | AndResGuard |
| --- | --- | --- |
| 插件 ID | `io.github.dawnuu.aabresguard` | `io.github.dawnuu.andresguard` |
| buildscript 依赖 | `io.github.dawnuu:aabresguard-plugin` | `io.github.dawnuu:andresguard-plugin:1.1.1` |
| 配置块 | `aabResGuard { ... }` | `andResGuard { ... }` |
| 处理任务 | `aabresguardRelease` | `andresguardRelease` |
| Kotlin 扩展类型 | `AabResGuardExtension` | `AndResGuardExtension` |
| 文件名项目属性 | `aabResGuard.fileName` | `andResGuard.fileName` |
| 文件名模板 | `obfuscatedBundleFileName` | `obfuscatedFileName`（APK/AAB 共用） |
| 自定义变量项目属性 | `aabResGuard.var.NAME` | `andResGuard.var.NAME` |

已有白名单、压缩、过滤等配置可以继续复用。`bundleRelease` 自动处理 AAB，`assembleRelease` 自动处理 APK。独立任务 `andresguardRelease` 按 `format` 选择处理范围，不配置时处理 AAB；需要 APK 或两种格式时设置 `apk` 或 `all`。

## 接入教程

保留 Gradle Plugin 和命令行两种接入方式。

### 一、Gradle Plugin

#### 1. 接入插件

推荐使用 Gradle plugins DSL 和版本目录接入。

在 `settings.gradle` 或 `settings.gradle.kts` 配置仓库，以下写法同时适用于 Groovy/Kotlin DSL：

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

在 `gradle/libs.versions.toml` 中声明版本和插件：

```toml
[versions]
andresguard = "1.1.1"

[plugins]
andresguard = { id = "io.github.dawnuu.andresguard", version.ref = "andresguard" }
```

在根工程声明，在应用模块启用；下面的写法同时适用于 Groovy/Kotlin DSL：

```groovy
// 根工程 build.gradle / build.gradle.kts
plugins {
    alias(libs.plugins.andresguard) apply false
}

// 应用模块 build.gradle / build.gradle.kts
plugins {
    id("com.android.application")
    alias(libs.plugins.andresguard)
}
```

> 也可以继续使用旧版 `buildscript` 方式接入：
>
> **Groovy DSL：**
>
> ```gradle
> buildscript {
>   repositories {
>     mavenCentral()
>     google()
>   }
>   dependencies {
>     classpath "io.github.dawnuu:andresguard-plugin:1.1.1"
>   }
> }
> ```
>
> 在 application module 中启用插件：
>
> ```gradle
> apply plugin: "io.github.dawnuu.andresguard"
> ```
>
> **Kotlin DSL（.kts）：**
>
> ```kotlin
> buildscript {
>   repositories {
>     mavenCentral()
>     google()
>   }
>   dependencies {
>     classpath("io.github.dawnuu:andresguard-plugin:1.1.1")
>   }
> }
> ```
>
> 在 application module 中启用插件：
>
> ```kotlin
> apply(plugin = "io.github.dawnuu.andresguard")
> ```

#### 2. 配置参数

在 `build.gradle(application)` 中添加 `andResGuard` 配置块：

**Groovy DSL：**

```gradle
andResGuard {
    // 输出格式：aab、apk、all，默认 aab
    format = "aab"
    // 是否开启资源混淆，默认 true
    enableObfuscate = true
    // 是否生成随机资源名称，默认 false
    useRandomName = false
    // 是否给媒体文件追加字节以修改 MD5，默认 false
    enableMutateMd5 = false
    // 默认稳定修改（相同输入得到相同结果）；改变种子可得到另一份稳定结果
    mutateMd5Seed = ""
    // 找不到签名配置时是否回退到 debug 签名，默认 false（直接构建失败）
    allowDebugSignFallback = false
    // 是否允许去除重复资源，默认 false
    mergeDuplicatedRes = true
    // 无损压缩 PNG/JPEG/GIF/静态 WebP，默认 false
    enableCompressImages = false
    // 可选：覆盖内置压缩工具；留空使用随 JAR 打包的工具
    imageCompressionToolsDir = ""
    // 是否允许过滤文件，默认 false
    enableFilterFiles = false
    // 是否允许过滤文案，默认 false
    enableFilterStrings = false
    // 是否移除 AAB 的 BUNDLE-METADATA，默认 true
    removeBundleMetadata = true
    // 仅 AAB：是否移除 base/root 目录，默认 false
    // 开启后需自行测试稳定性
    removeRootFiles = false
    // removeBundleMetadata 开启时，保留匹配的 BUNDLE-METADATA 文件
    bundleMetaDataWhiteList = [
        "com.example.metadata"
    ]

    // 用于增量混淆的 mapping 文件（可选）
    // mappingFile = file("mapping.txt").toPath()

    // 白名单规则，自定义的白名单会与内置白名单合并
    whiteList = [
        "*.R.raw.*",
        "*.R.drawable.icon"
    ]

    // AAB/APK 共用文件名模板，后缀按输出格式自动匹配
    // 支持变量: {applicationId} {versionName} {versionCode} {variantName} {buildTime} {format}
    // 例如 "{applicationId}_{versionName}_{buildTime}.aab"
    // 外部覆盖: -PandResGuard.fileName="{applicationId}_{channel}"
    // 以及 -PandResGuard.var.channel=google（命令行或 gradle.properties，优先于本文件）
    // 自定义变量，例如 fileNameVariables = [channel: "google"] -> "{applicationId}_{channel}.aab"
    // 默认文件名为: {applicationId}_{versionName}_{versionCode}.aab / .apk
    obfuscatedFileName = "duplicated-app"

    // 文件过滤规则
    filterList = [
        "*/arm64-v8a/*",
        "BUNDLE-METADATA/*"
    ]

    // 过滤文案列表路径，相对于当前工作目录（可选）
    unusedStringPath = "unused.txt"

    // 保留 en,en-xx,zh,zh-xx 等语言，其余均删除（可选）
    languageWhiteList = ["en", "zh"]

    // 默认关闭 VirusTotal 上传，不要将 API key 硬编码到仓库
    enableVirusTotalUpload = false
    virusTotalApiKey = System.getenv("VIRUSTOTAL_API_KEY") ?: ""
}
```

**Kotlin DSL（.kts）：**

```kotlin
andResGuard {
    // 输出格式：aab、apk、all，默认 aab
    format = "aab"
    // 是否开启资源混淆，默认 true
    enableObfuscate = true
    // 是否生成随机资源名称，默认 false
    useRandomName = false
    // 是否给媒体文件追加字节以修改 MD5，默认 false
    enableMutateMd5 = false
    // 默认稳定修改（相同输入得到相同结果）；改变种子可得到另一份稳定结果
    mutateMd5Seed = ""
    // 是否允许去除重复资源，默认 false
    mergeDuplicatedRes = true
    enableCompressImages = false
    imageCompressionToolsDir = ""
    // 是否允许过滤文件，默认 false
    enableFilterFiles = false
    // 是否允许过滤文案，默认 false
    enableFilterStrings = false
    // 是否移除 AAB 的 BUNDLE-METADATA，默认 true
    removeBundleMetadata = true
    // 仅 AAB：是否移除 base/root 目录，默认 false
    // 开启后需自行测试稳定性
    removeRootFiles = false
    // removeBundleMetadata 开启时，保留匹配的 BUNDLE-METADATA 文件
    bundleMetaDataWhiteList = setOf(
        "com.example.metadata"
    )

    // 用于增量混淆的 mapping 文件（可选）
    // mappingFile = file("mapping.txt").toPath()

    // 白名单规则，自定义的白名单会与内置白名单合并
    whiteList = setOf(
        "*.R.raw.*",
        "*.R.drawable.icon"
    )

    // AAB/APK 共用文件名模板，后缀按输出格式自动匹配
    // 支持变量: {applicationId} {versionName} {versionCode} {variantName} {buildTime} {format}
    // 例如 "{applicationId}_{versionName}_{buildTime}.aab"
    // 外部覆盖: -PandResGuard.fileName="{applicationId}_{channel}"
    // 以及 -PandResGuard.var.channel=google（命令行或 gradle.properties，优先于本文件）
    // 自定义变量，例如 fileNameVariables = [channel: "google"] -> "{applicationId}_{channel}.aab"
    // 默认文件名为: {applicationId}_{versionName}_{versionCode}.aab / .apk
    obfuscatedFileName = "duplicated-app"

    // 文件过滤规则
    filterList = setOf(
        "*/arm64-v8a/*",
        "BUNDLE-METADATA/*"
    )

    // 过滤文案列表路径，相对于当前工作目录（可选）
    unusedStringPath = "unused.txt"

    // 保留 en,en-xx,zh,zh-xx 等语言，其余均删除（可选）
    languageWhiteList = setOf("en", "zh")

    // 默认关闭 VirusTotal 上传，不要将 API key 硬编码到仓库
    enableVirusTotalUpload = false
    virusTotalApiKey = System.getenv("VIRUSTOTAL_API_KEY") ?: ""
}
```

文件名模板可以省略后缀，也可以继续写原来的 `.aab` 或 `.apk` 后缀；插件按输出格式自动匹配。`format = "all"` 时，一份模板生成同名的 AAB 和 APK，分别存入各自目录。

#### 3. 执行混淆

统一命令按 `format` 选择打包和处理任务，默认只注册 Release 变体：

```bash
./gradlew :app:andresguardRelease
# 标准 Android 任务也会自动处理资源：
./gradlew :app:bundleRelease    # AAB
./gradlew :app:assembleRelease  # APK
# 有 flavor 时，例如：
./gradlew :app:andresguardFreeRelease
```

| format | 处理范围 |
| --- | --- |
| 不配置 / `aab` | AAB |
| `apk` | APK |
| `all` | 同时处理 AAB 和 APK |

`format` 仅控制 `andresguard<Variant>`。`bundle<Variant>` 始终处理 AAB，`assemble<Variant>` 始终处理 APK，同样支持 flavor 变体。同一次构建执行多个入口时，每种格式只处理一次；资源处理失败会使构建失败，debug 变体不受影响。

APK 直接处理二进制 `resources.arsc` 和包内文件，不转换为 AAB。混淆、合并、压缩及文案过滤等配置共用；`removeBundleMetadata`、`bundleMetaDataWhiteList`、`removeRootFiles` 仅对 AAB 生效。APK 的文件过滤规则匹配其实际包内路径。

Release 变体需要有效 `signingConfig`。无签名配置时默认报错；`allowDebugSignFallback = true` 仅用于测试。

#### 4. 产物位置

处理后的产物统一放在应用模块的 `build/outputs/andresguard/`，APK、AAB 和各自的 mapping 分目录保存，不覆盖 Android 原始产物。以 `app` 模块、`release` 变体为例：

```text
app/build/outputs/
├── andresguard/
│   ├── aab/release/
│   │   ├── <文件名>.aab
│   │   ├── resources-mapping.txt
│   │   └── base-duplicated.txt
│   └── apk/release/
│       ├── <文件名>.apk
│       ├── resources-mapping.txt
│       └── base-duplicated.txt
├── bundle/release/       # Android 原始 AAB
└── apk/release/          # Android 原始 APK
```

`resources-mapping.txt` 在启用资源混淆时生成，`base-duplicated.txt` 在启用重复资源合并时生成。每种格式使用自己的报告目录。

- 默认文件名：`{applicationId}_{versionName}_{versionCode}.aab` / `.apk`。
- 自定义文件名：`obfuscatedFileName`；外部覆盖使用 `andResGuard.fileName`。
- 有 flavor 时，AndResGuard 目录使用完整变体名，例如 `aab/freeRelease/`、`apk/freeRelease/`；原始产物目录由 AGP 决定。
- 仅配置 `aab` 或 `apk` 时只生成相应的新产物；旧产物不会自动删除。
- 命令行工具的输出位置由 `--output` 指定。

### 图片无损压缩

可选配置 `imageCompressionCacheDir = rootProject.file("custom-cache/images").toPath()` 来指定其他缓存目录；默认不需要设置。若希望 `clean` 后仍可复用，请选择 `build/` 之外的目录。

Gradle 插件自动将成功的压缩结果缓存在项目根目录的 `.gradle/andresguard/image-cache/`，`gradlew clean` 不会清除它。每次打包仍会读取原图；内容相同且压缩参数、工具内容和平台未改变时，直接将缓存图片写入新的 AAB/APK，不修改源文件。不再缩小的结果也会缓存，压缩失败则不缓存。日志中的 `cached` 表示命中次数。缓存损坏时重新压缩，缓存读写失败时警告并继续；需要强制重新压缩时删除该目录即可。命令行默认不使用持久缓存。

设置 `enableCompressImages = true`，即可处理所有模块 `res/` 和 `assets/` 下的 PNG、JPG/JPEG、GIF（含动画）和静态 WebP。保持原格式和路径，不降低分辨率或画质，保留工具支持的元数据；仅当文件变小时替换。已优化的图片可能不再缩小，解码后的内存占用通常不变。

压缩工具已随 JAR 打包，无需额外安装、下载或配置目录。支持 macOS ARM64（Apple Silicon）/x86_64（Intel）、Windows x86_64 和 Linux x86_64（glibc，非 Alpine/musl）。运行时自动选择平台，在本次处理的临时目录解压并校验 SHA-256，处理结束后清理。关闭图片压缩时不会解压工具。

`imageCompressionToolsDir` 默认为空，表示使用内置工具；仅在需要覆盖工具版本或使用其他平台时设置，例如 `imageCompressionToolsDir = "/opt/homebrew/bin"`。压缩工具被系统阻止、缺失、失败或超时时，输出 `[AndResGuard WARNING]` 警告，保留原图并继续混淆和打包。同一格式失败后，本次处理跳过该格式后续压缩，避免重复启动和警告。临时目录创建或清理失败也只输出警告；主动取消任务仍会中断。

Nine-patch（包括带 Android `np*` 块的编译图片）、APNG 和动画 WebP 会跳过并记录。其他格式（如 BMP、SVG）保持原样。日志输出优化数量、未缩小数量、跳过数量、因失败保留原图的数量和节省字节数；该字节数是图片文件大小差值，最终 AAB/APK 大小还受 ZIP 压缩影响。压缩在资源混淆及 MD5 修改之前执行。

命令行只需增加 `--compress-images=true`；`--image-compression-tools-dir` 是可选覆盖参数。此功能通过命令行参数或 Gradle 配置启用，XML 配置暂不支持。工具版本、来源、许可证及重新生成方法见 [内置工具说明](core/src/main/resources/image-tools/NOTICE.md)。

内置 macOS 工具未进行 Developer ID 公证；若构建机阻止执行，可用 imageCompressionToolsDir 指向获准运行的工具。

### 二、命令行工具

#### 1. 构建 JAR

```bash
./gradlew :core:shadowJar
```

生成 `core/build/libs/AndResGuard-1.1.1.jar`，以下命令按 JAR 已复制为当前目录的 `AndResGuard.jar` 举例。

#### 2. 准备配置文件

命令行工具需要一个 XML 配置文件来定义白名单和过滤规则，示例如下：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<resguard>
    <!-- 白名单规则 -->
    <issue id="whitelist" isactive="true">
        <path value="*.R.raw.*"/>
        <path value="*.R.drawable.icon"/>
    </issue>

    <!-- 文件过滤规则（可选） -->
    <filter isactive="true">
        <rule value="*/arm64-v8a/*"/>
        <rule value="BUNDLE-METADATA/*"/>
    </filter>

    <!-- 文案过滤配置（可选） -->
    <filter-str isactive="true">
        <path value="unused.txt"/>
        <language value="en"/>
        <language value="zh"/>
    </filter-str>
</resguard>
```

XML 解析器只识别上面示例中的 `issue`、`filter` 和 `filter-str` 元素。`useRandomName`、`enableMutateMd5`、VirusTotal 上传和 BUNDLE-METADATA 白名单目前仅支持 Gradle 插件配置，命令行没有对应参数。

文件名模板可以省略后缀，也可以继续写原来的 `.aab` 或 `.apk` 后缀；插件按输出格式自动匹配。`format = "all"` 时，一份模板生成同名的 AAB 和 APK，分别存入各自目录。

#### 3. 执行混淆

```cmd
java -jar AndResGuard.jar obfuscate \
  --input=app.aab \
  --output=obfuscated-app.aab \
  --config=config.xml \
  --merge-duplicated-res=true \
  --remove-bundle-metadata=true \
  --remove-root-files=false
```

APK 使用同一命令，将 `--input` / `--output` 改为 `.apk`。使用 `--build-tools-dir=/path/to/sdk/build-tools/35.0.0` 指定工具目录，或设置 ANDROID_HOME / ANDROID_SDK_ROOT。

**必选参数：**

| 参数 | 说明 |
|------|------|
| `--input` | 输入 AAB 或普通单 APK 的路径 |
| `--output` | 处理后输出路径，后缀须与输入格式相同 |
| `--config` | 配置文件路径 |

**可选参数：**

| 参数 | 说明 |
|------|------|
| `--mapping` | 增量混淆的 mapping 文件路径 |
| `--build-tools-dir` | APK 的 Android SDK Build Tools 35+ 目录 |
| `--compress-images=true` | 启用无损图片压缩 |
| `--image-compression-tools-dir` | 可选：覆盖内置图片工具目录 |
| `--merge-duplicated-res=true` | 去除重复资源 |
| `--remove-bundle-metadata=true` | 移除 BUNDLE-METADATA |
| `--remove-root-files=true` | 仅 AAB：移除 base/root 目录文件 |
| `--disable-sign=true` | 禁用签名 |
| `--storeFile` | 签名 keystore 文件路径 |
| `--storePassword` | keystore 密码 |
| `--keyAlias` | key alias |
| `--keyPassword` | key 密码 |

CLI 默认使用 debug 签名，正式产物需提供签名参数。`--remove-root-files=true` 仅对 AAB 生效，会整目录删除 base/root 内容，默认不启用。
