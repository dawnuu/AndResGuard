# AndResGuard

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dawnuu/andresguard-plugin)](https://central.sonatype.com/artifact/io.github.dawnuu/andresguard-plugin)

[English](README.md) | [简体中文](README.zh-CN.md)

Resource obfuscation, duplicate-resource merging, lossless image compression, file/string filtering and signing for AAB and APK.

## Migration guide

Update integration names and the processing command when migrating from AabResGuard:

| Item | AabResGuard | AndResGuard |
| --- | --- | --- |
| Plugin ID | `io.github.dawnuu.aabresguard` | `io.github.dawnuu.andresguard` |
| Buildscript dependency | `io.github.dawnuu:aabresguard-plugin` | `io.github.dawnuu:andresguard-plugin:1.1.1` |
| Configuration block | `aabResGuard { ... }` | `andResGuard { ... }` |
| Processing task | `aabresguardRelease` | `andresguardRelease` |
| Kotlin extension type | `AabResGuardExtension` | `AndResGuardExtension` |
| File-name project property | `aabResGuard.fileName` | `andResGuard.fileName` |
| Output template | `obfuscatedBundleFileName` | `obfuscatedFileName` (shared by AAB/APK) |
| Custom-variable property | `aabResGuard.var.NAME` | `andResGuard.var.NAME` |

Existing whitelist, compression and filtering settings can be reused. `bundleRelease` automatically processes AAB resources, and `assembleRelease` automatically processes APK resources. The explicit `andresguardRelease` task follows `format`, which defaults to AAB; select `apk` or `all` as needed.

## Integration guide

Both Gradle Plugin and standalone JAR integration are supported.

### 1. Gradle Plugin

#### 1.1 Add the plugin

Use Gradle plugins DSL with a version catalog.

Configure repositories in settings.gradle or settings.gradle.kts; the following syntax works with both Groovy and Kotlin DSL:

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

Declare the version and plugin in gradle/libs.versions.toml:

```toml
[versions]
andresguard = "1.1.1"

[plugins]
andresguard = { id = "io.github.dawnuu.andresguard", version.ref = "andresguard" }
```

Declare the plugin in the root project and apply it in the app module. This syntax works with both Groovy and Kotlin DSL:

```groovy
// Root build.gradle / build.gradle.kts
plugins {
    alias(libs.plugins.andresguard) apply false
}

// App module build.gradle / build.gradle.kts
plugins {
    id("com.android.application")
    alias(libs.plugins.andresguard)
}
```

> You can also use the legacy `buildscript` method:
>
> **Groovy DSL:**
>
> Add the dependency in the root project:
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
> Apply the plugin in the application module:
>
> ```gradle
> apply plugin: "io.github.dawnuu.andresguard"
> ```
>
> **Kotlin DSL (`.kts`):**
>
> Add the dependency in the root project:
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
> Apply the plugin in the application module:
>
> ```kotlin
> apply(plugin = "io.github.dawnuu.andresguard")
> ```

#### 1.2 Configuration options

Add an `andResGuard` configuration block in `build.gradle` for the application module.

**Groovy DSL:**

```gradle
andResGuard {
    // Output format: aab, apk or all; default: aab
    format = "aab"
    // Enable resource obfuscation; default: true
    enableObfuscate = true
    // Generate random resource names; default: false
    useRandomName = false
    // Append one byte to media files to change their MD5; default: false
    enableMutateMd5 = false
    // Mutation is stable by default (same input -> same bytes); change the seed to get a different result
    mutateMd5Seed = ""
    // Sign with the debug keystore when no signing config is found; default: false (the build fails instead)
    allowDebugSignFallback = false
    // Process in a short-lived JVM so the Gradle daemon heap can stay small; default: true.
    // false restores the previous in-daemon processing.
    processIsolation = true
    // Max heap of that JVM; default: "4g". Override per build with -PandResGuard.workerMaxHeapSize=6g
    workerMaxHeapSize = "4g"
    // Extra JVM arguments for that JVM; default: empty
    workerJvmArgs = []
    // Remove duplicate resources; default: false
    mergeDuplicatedRes = true
    // Losslessly optimize PNG/JPEG/GIF/static WebP; default: false
    enableCompressImages = false
    // Optional override; empty uses tools bundled in the JAR
    imageCompressionToolsDir = ""
    // Images compressed at the same time, which also bounds how many are held in memory;
    // 0 = CPU count (at most 4), 1 = serial. Override with -PandResGuard.imageCompressionParallelism=2
    imageCompressionParallelism = 0
    // Enable file filtering; default: false
    enableFilterFiles = false
    // Enable string filtering; default: false
    enableFilterStrings = false
    // Remove AAB BUNDLE-METADATA; default: true
    removeBundleMetadata = true
    // AAB only: remove files in base/root; default: false
    // Test application stability yourself before enabling this option
    removeRootFiles = false
    // Keep matching BUNDLE-METADATA files when removeBundleMetadata is true
    bundleMetaDataWhiteList = [
        "com.example.metadata"
    ]

    // Mapping file for incremental obfuscation (optional)
    // mappingFile = file("mapping.txt").toPath()

    // Custom allowlist rules are merged with the built-in allowlist
    whiteList = [
        "*.R.raw.*",
        "*.R.drawable.icon"
    ]

    // Shared AAB/APK output template; the extension follows the output format
    // Placeholders: {applicationId} {versionName} {versionCode} {variantName} {buildTime} {format}
    // e.g. "{applicationId}_{versionName}_{buildTime}.aab"
    // Override from outside: -PandResGuard.fileName="{applicationId}_{channel}"
    // and -PandResGuard.var.channel=google (command line or gradle.properties; wins over this file)
    // Custom variables, e.g. fileNameVariables = [channel: "google"] -> "{applicationId}_{channel}.aab"
    // Default: {applicationId}_{versionName}_{versionCode}.aab / .apk
    obfuscatedFileName = "duplicated-app"

    // File filtering rules
    filterList = [
        "*/arm64-v8a/*",
        "BUNDLE-METADATA/*"
    ]

    // String filtering list; path is relative to the current working directory (optional)
    unusedStringPath = "unused.txt"

    // Retain en, en-xx, zh, zh-xx, and other selected languages (optional)
    languageWhiteList = ["en", "zh"]

    // VirusTotal upload is disabled by default; do not hard-code the API key
    enableVirusTotalUpload = false
    virusTotalApiKey = System.getenv("VIRUSTOTAL_API_KEY") ?: ""
}
```

**Kotlin DSL (`.kts`):**

```kotlin
andResGuard {
    // Output format: aab, apk or all; default: aab
    format = "aab"
    // Enable resource obfuscation; default: true
    enableObfuscate = true
    // Generate random resource names; default: false
    useRandomName = false
    // Append one byte to media files to change their MD5; default: false
    enableMutateMd5 = false
    // Mutation is stable by default (same input -> same bytes); change the seed to get a different result
    mutateMd5Seed = ""
    // Remove duplicate resources; default: false
    mergeDuplicatedRes = true
    enableCompressImages = false
    imageCompressionToolsDir = ""
    // Enable file filtering; default: false
    enableFilterFiles = false
    // Enable string filtering; default: false
    enableFilterStrings = false
    // Remove AAB BUNDLE-METADATA; default: true
    removeBundleMetadata = true
    // AAB only: remove files in base/root; default: false
    // Test application stability yourself before enabling this option
    removeRootFiles = false
    // Keep matching BUNDLE-METADATA files when removeBundleMetadata is true
    bundleMetaDataWhiteList = setOf(
        "com.example.metadata"
    )

    // Mapping file for incremental obfuscation (optional)
    // mappingFile = file("mapping.txt").toPath()

    // Custom allowlist rules are merged with the built-in allowlist
    whiteList = setOf(
        "*.R.raw.*",
        "*.R.drawable.icon"
    )

    // Shared AAB/APK output template; the extension follows the output format
    // Placeholders: {applicationId} {versionName} {versionCode} {variantName} {buildTime} {format}
    // e.g. "{applicationId}_{versionName}_{buildTime}.aab"
    // Override from outside: -PandResGuard.fileName="{applicationId}_{channel}"
    // and -PandResGuard.var.channel=google (command line or gradle.properties; wins over this file)
    // Custom variables, e.g. fileNameVariables = [channel: "google"] -> "{applicationId}_{channel}.aab"
    // Default: {applicationId}_{versionName}_{versionCode}.aab / .apk
    obfuscatedFileName = "duplicated-app"

    // File filtering rules
    filterList = setOf(
        "*/arm64-v8a/*",
        "BUNDLE-METADATA/*"
    )

    // String filtering list; path is relative to the current working directory (optional)
    unusedStringPath = "unused.txt"

    // Retain en, en-xx, zh, zh-xx, and other selected languages (optional)
    languageWhiteList = setOf("en", "zh")

    // VirusTotal upload is disabled by default; do not hard-code the API key
    enableVirusTotalUpload = false
    virusTotalApiKey = System.getenv("VIRUSTOTAL_API_KEY") ?: ""
}
```

Templates may omit the extension or retain an .aab/.apk extension; the plugin applies the output format automatically. With format = "all", one template produces matching AAB/APK names in separate directories.

#### 1.3 Run obfuscation

The unified task selects packaging and processing dependencies from format. Only Release variants are registered:

```bash
./gradlew :app:andresguardRelease
# Standard Android tasks also run resource processing:
./gradlew :app:bundleRelease    # AAB
./gradlew :app:assembleRelease  # APK
# With a flavor, for example:
./gradlew :app:andresguardFreeRelease
```

| format | Processed output |
| --- | --- |
| Unset / `aab` | AAB |
| `apk` | APK |
| `all` | Both AAB and APK |

`format` controls only `andresguard<Variant>`. `bundle<Variant>` always processes AAB, and `assemble<Variant>` always processes APK, including flavor variants. Requesting multiple tasks in one invocation processes each format once. Processing failures fail the build; debug variants are unaffected.

APK processing edits binary `resources.arsc` and ZIP entries directly, without converting to AAB. Obfuscation, merging, compression and string-filter settings are shared; `removeBundleMetadata`, `bundleMetaDataWhiteList` and `removeRootFiles` apply only to AAB. APK file-filter rules match actual paths inside the APK.

Release variants require a valid signingConfig. Missing signing configuration fails by default; allowDebugSignFallback = true is for testing only.

Resource processing runs in a separate Gradle worker process by default. The process exits when the build finishes and its memory is released, so org.gradle.jvmargs does not need to be raised for AndResGuard. If a very large AAB hits OutOfMemoryError, raise workerMaxHeapSize.

When the input package, the andResGuard configuration, referenced files (mapping, unused.txt, signing keystore, custom image tools directory) and the signing identity are unchanged, the task is skipped (UP-TO-DATE) and the previous outputs are reused. A file-name template containing {buildTime} produces a new name on every run, so such builds always execute.

The plugin is compatible with the Gradle configuration cache (org.gradle.configuration-cache=true). The andResGuard configuration, signing configuration and -P properties are read at configuration time; Gradle reconfigures automatically when they change.

#### 1.4 Output locations

Processed files live in the app module's build/outputs/andresguard/. Each format has its own artifact and mapping directory. Android's original outputs are retained. For module app and variant release:

```text
app/build/outputs/
├── andresguard/
│   ├── aab/release/
│   │   ├── <file-name>.aab
│   │   ├── resources-mapping.txt
│   │   └── base-duplicated.txt
│   └── apk/release/
│       ├── <file-name>.apk
│       ├── resources-mapping.txt
│       └── base-duplicated.txt
├── bundle/release/       # Original Android AAB
└── apk/release/          # Original Android APK
```

resources-mapping.txt is generated when resource obfuscation is enabled; base-duplicated.txt is generated when duplicate-resource merging is enabled. Reports are isolated by format.

- Default names: `{applicationId}_{versionName}_{versionCode}.aab` / `.apk`.
- Custom template: obfuscatedFileName; use andResGuard.fileName for a project-property override.
- Flavors use the full variant name, for example aab/freeRelease/ and apk/freeRelease/. AGP determines the original-output paths.
- Selecting only aab or apk produces that format; existing outputs are not automatically removed.
- CLI output location is controlled by --output.

### Lossless image compression

Optionally set `imageCompressionCacheDir = rootProject.file("custom-cache/images").toPath()` to choose another cache directory. Leave it unset for the default. Choose a location outside `build/` to preserve results across `clean`.

The Gradle plugin caches successful results in `.gradle/andresguard/image-cache/` under the project root; `gradlew clean` preserves this directory. Each build reads the original image and inserts cached bytes into the new AAB/APK when image content, compression arguments, tool contents and platform match. Source files are unchanged. Results that do not shrink are also cached; failures are not. The `cached` summary field counts hits. Corrupt entries are recompressed, and cache I/O failures warn and allow compression to continue. Delete this directory to force recompression. The CLI does not enable persistent caching by default.

Set `enableCompressImages = true` to optimize PNG, JPG/JPEG, GIF (including animations), and static WebP in every module's `res/` and `assets/` directories. Images retain their formats, paths, resolution and visual quality, with metadata supported by the tools preserved. Only smaller results replace the originals. Already optimized images may not shrink; decoded memory usage usually stays the same.

Native tools are bundled in the JAR; no installation, runtime download or directory configuration is needed. Supported platforms: macOS ARM64 (Apple Silicon)/x86_64 (Intel), Windows x86_64 and Linux x86_64 (glibc, not Alpine/musl). Tools are extracted into a private per-run temporary directory and verified with SHA-256, then cleaned up after processing. No tools are extracted when compression is disabled.

Leave `imageCompressionToolsDir` empty to use bundled tools. Set it only to override versions or use another platform, e.g. `imageCompressionToolsDir = "/opt/homebrew/bin"`. Blocked, missing, failed or timed-out tools emit `[AndResGuard WARNING]`, retain original images and allow obfuscation and packaging to continue. Once a format fails, further images of that format are left unchanged for that run to avoid repeated launches and warnings. Temporary directory creation/cleanup failures also warn; explicit task interruption still propagates.

Nine-patch images (including compiled Android `np*` chunks), APNG, and animated WebP are skipped and logged. Other formats, such as BMP and SVG, are unchanged. The summary reports optimized, unchanged, skipped and failed image counts and saved image bytes; actual AAB/APK savings also depend on ZIP compression. Optimization runs before resource obfuscation and MD5 mutation.

CLI: add `--compress-images=true`; `--image-compression-tools-dir` is an optional override. Enable this feature through Gradle or CLI flags; XML configuration is not supported yet. See [bundled tool notices](core/src/main/resources/image-tools/NOTICE.md) for versions, origins, licenses and regeneration instructions.

Bundled macOS tools are not Developer ID-notarized; use imageCompressionToolsDir to select approved tools if execution is blocked.

### 2. Command-line tool

#### 2.1 Build the JAR

```bash
./gradlew :core:shadowJar
```

The JAR is core/build/libs/AndResGuard-1.1.1.jar. Commands below assume it has been copied to the current directory as AndResGuard.jar.

#### 2.2 Prepare the configuration file

The command-line tool uses an XML configuration file to define allowlist and filtering rules:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<resguard>
    <!-- Allowlist rules -->
    <issue id="whitelist" isactive="true">
        <path value="*.R.raw.*"/>
        <path value="*.R.drawable.icon"/>
    </issue>

    <!-- File filtering rules (optional) -->
    <filter isactive="true">
        <rule value="*/arm64-v8a/*"/>
        <rule value="BUNDLE-METADATA/*"/>
    </filter>

    <!-- String filtering configuration (optional) -->
    <filter-str isactive="true">
        <path value="unused.txt"/>
        <language value="en"/>
        <language value="zh"/>
    </filter-str>
</resguard>
```

The XML parser recognizes only the `issue`, `filter`, and `filter-str` elements shown above. `useRandomName`, `enableMutateMd5`, VirusTotal upload, and the BUNDLE-METADATA allowlist are currently Gradle-plugin options and have no command-line flags.

#### 2.3 Run obfuscation

```cmd
java -jar AndResGuard.jar obfuscate \
  --input=app.aab \
  --output=obfuscated-app.aab \
  --config=config.xml \
  --merge-duplicated-res=true \
  --remove-bundle-metadata=true \
  --remove-root-files=false
```

For APK, use the same command with .apk input/output. Set --build-tools-dir=/path/to/sdk/build-tools/35.0.0 or configure ANDROID_HOME / ANDROID_SDK_ROOT.

**Required arguments:**

| Argument | Description |
|------|------|
| `--input` | Input AAB or standalone APK path |
| `--output` | Processed output path with the same format as the input |
| `--config` | Configuration file path |

**Optional arguments:**

| Argument | Description |
|------|------|
| `--mapping` | Mapping file path for incremental obfuscation |
| `--build-tools-dir` | Android SDK Build Tools 35+ directory for APK |
| `--compress-images=true` | Enable lossless image compression |
| `--image-compression-tools-dir` | Optional override for bundled image tools |
| `--merge-duplicated-res=true` | Remove duplicate resources |
| `--remove-bundle-metadata=true` | Remove BUNDLE-METADATA |
| `--remove-root-files=true` | AAB only: remove files in base/root |
| `--disable-sign=true` | Disable signing |
| `--storeFile` | Signing keystore file path |
| `--storePassword` | Keystore password |
| `--keyAlias` | Key alias |
| `--keyPassword` | Key password |

CLI defaults to debug signing; supply signing flags for release artifacts. --remove-root-files=true applies only to AAB, removes all base/root content and is disabled by default.
