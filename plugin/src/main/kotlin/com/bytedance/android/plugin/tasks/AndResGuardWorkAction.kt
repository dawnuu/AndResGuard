package com.bytedance.android.plugin.tasks

import com.bytedance.android.andresguard.commands.ObfuscateResourcesCommand
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Runs one AAB/APK processing pass. With process isolation this executes in a short-lived
 * worker JVM, so bundletool's in-memory model never occupies the Gradle daemon heap.
 */
abstract class AndResGuardWorkAction : WorkAction<AndResGuardWorkAction.Parameters> {
    interface Parameters : WorkParameters {
        val input: RegularFileProperty
        val output: RegularFileProperty
        val mappingFile: RegularFileProperty
        val enableObfuscate: Property<Boolean>
        val mergeDuplicatedRes: Property<Boolean>
        val enableCompressImages: Property<Boolean>
        val imageCompressionToolsDir: Property<String>
        val imageCompressionCacheDir: DirectoryProperty
        val imageCompressionParallelism: Property<Int>
        val apkBuildToolsDir: Property<String>
        val whiteList: SetProperty<String>
        val bundleMetaDataWhiteList: SetProperty<String>
        val enableFilterFiles: Property<Boolean>
        val filterList: SetProperty<String>
        val removeBundleMetadata: Property<Boolean>
        val removeRootFiles: Property<Boolean>
        val enableFilterStrings: Property<Boolean>
        val unusedStringPath: Property<String>
        val languageWhiteList: SetProperty<String>
        val useRandomName: Property<Boolean>
        val enableMutateMd5: Property<Boolean>
        val mutateMd5Seed: Property<String>
        val enableVirusTotalUpload: Property<Boolean>
        val virusTotalApiKey: Property<String>
        val storeFile: RegularFileProperty
        val storePassword: Property<String>
        val keyAlias: Property<String>
        val keyPassword: Property<String>
    }

    override fun execute() {
        val p = parameters
        val command = ObfuscateResourcesCommand.builder()
            .setEnableObfuscate(p.enableObfuscate.get())
            .setInputPath(p.input.get().asFile.toPath()).setOutputPath(p.output.get().asFile.toPath())
            .setMergeDuplicatedResources(p.mergeDuplicatedRes.get())
            .setEnableCompressImages(p.enableCompressImages.get())
            .setImageCompressionToolsDir(p.imageCompressionToolsDir.get())
            .setImageCompressionCacheDir(p.imageCompressionCacheDir.get().asFile.toPath())
            .setImageCompressionParallelism(p.imageCompressionParallelism.get())
            .setApkBuildToolsDir(p.apkBuildToolsDir.get())
            .setWhiteList(p.whiteList.get()).setBundleMetaDataWhiteList(p.bundleMetaDataWhiteList.get())
            .setFilterFile(p.enableFilterFiles.get()).setFileFilterRules(p.filterList.get())
            .setRemoveBundleMetadata(p.removeBundleMetadata.get()).setRemoveRootFiles(p.removeRootFiles.get())
            .setRemoveStr(p.enableFilterStrings.get()).setUnusedStrPath(p.unusedStringPath.get())
            .setLanguageWhiteList(p.languageWhiteList.get())
            .setUseRandomName(p.useRandomName.get()).setEnableMutateMd5(p.enableMutateMd5.get())
            .setMutateMd5Seed(p.mutateMd5Seed.get())
            .setEnableVirusTotalUpload(p.enableVirusTotalUpload.get()).setVirusTotalApiKey(p.virusTotalApiKey.get())
        if (p.mappingFile.isPresent) command.setMappingPath(p.mappingFile.get().asFile.toPath())
        if (p.storeFile.isPresent) {
            command.setStoreFile(p.storeFile.get().asFile.toPath()).setStorePassword(p.storePassword.get())
                .setKeyAlias(p.keyAlias.get()).setKeyPassword(p.keyPassword.get())
        }
        command.build().execute()
    }
}
