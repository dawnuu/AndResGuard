package com.bytedance.android.plugin.tasks

import com.android.build.api.variant.BuiltArtifactsLoader
import com.bytedance.android.andresguard.apk.AndroidBuildTools
import com.bytedance.android.plugin.internal.AndResGuardSettings
import com.bytedance.android.plugin.internal.ResourceFormat
import com.bytedance.android.plugin.internal.resolveFileName
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

abstract class AndResGuardTask @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    // Resolved at configuration time; the task never reads the Project or the variant while running.
    @get:Internal abstract val settings: Property<AndResGuardSettings>
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
    internal fun trackInputsAndOutputs(format: ResourceFormat) {
        if (format.aab) inputs.file(bundleFile).withPropertyName("bundle").withPathSensitivity(PathSensitivity.NONE)
        if (format.apk) inputs.dir(apkDirectory).withPropertyName("apkDirectory").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("configuration", settings.map { it.fingerprint(format) })
        if (format.apk) {
            // Errors surface with their usual message when the task runs.
            inputs.property("buildTools", sdkDirectory.map { sdk ->
                runCatching { AndroidBuildTools.find("", sdk.asFile.toPath()).toString() }.getOrDefault("")
            })
        }
        inputs.files(settings.map { it.referencedFiles() })
            .withPropertyName("referencedFiles").withPathSensitivity(PathSensitivity.ABSOLUTE)
        outputs.dir(settings.map { File(it.outputDirectory) }).withPropertyName("outputDirectory")
        // {buildTime} gives every run a new file name, so such outputs are never up to date.
        val settings = settings
        outputs.upToDateWhen { !settings.get().usesBuildTime }
    }

    @TaskAction
    fun execute() {
        val config = settings.get()
        val format = ResourceFormat.parse(requireNotNull(resourceFormat) { "resourceFormat is not set" })
        logger.lifecycle(config.toString())
        if (config.storeFile == null || !File(config.storeFile).exists()) {
            if (!config.allowDebugSignFallback) {
                throw GradleException("No usable signingConfig for ${config.variantName}. Configure release signing or explicitly enable andResGuard.allowDebugSignFallback for testing.")
            }
            logger.warn("AndResGuard: signing with the DEBUG keystore. Do not publish these artifacts.")
        } else if (config.storePassword == null || config.keyAlias == null || config.keyPassword == null) {
            throw GradleException("Incomplete signingConfig for ${config.variantName}: storePassword, keyAlias and keyPassword are required.")
        }
        val tools = if (format.apk) AndroidBuildTools.find("", sdkDirectory.get().asFile.toPath()).toString() else ""
        if (format.apk) AndroidBuildTools(java.nio.file.Paths.get(tools))
        // Check all required APK inputs before processing either output in 'all' mode.
        val apkInput = if (format.apk) {
            val artifacts = builtArtifactsLoader.load(apkDirectory.get())
                ?: throw GradleException("Cannot read APK output metadata for ${config.variantName}.")
            if (artifacts.elements.size != 1 || artifacts.elements.single().filters.isNotEmpty()) {
                throw GradleException("APK splits are not supported. Disable splits and build a standalone/universal APK.")
            }
            File(artifacts.elements.single().outputFile).toPath()
        } else null
        val buildTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val unusedStringPath = config.unusedStringFile()?.absolutePath ?: config.unusedStringPath ?: ""
        fun process(input: Path, extension: String) {
            val values = mapOf(
                "applicationId" to config.applicationId, "versionName" to (config.versionName ?: "unspecified"),
                "versionCode" to (config.versionCode ?: 0).toString(), "variantName" to config.variantName,
                "buildTime" to buildTime, "format" to extension
            ) + config.fileNameVariables
            val name = resolveFileName(config.fileNameTemplate, values, extension)
            val output = File(config.outputDirectory, name).toPath()
            val queue = workQueue(config)
            queue.submit(AndResGuardWorkAction::class.java) { p ->
                p.input.set(input.toFile()); p.output.set(output.toFile())
                config.mappingFile?.let { p.mappingFile.set(File(it)) }
                p.enableObfuscate.set(config.enableObfuscate)
                p.mergeDuplicatedRes.set(config.mergeDuplicatedRes)
                p.enableCompressImages.set(config.enableCompressImages)
                p.imageCompressionToolsDir.set(config.imageCompressionToolsDir)
                p.imageCompressionCacheDir.set(File(config.imageCompressionCacheDir))
                p.imageCompressionParallelism.set(config.imageCompressionParallelism)
                p.apkBuildToolsDir.set(tools)
                p.whiteList.set(config.whiteList); p.bundleMetaDataWhiteList.set(config.bundleMetaDataWhiteList)
                p.enableFilterFiles.set(config.enableFilterFiles); p.filterList.set(config.filterList)
                p.removeBundleMetadata.set(config.removeBundleMetadata); p.removeRootFiles.set(config.removeRootFiles)
                p.enableFilterStrings.set(config.enableFilterStrings); p.unusedStringPath.set(unusedStringPath)
                p.languageWhiteList.set(config.languageWhiteList)
                p.useRandomName.set(config.useRandomName); p.enableMutateMd5.set(config.enableMutateMd5)
                p.mutateMd5Seed.set(config.mutateMd5Seed)
                p.enableVirusTotalUpload.set(config.enableVirusTotalUpload); p.virusTotalApiKey.set(config.virusTotalApiKey)
                config.storeFile?.let(::File)?.takeIf { it.exists() }?.let {
                    p.storeFile.set(it); p.storePassword.set(config.storePassword!!)
                    p.keyAlias.set(config.keyAlias!!); p.keyPassword.set(config.keyPassword!!)
                }
            }
            // Run AAB and APK passes one after another so 'all' never holds two worker heaps at once.
            queue.await()
            logger.lifecycle("AndResGuard $extension output: $output")
        }
        if (format.aab) process(bundleFile.get().asFile.toPath(), "aab")
        if (format.apk) process(apkInput!!, "apk")
    }

    private fun workQueue(config: AndResGuardSettings) = if (config.processIsolation) {
        workerExecutor.processIsolation { spec ->
            spec.forkOptions { fork ->
                fork.maxHeapSize = config.workerMaxHeapSize
                fork.jvmArgs(config.workerJvmArgs)
            }
        }
    } else {
        workerExecutor.noIsolation()
    }
}
