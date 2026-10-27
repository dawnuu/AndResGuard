package com.bytedance.android.plugin.tasks

import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuiltArtifactsLoader
import com.bytedance.android.andresguard.apk.AndroidBuildTools
import com.bytedance.android.plugin.extensions.AndResGuardExtension
import com.bytedance.android.plugin.internal.ResourceFormat
import com.bytedance.android.plugin.internal.getSigningConfig
import com.bytedance.android.plugin.internal.resolveFileName
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

open class AndResGuardTask @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @get:Internal lateinit var variant: ApplicationVariant
    @get:Internal lateinit var bundleFile: Provider<RegularFile>
    @get:Internal lateinit var apkDirectory: Provider<Directory>
    @get:Internal lateinit var sdkDirectory: Provider<Directory>
    @get:Internal lateinit var builtArtifactsLoader: BuiltArtifactsLoader
    @get:Internal var resourceFormat: String? = null

    init {
        group = "andresguard"
        description = "Build and process AAB/APK resources according to andResGuard.format"
    }

    /**
     * Declares everything that affects the produced files, so the task is skipped when
     * neither the input package nor the configuration has changed.
     */
    internal fun trackInputsAndOutputs(config: AndResGuardExtension, format: ResourceFormat) {
        if (format.aab) inputs.file(bundleFile).withPropertyName("bundle").withPathSensitivity(PathSensitivity.NONE)
        if (format.apk) inputs.dir(apkDirectory).withPropertyName("apkDirectory").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("configuration", project.provider { fingerprint(config, format) })
        inputs.files(project.provider { referencedFiles(config) })
            .withPropertyName("referencedFiles").withPathSensitivity(PathSensitivity.ABSOLUTE)
        val extension = if (format.aab) "aab" else "apk"
        outputs.dir(project.layout.buildDirectory.dir("outputs/andresguard/$extension/${variant.name}"))
            .withPropertyName("outputDirectory")
        // {buildTime} gives every run a new file name, so such outputs are never up to date.
        outputs.upToDateWhen { !buildTimePlaceholder.containsMatchIn(fileNameTemplate(config)) }
    }

    private fun fingerprint(config: AndResGuardExtension, format: ResourceFormat): Map<String, String> {
        val signing = getSigningConfig(project, variant)
        val output = variant.outputs.firstOrNull()
        fun Collection<String>?.stable() = orEmpty().sorted().joinToString("\n")
        val values = sortedMapOf(
            "format" to format.name,
            "enableObfuscate" to config.enableObfuscate.toString(),
            "useRandomName" to config.useRandomName.toString(),
            "enableCompressImages" to config.enableCompressImages.toString(),
            "enableMutateMd5" to config.enableMutateMd5.toString(),
            "mutateMd5Seed" to sha256(config.mutateMd5Seed),
            "allowDebugSignFallback" to config.allowDebugSignFallback.toString(),
            "enableVirusTotalUpload" to config.enableVirusTotalUpload.toString(),
            "virusTotalApiKey" to sha256(config.virusTotalApiKey),
            "whiteList" to config.whiteList.stable(),
            "bundleMetaDataWhiteList" to config.bundleMetaDataWhiteList.stable(),
            "mergeDuplicatedRes" to config.mergeDuplicatedRes.toString(),
            "enableFilterFiles" to config.enableFilterFiles.toString(),
            "filterList" to config.filterList.stable(),
            "removeBundleMetadata" to config.removeBundleMetadata.toString(),
            "removeRootFiles" to config.removeRootFiles.toString(),
            "enableFilterStrings" to config.enableFilterStrings.toString(),
            "languageWhiteList" to config.languageWhiteList.stable(),
            "fileNameTemplate" to fileNameTemplate(config),
            "fileNameVariables" to (config.fileNameVariables + fileNameProperties()).map { "${it.key}=${it.value}" }.stable(),
            "applicationId" to variant.applicationId.get(),
            "versionName" to (output?.versionName?.orNull ?: ""),
            "versionCode" to (output?.versionCode?.orNull?.toString() ?: ""),
            "keyAlias" to (signing.keyAlias ?: ""),
            // Store only digests of secrets in Gradle's execution history.
            "storePassword" to sha256(signing.storePassword ?: ""),
            "keyPassword" to sha256(signing.keyPassword ?: "")
        )
        if (format.apk) {
            // Errors surface with their usual message when the task runs.
            values["buildTools"] = runCatching { AndroidBuildTools.find("", sdkDirectory.get().asFile.toPath()).toString() }
                .getOrDefault("")
        }
        return values
    }

    private fun referencedFiles(config: AndResGuardExtension): List<File> = listOfNotNull(
        config.mappingFile?.toFile(),
        unusedStringFile(config),
        getSigningConfig(project, variant).storeFile,
        config.imageCompressionToolsDir.takeIf { it.isNotBlank() }?.let { File(it) }
    )

    private fun fileNameTemplate(config: AndResGuardExtension): String =
        ((project.findProperty("andResGuard.fileName") as? String)?.takeIf { it.isNotBlank() } ?: config.obfuscatedFileName)
            .ifBlank { "{applicationId}_{versionName}_{versionCode}" }

    private fun fileNameProperties(): Map<String, String> =
        project.properties.filterKeys { it.startsWith("andResGuard.var.") }
            .mapKeys { it.key.removePrefix("andResGuard.var.") }.mapValues { it.value.toString() }

    @TaskAction
    fun execute() {
        val config = project.extensions.getByType(AndResGuardExtension::class.java)
        val format = ResourceFormat.parse(resourceFormat ?: config.format)
        logger.lifecycle(config.toString())
        prepareUnusedFile(config)
        // Read signing once so all formats use the same identity.
        val signing = getSigningConfig(project, variant)
        if (signing.storeFile == null || !signing.storeFile.exists()) {
            if (!config.allowDebugSignFallback) {
                throw GradleException("No usable signingConfig for ${variant.name}. Configure release signing or explicitly enable andResGuard.allowDebugSignFallback for testing.")
            }
            logger.warn("AndResGuard: signing with the DEBUG keystore. Do not publish these artifacts.")
        } else if (signing.storePassword == null || signing.keyAlias == null || signing.keyPassword == null) {
            throw GradleException("Incomplete signingConfig for ${variant.name}: storePassword, keyAlias and keyPassword are required.")
        }
        val tools = if (format.apk) AndroidBuildTools.find("", sdkDirectory.get().asFile.toPath()).toString() else ""
        if (format.apk) AndroidBuildTools(java.nio.file.Paths.get(tools))
        // Check all required APK inputs before processing either output in 'all' mode.
        val apkInput = if (format.apk) {
            val artifacts = builtArtifactsLoader.load(apkDirectory.get())
                ?: throw GradleException("Cannot read APK output metadata for ${variant.name}.")
            if (artifacts.elements.size != 1 || artifacts.elements.single().filters.isNotEmpty()) {
                throw GradleException("APK splits are not supported. Disable splits and build a standalone/universal APK.")
            }
            File(artifacts.elements.single().outputFile).toPath()
        } else null
        val buildTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        fun process(input: Path, extension: String) {
            val outputDir = project.layout.buildDirectory.dir("outputs/andresguard/$extension/${variant.name}").get().asFile
            val versionName = variant.outputs.firstOrNull()?.versionName?.getOrElse("unspecified") ?: "unspecified"
            val versionCode = variant.outputs.firstOrNull()?.versionCode?.getOrElse(0) ?: 0
            val values = mapOf(
                "applicationId" to variant.applicationId.get(), "versionName" to versionName,
                "versionCode" to versionCode.toString(), "variantName" to variant.name,
                "buildTime" to buildTime, "format" to extension
            ) + config.fileNameVariables + fileNameProperties()
            val name = resolveFileName(fileNameTemplate(config), values, extension)
            val output = File(outputDir, name).toPath()
            val queue = workQueue(config)
            queue.submit(AndResGuardWorkAction::class.java) { p ->
                p.input.set(input.toFile()); p.output.set(output.toFile())
                config.mappingFile?.let { p.mappingFile.set(it.toFile()) }
                p.enableObfuscate.set(config.enableObfuscate)
                p.mergeDuplicatedRes.set(config.mergeDuplicatedRes)
                p.enableCompressImages.set(config.enableCompressImages)
                p.imageCompressionToolsDir.set(config.imageCompressionToolsDir)
                p.imageCompressionCacheDir.set((config.imageCompressionCacheDir
                    ?: project.rootDir.resolve(".gradle/andresguard/image-cache").toPath()).toFile())
                p.imageCompressionParallelism.set(
                    (project.findProperty("andResGuard.imageCompressionParallelism") as? String)?.toIntOrNull()
                        ?: config.imageCompressionParallelism
                )
                p.apkBuildToolsDir.set(tools)
                p.whiteList.set(config.whiteList ?: emptySet()); p.bundleMetaDataWhiteList.set(config.bundleMetaDataWhiteList ?: emptySet())
                p.enableFilterFiles.set(config.enableFilterFiles); p.filterList.set(config.filterList ?: emptySet())
                p.removeBundleMetadata.set(config.removeBundleMetadata); p.removeRootFiles.set(config.removeRootFiles)
                p.enableFilterStrings.set(config.enableFilterStrings); p.unusedStringPath.set(config.unusedStringPath ?: "")
                p.languageWhiteList.set(config.languageWhiteList ?: emptySet())
                p.useRandomName.set(config.useRandomName); p.enableMutateMd5.set(config.enableMutateMd5)
                p.mutateMd5Seed.set(config.mutateMd5Seed)
                p.enableVirusTotalUpload.set(config.enableVirusTotalUpload); p.virusTotalApiKey.set(config.virusTotalApiKey)
                signing.storeFile?.takeIf { it.exists() }?.let {
                    p.storeFile.set(it); p.storePassword.set(signing.storePassword!!)
                    p.keyAlias.set(signing.keyAlias!!); p.keyPassword.set(signing.keyPassword!!)
                }
            }
            // Run AAB and APK passes one after another so 'all' never holds two worker heaps at once.
            queue.await()
            logger.lifecycle("AndResGuard $extension output: $output")
        }
        if (format.aab) process(bundleFile.get().asFile.toPath(), "aab")
        if (format.apk) process(apkInput!!, "apk")
    }

    private fun workQueue(config: AndResGuardExtension) = if (config.processIsolation) {
        val heap = (project.findProperty("andResGuard.workerMaxHeapSize") as? String)?.takeIf { it.isNotBlank() }
            ?: config.workerMaxHeapSize
        workerExecutor.processIsolation { spec ->
            spec.forkOptions { fork ->
                fork.maxHeapSize = heap
                fork.jvmArgs(config.workerJvmArgs)
            }
        }
    } else {
        workerExecutor.noIsolation()
    }

    private fun prepareUnusedFile(config: AndResGuardExtension) {
        if (!config.enableFilterStrings || !config.unusedStringPath.isNullOrBlank()) return
        unusedStringFile(config)?.let { config.unusedStringPath = it.absolutePath }
    }

    private fun unusedStringFile(config: AndResGuardExtension): File? {
        if (!config.enableFilterStrings) return null
        config.unusedStringPath?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val candidates = listOf(
            File(project.buildDir, "outputs/mapping/${variant.name}/unused.txt"),
            File(project.buildDir, "outputs/mapping/${variant.name.removeSuffix("Release")}/release/unused.txt")
        )
        return candidates.firstOrNull { it.isFile }
    }

    private companion object {
        val buildTimePlaceholder = Regex("""\{buildTime}""")

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
