# AndResGuard

[English](README.md) | [简体中文](README.zh-CN.md)

Android AAB/APK 资源处理工具，支持资源名称与路径混淆、白名单、mapping、重复资源合并、图片无损压缩和媒体 MD5 修改。

## 统一 Gradle 入口

```groovy
plugins {
    id 'com.android.application'
    id 'io.github.dawnuu.andresguard' version '1.1.0'
}

andResGuard {
    format = "aab" // 默认 aab，可选 apk、all
    enableObfuscate = true
    mergeDuplicatedRes = true
    enableCompressImages = true
    whiteList = ['*.R.string.keep_name'] as Set
}
```

```bash
./gradlew :app:andresguardRelease
# 有 flavor 时，例如 :app:andresguardFreeRelease
```

| format | 处理范围 |
| --- | --- |
| 不配置 / aab | 生成、处理并签名 AAB |
| apk | 生成、处理、对齐并签名 APK |
| all | 同时处理 AAB 和 APK，使用相同配置和签名身份 |

单一任务按 format 选择打包依赖。Android 原有的 `bundleRelease` 和 `assembleRelease` 保留为普通打包任务；执行资源处理请使用 `andresguardRelease`。无效 format 会在执行打包前报错。

产物与 mapping 分开存放：

- `app/build/outputs/andresguard/aab/<variant>/`
- `app/build/outputs/andresguard/apk/<variant>/`

本版从 AabResGuard 迁移，插件 ID、配置块、任务名、Java 公共包和产物名称均已更名。旧版使用方需更新接入配置；AAB 专属的 Bundle 类名保留，APK 专属类使用 Apk 命名。

## 本地构建与 Demo

此版本尚未发布到 Maven Central，请先构建本地 Maven 仓库：

```bash
./gradlew :core:publishAllPublicationsToLocalBuildRepository :plugin:publishAllPublicationsToLocalBuildRepository
```

在使用方的 `pluginManagement.repositories` 和依赖仓库中加入 `AndResGuard/build/maven`，再加入 `google()` 和 `mavenCentral()`。插件实现坐标为 `io.github.dawnuu:andresguard-plugin:1.1.0`，核心为 `io.github.dawnuu:andresguard-core:1.1.0`。

本地 Demo 为同级目录 `../AndResGuardDemo`：

```bash
cd ../AndResGuardDemo
./gradlew andresguardRelease
./gradlew andresguardRelease -PguardFormat=apk
./gradlew andresguardRelease -PguardFormat=all
```

Demo 的 `guardFormat` 是示例工程映射到 `format` 的项目属性，不是插件额外的格式入口。Demo 使用 debug 证书，仅用于测试。

## 配置

| 配置 | 默认值 / 用途 |
| --- | --- |
| format | aab；可选 apk、all |
| enableObfuscate | true；资源名称和文件路径混淆，保留资源 ID 和文本值 |
| whiteList | 默认 SDK 白名单；用户规则追加，用于通过名称动态读取的资源 |
| mappingFile | null；复用旧 mapping，建议为两种格式分别保留各自产物目录中的 mapping |
| mergeDuplicatedRes | false；合并重复资源文件 |
| enableCompressImages | false；无损压缩 PNG/JPEG/GIF/静态 WebP，只替换更小结果 |
| imageCompressionToolsDir | 空；默认使用 JAR 内置工具，可覆盖 |
| imageCompressionCacheDir | null；默认项目根目录 .gradle/andresguard/image-cache |
| apkBuildToolsDir | 空；默认从 Android SDK 选择最新稳定目录名的 Build Tools |
| useRandomName | false；随机混淆名称 |
| enableMutateMd5 / mutateMd5Seed | false / 空；确定性媒体 MD5 修改 |
| enableFilterFiles / filterList | false / 空；原有原生库等过滤规则 |
| enableFilterStrings / unusedStringPath / languageWhiteList | false / 空 / 空；按 unused.txt、语言规则过滤文本资源 |
| removeBundleMetadata / bundleMetaDataWhiteList | true / 空；仅针对 AAB 的 BUNDLE-METADATA |
| removeRootFiles | false；移除可选根目录文件，使用前确认应用不依赖它们 |
| obfuscatedBundleFileName / obfuscatedApkFileName | 空；分别配置 AAB/APK 文件名 |
| fileNameVariables | 空；文件名模板的自定义变量 |
| allowDebugSignFallback | false；仅测试时可显式允许 debug 签名回退 |
| enableVirusTotalUpload / virusTotalApiKey | false / 空；可选上传最终产物 |

文件名模板支持 `{applicationId}`、`{versionName}`、`{versionCode}`、`{variantName}`、`{buildTime}`、`{format}` 和自定义变量。可用 `-PandResGuard.aabFileName=...`、`-PandResGuard.apkFileName=...`、`-PandResGuard.var.NAME=...` 覆盖。文件名必须保持相应的 .aab 或 .apk 后缀。

图片压缩失败会保留原图、打印警告并继续打包；九宫格、APNG、动画 WebP 不做优化。内置图片工具支持 macOS ARM64/x86_64、Windows x86_64 和 Linux x86_64 glibc。macOS 工具未进行 Developer ID 公证，可通过工具目录覆盖。

## APK 支持边界

需要 JDK 17+、Android SDK Build Tools 35+。APK 使用 AAPT2 在 binary/protobuf 资源格式间转换，复用资源处理逻辑，再恢复 APK 目录、保持 DEX/原生库内容和 ZIP 压缩方式，确保 resources.arsc 不压缩，进行 16 KB 原生库对齐，再使用 apksigner 签名并验证。

当前只支持普通单 APK / universal APK。Split APK、ABI/density 拆分输出、APKS 集合会明确拒绝。该入口直接处理 Gradle 生成的 APK，不要求从 AAB 生成 APK。

release 变体应配置有效 signingConfig；没有可用配置默认失败。原始输入保持不变，处理失败保留已有输出。APK 未签名输出只可通过 CLI 的 `--disable-sign=true` 请求。

## 命令行

```bash
./gradlew :core:shadowJar
java -jar core/build/libs/AndResGuard-1.1.0.jar help
java -jar core/build/libs/AndResGuard-1.1.0.jar obfuscate \
  --input=app.apk --output=guarded.apk --config=config.xml \
  --build-tools-dir=/path/to/android/sdk/build-tools/35.0.0
```

AAB 使用同一个 `obfuscate` 命令，将 input/output 换为 .aab。APK 的 Build Tools 也可由 ANDROID_HOME 或 ANDROID_SDK_ROOT 定位。CLI 默认使用 debug 签名，正式产物必须提供 `--storeFile`、`--storePassword`、`--keyAlias`、`--keyPassword`。完整参数见 `help obfuscate`。

原有独立 AAB 过滤命令保留，输入仍为 AAB。

## 验证与来源

```bash
ANDROID_HOME=/path/to/android/sdk ./gradlew :core:test :core:shadowJar :plugin:compileKotlin
```

APK 集成测试使用真实 AAPT2、zipalign、apksigner；没有 SDK 时跳过 APK 集成测试。测试覆盖资源 ID、文本、引用路径、白名单、图片像素、DEX/原生库内容、签名、压缩方式、过滤、split 拒绝和失败时保留产物。

本项目保留原 AabResGuard 的许可与来源信息。APK 重打包、resources.arsc 压缩约束和先对齐再签名的流程参考 [shwenzhang/AndResGuard](https://github.com/shwenzhang/AndResGuard)，使用当前 SDK 工具实现，没有引入其旧 Gradle 插件和 ARSC 解析器。
