/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.plugin

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.telegram.tasks.localization.GenerateLocalizationUtilsJavaTask
import org.telegram.tasks.PrepareDeduplicatedResourcesTask

class TelegramBuildPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val androidComponents =
            project.extensions.findByType(AndroidComponentsExtension::class.java)
                ?: error("Apply com.android.application/library before org.telegram.build-plugin")

        val deduplicateBitmaps = project.name == "TMessagesProj"
        if (deduplicateBitmaps) {
            project.extensions.getByType(LibraryExtension::class.java)
                .sourceSets.getByName("main").res.setSrcDirs(emptyList<String>())
        }

        androidComponents.onVariants { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }

            if (deduplicateBitmaps) {
                val resources = project.tasks.register<PrepareDeduplicatedResourcesTask>(
                    "prepare${suffix}DeduplicatedResources"
                ) {
                    inputDir.set(project.layout.projectDirectory.dir("src/main/res"))
                    outputDir.set(project.layout.buildDirectory.dir("generated/deduplicatedResources/${variant.name}/res"))
                    project.rootProject.allprojects.forEach { owner ->
                        overlayFiles.from(owner.fileTree("src") {
                            include("**/res/drawable*/*", "**/res/values*/*.xml")
                            if (owner == project) exclude("main/res/**")
                        })
                    }
                }
                variant.sources.res?.addGeneratedSourceDirectory(resources, PrepareDeduplicatedResourcesTask::outputDir)
            }

            val task = project.tasks.register<GenerateLocalizationUtilsJavaTask>(
                "generate${suffix}LocalizationUtilsJava"
            ) {
                localizationFiles.from(
                    project.fileTree("src/main/res") {
                        include("values-*/strings*.xml", "values-*/banner_settings.xml", "values-*/wl.xml")
                    }
                )

                javaOutputDir.set(
                    project.layout.buildDirectory.dir(
                        "generated/generateLocalizationUtilsJava/${variant.name}"
                    )
                )
            }

            variant.sources.java?.addGeneratedSourceDirectory(
                task,
                GenerateLocalizationUtilsJavaTask::javaOutputDir
            )
        }
    }
}
