package com.bytedance.android.plugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.bytedance.android.plugin.extensions.AndResGuardExtension
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
            project.tasks.register("andresguard$suffix", AndResGuardTask::class.java) { task ->
                task.variant = variant
                task.bundleFile = bundle
                task.apkDirectory = apk
                task.builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
                task.sdkDirectory = components.sdkComponents.sdkDirectory
                // Resolve after the DSL is configured; unselected formats never enter the task graph.
                task.dependsOn(project.provider {
                    val format = ResourceFormat.parse(extension.format)
                    buildList<Any> {
                        if (format.aab) add(bundle)
                        if (format.apk) add(apk)
                    }
                })
            }
        }
    }
}
