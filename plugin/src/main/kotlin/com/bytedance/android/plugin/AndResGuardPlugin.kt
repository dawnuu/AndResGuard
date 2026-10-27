package com.bytedance.android.plugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.bytedance.android.plugin.extensions.AndResGuardExtension
import com.bytedance.android.plugin.internal.AndResGuardSettings
import com.bytedance.android.plugin.internal.ResourceFormat
import com.bytedance.android.plugin.tasks.AndResGuardTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndResGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("com.android.application")) {
            throw GradleException("Apply com.android.application before AndResGuard.")
        }
        val extension = project.extensions.create("andResGuard", AndResGuardExtension::class.java)
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
            val apk = variant.artifacts.get(SingleArtifact.APK)
            fun registerProcessor(label: String, format: ResourceFormat) =
                project.tasks.register("andresguard$label$suffix", AndResGuardTask::class.java) { task ->
                    // Lazy, so the DSL is fully configured before the snapshot is taken.
                    task.settings.set(project.provider {
                        AndResGuardSettings.create(project, extension, variant, format.name.lowercase())
                    })
                    task.bundleFile = bundle
                    task.apkDirectory = apk
                    task.builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
                    task.sdkDirectory = components.sdkComponents.sdkDirectory
                    task.resourceFormat = format.name.lowercase()
                    task.description = "Build and process ${format.name} resources for ${variant.name}"
                    task.dependsOn(if (format.aab) bundle else apk)
                    task.trackInputsAndOutputs(format)
                }
            val aabProcessor = registerProcessor("Aab", ResourceFormat.AAB)
            val apkProcessor = registerProcessor("Apk", ResourceFormat.APK)
            project.tasks.register("andresguard$suffix") { task ->
                task.group = "andresguard"
                task.description = "Build and process AAB/APK resources according to andResGuard.format"
                // Resolve after the DSL is configured; unselected formats never enter the task graph.
                task.dependsOn(project.provider {
                    val format = ResourceFormat.parse(extension.format)
                    buildList<Any> {
                        if (format.aab) add(aabProcessor)
                        if (format.apk) add(apkProcessor)
                    }
                })
            }
            project.tasks.matching { it.name == "bundle$suffix" }.configureEach {
                it.dependsOn(aabProcessor)
            }
            project.tasks.matching { it.name == "assemble$suffix" }.configureEach {
                it.dependsOn(apkProcessor)
            }
        }
    }
}
