# AndResGuard

[English](README.md) | [简体中文](README.zh-CN.md)

Resource processing for Android AAB and APK: resource name/path obfuscation, whitelists, incremental mapping, duplicate-resource merging, lossless image compression and deterministic media MD5 changes.

## Unified Gradle task

```groovy
plugins {
    id 'com.android.application'
    id 'io.github.dawnuu.andresguard' version '1.1.0'
}
andResGuard {
    format = "aab" // default: aab; also apk or all
    enableObfuscate = true
    mergeDuplicatedRes = true
    enableCompressImages = true
    whiteList = ['*.R.string.keep_name'] as Set
}
```

Run `./gradlew :app:andresguardRelease` (or `andresguardFreeRelease` for a flavor). The task selects build dependencies from format. `all` produces both formats with shared options and signing identity. Invalid format values fail before packaging.

Ordinary Android `bundleRelease` and `assembleRelease` tasks remain available; use `andresguardRelease` for resource processing.

Outputs and mapping reports are isolated under:

- `app/build/outputs/andresguard/aab/<variant>/`
- `app/build/outputs/andresguard/apk/<variant>/`

This version migrates the plugin ID, extension, task, public Java package and artifact names from AabResGuard to AndResGuard. AAB-specific Bundle names and APK-specific Apk names remain descriptive.

## Build locally

This version has not been published to Maven Central.

```bash
./gradlew :core:publishAllPublicationsToLocalBuildRepository :plugin:publishAllPublicationsToLocalBuildRepository
```

Add the resulting `AndResGuard/build/maven` directory to both plugin-management and dependency repositories, alongside Google and Maven Central. Coordinates:

- Plugin: `io.github.dawnuu:andresguard-plugin:1.1.0`
- Core: `io.github.dawnuu:andresguard-core:1.1.0`
- Plugin ID: `io.github.dawnuu.andresguard`

The local sibling `../AndResGuardDemo` demonstrates all modes:

```bash
./gradlew andresguardRelease
./gradlew andresguardRelease -PguardFormat=apk
./gradlew andresguardRelease -PguardFormat=all
```

The demo maps its guardFormat project property to the extension's format setting and uses a debug certificate for testing.

## Options

All formats share `enableObfuscate`, `whiteList`, `mappingFile`, `mergeDuplicatedRes`, `enableCompressImages`, `imageCompressionToolsDir`, `imageCompressionCacheDir`, `useRandomName`, `enableMutateMd5`, `mutateMd5Seed`, file/string filtering and optional VirusTotal upload. Bundle metadata options apply only to AAB. Keep each format's mapping reports for subsequent runs.

Image compression retains original images and warns on optional tool failures. Nine-patch, APNG and animated WebP are skipped. Native image tools are bundled for macOS ARM64/x86_64, Windows x86_64 and Linux x86_64 glibc; macOS tools are not Developer ID-notarized. Set a tools directory to override them.

Configure `obfuscatedBundleFileName` and `obfuscatedApkFileName` separately. Templates accept applicationId, versionName, versionCode, variantName, buildTime, format and fileNameVariables. Project-property overrides: `andResGuard.aabFileName`, `andResGuard.apkFileName`, `andResGuard.var.NAME`. File names must retain their corresponding extension.

Release builds require a usable Android signingConfig. Missing credentials fail by default; `allowDebugSignFallback = true` is an explicit testing option.

See the [Chinese configuration table](README.zh-CN.md#配置) for all defaults.

## APK requirements and boundaries

Use JDK 17+ and Android SDK Build Tools 35+. Set `apkBuildToolsDir` to override SDK discovery.

AAPT2 converts binary resource tables to protobuf for the shared pipeline, then back to APK. Repackaging preserves resource IDs, DEX/native payloads and ZIP storage methods, keeps resources.arsc uncompressed, aligns native libraries to 16 KB and signs/verifies with apksigner.

Only standalone/universal APKs are supported. Split APKs, split Gradle outputs and APKS archives are rejected. Input files stay intact; processing failures preserve previous successful outputs.

## CLI

```bash
./gradlew :core:shadowJar
java -jar core/build/libs/AndResGuard-1.1.0.jar help
java -jar core/build/libs/AndResGuard-1.1.0.jar obfuscate \
  --input=app.apk --output=guarded.apk --config=config.xml \
  --build-tools-dir=/path/to/sdk/build-tools/35.0.0
```

Use the same obfuscate command for AAB input/output. APK tools can also be located through ANDROID_HOME or ANDROID_SDK_ROOT. CLI defaults to a debug certificate; provide storeFile, storePassword, keyAlias and keyPassword for release signing, or explicitly disable signing with `--disable-sign=true`. See `help obfuscate` for options. Existing standalone bundle-filter commands remain AAB-specific.

## Validation and attribution

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :core:test :core:shadowJar :plugin:compileKotlin
```

APK integration tests run actual AAPT2, zipalign and apksigner. They verify resource IDs/text/references, whitelisting, pixels, DEX/native contents, compression, signatures, filtering and failure recovery. APK tests skip when no SDK is configured.

The original AabResGuard licensing and attribution are retained. APK repackaging and signing constraints were reviewed against [shwenzhang/AndResGuard](https://github.com/shwenzhang/AndResGuard); this implementation uses current SDK tools and does not import that project's legacy ARSC parser or Gradle plugin.
