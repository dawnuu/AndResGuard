package com.bytedance.android.plugin.internal

import com.android.build.api.variant.ApplicationVariant
import com.bytedance.android.plugin.extensions.AndResGuardExtension
import org.gradle.api.Project
import java.io.File
import java.io.Serializable
import java.security.MessageDigest

/**
 * Everything a processing task needs, resolved at configuration time so the task never touches
 * the Project or the AGP variant while executing (required by the configuration cache).
 */
data class AndResGuardSettings(
    val description: String,
    val variantName: String,
    val applicationId: String,
    val versionName: String?,
    val versionCode: Int?,
    val outputDirectory: String,
    val enableObfuscate: Boolean,
    val mappingFile: String?,
    val useRandomName: Boolean,
    val enableCompressImages: Boolean,
    val imageCompressionToolsDir: String,
    val imageCompressionCacheDir: String,
    val imageCompressionParallelism: Int,
    val enableMutateMd5: Boolean,
    val mutateMd5Seed: String,
    val allowDebugSignFallback: Boolean,
    val processIsolation: Boolean,
    val workerMaxHeapSize: String,
    val workerJvmArgs: List<String>,
    val enableVirusTotalUpload: Boolean,
    val virusTotalApiKey: String,
    val whiteList: List<String>,
    val bundleMetaDataWhiteList: List<String>,
    val fileNameTemplate: String,
    val fileNameVariables: Map<String, String>,
    val mergeDuplicatedRes: Boolean,
    val enableFilterFiles: Boolean,
    val filterList: List<String>,
    val removeBundleMetadata: Boolean,
    val removeRootFiles: Boolean,
    val enableFilterStrings: Boolean,
    val unusedStringPath: String?,
    // unused.txt is written by R8 later in the build, so the existing candidate is chosen when the task runs.
    val unusedStringCandidates: List<String>,
    val languageWhiteList: List<String>,
    val storeFile: String?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?
) : Serializable {

    val usesBuildTime: Boolean get() = BUILD_TIME_PLACEHOLDER.containsMatchIn(fileNameTemplate)

    fun unusedStringFile(): File? {
        if (!enableFilterStrings) return null
        unusedStringPath?.let { return File(it) }
        return unusedStringCandidates.map(::File).firstOrNull { it.isFile }
    }

    /** Values that affect the produced files. Secrets are reduced to digests before Gradle stores them. */
    internal fun fingerprint(format: ResourceFormat): Map<String, String> {
        fun List<String>.stable() = sorted().joinToString("\n")
        return sortedMapOf(
            "format" to format.name,
            "enableObfuscate" to enableObfuscate.toString(),
            "useRandomName" to useRandomName.toString(),
            "enableCompressImages" to enableCompressImages.toString(),
            "enableMutateMd5" to enableMutateMd5.toString(),
            "mutateMd5Seed" to sha256(mutateMd5Seed),
            "allowDebugSignFallback" to allowDebugSignFallback.toString(),
            "enableVirusTotalUpload" to enableVirusTotalUpload.toString(),
            "virusTotalApiKey" to sha256(virusTotalApiKey),
            "whiteList" to whiteList.stable(),
            "bundleMetaDataWhiteList" to bundleMetaDataWhiteList.stable(),
            "mergeDuplicatedRes" to mergeDuplicatedRes.toString(),
            "enableFilterFiles" to enableFilterFiles.toString(),
            "filterList" to filterList.stable(),
            "removeBundleMetadata" to removeBundleMetadata.toString(),
            "removeRootFiles" to removeRootFiles.toString(),
            "enableFilterStrings" to enableFilterStrings.toString(),
            "languageWhiteList" to languageWhiteList.stable(),
            "fileNameTemplate" to fileNameTemplate,
            "fileNameVariables" to fileNameVariables.map { "${it.key}=${it.value}" }.stable(),
            "applicationId" to applicationId,
            "versionName" to (versionName ?: ""),
            "versionCode" to (versionCode?.toString() ?: ""),
            "keyAlias" to (keyAlias ?: ""),
            "storePassword" to sha256(storePassword ?: ""),
            "keyPassword" to sha256(keyPassword ?: "")
        )
    }

    /** Files whose contents affect the output; missing candidates are fine. */
    fun referencedFiles(): List<File> = listOfNotNull(
        mappingFile?.let(::File),
        storeFile?.let(::File),
        imageCompressionToolsDir.takeIf { it.isNotBlank() }?.let(::File)
    ) + if (!enableFilterStrings) emptyList()
        else unusedStringPath?.let { listOf(File(it)) } ?: unusedStringCandidates.map(::File)

    // Never print signing passwords or API keys.
    override fun toString(): String = description

    companion object {
        private const val serialVersionUID = 1L
        private val BUILD_TIME_PLACEHOLDER = Regex("""\{buildTime}""")

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun create(project: Project, config: AndResGuardExtension, variant: ApplicationVariant, format: String): AndResGuardSettings {
            val signing = getSigningConfig(project, variant)
            val output = variant.outputs.firstOrNull()
            val buildDirectory = project.layout.buildDirectory.get().asFile
            fun property(name: String) = (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
            return AndResGuardSettings(
                description = config.toString(),
                variantName = variant.name,
                applicationId = variant.applicationId.get(),
                versionName = output?.versionName?.orNull,
                versionCode = output?.versionCode?.orNull,
                outputDirectory = File(buildDirectory, "outputs/andresguard/$format/${variant.name}").absolutePath,
                enableObfuscate = config.enableObfuscate,
                mappingFile = config.mappingFile?.toAbsolutePath()?.toString(),
                useRandomName = config.useRandomName,
                enableCompressImages = config.enableCompressImages,
                imageCompressionToolsDir = config.imageCompressionToolsDir,
                imageCompressionCacheDir = (config.imageCompressionCacheDir?.toFile()
                    ?: project.rootDir.resolve(".gradle/andresguard/image-cache")).absolutePath,
                imageCompressionParallelism = property("andResGuard.imageCompressionParallelism")?.toIntOrNull()
                    ?: config.imageCompressionParallelism,
                enableMutateMd5 = config.enableMutateMd5,
                mutateMd5Seed = config.mutateMd5Seed,
                allowDebugSignFallback = config.allowDebugSignFallback,
                processIsolation = config.processIsolation,
                workerMaxHeapSize = property("andResGuard.workerMaxHeapSize") ?: config.workerMaxHeapSize,
                workerJvmArgs = config.workerJvmArgs.toList(),
                enableVirusTotalUpload = config.enableVirusTotalUpload,
                virusTotalApiKey = config.virusTotalApiKey,
                whiteList = config.whiteList.orEmpty().toList(),
                bundleMetaDataWhiteList = config.bundleMetaDataWhiteList.orEmpty().toList(),
                fileNameTemplate = (property("andResGuard.fileName") ?: config.obfuscatedFileName)
                    .ifBlank { "{applicationId}_{versionName}_{versionCode}" },
                fileNameVariables = config.fileNameVariables + project.properties
                    .filterKeys { it.startsWith("andResGuard.var.") }
                    .mapKeys { it.key.removePrefix("andResGuard.var.") }.mapValues { it.value.toString() },
                mergeDuplicatedRes = config.mergeDuplicatedRes,
                enableFilterFiles = config.enableFilterFiles,
                filterList = config.filterList.orEmpty().toList(),
                removeBundleMetadata = config.removeBundleMetadata,
                removeRootFiles = config.removeRootFiles,
                enableFilterStrings = config.enableFilterStrings,
                unusedStringPath = config.unusedStringPath?.takeIf { it.isNotBlank() },
                unusedStringCandidates = listOf(
                    File(buildDirectory, "outputs/mapping/${variant.name}/unused.txt").absolutePath,
                    File(buildDirectory, "outputs/mapping/${variant.name.removeSuffix("Release")}/release/unused.txt").absolutePath
                ),
                languageWhiteList = config.languageWhiteList.orEmpty().toList(),
                storeFile = signing.storeFile?.absolutePath,
                storePassword = signing.storePassword,
                keyAlias = signing.keyAlias,
                keyPassword = signing.keyPassword
            )
        }
    }
}
