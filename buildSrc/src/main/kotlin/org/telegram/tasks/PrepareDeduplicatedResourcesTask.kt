/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.*
import javax.inject.Inject

@CacheableTask
abstract class PrepareDeduplicatedResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overlayFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = inputDir.get().asFile
        val target = outputDir.get().asFile
        check(source.canonicalFile != target.canonicalFile &&
                !source.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()) &&
                !target.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) {
            "Generated resource directory must be separate from source artwork"
        }
        val plan = BitmapResourceDeduplicator.plan(source.toPath(), overlayFiles.files.map { it.toPath() })
        fileSystem.sync {
            from(source)
            into(target)
            exclude(plan.excludedPaths)
        }
        plan.aliasFiles.forEach { (relative, xml) ->
            val file = target.resolve(relative)
            file.parentFile.mkdirs()
            file.writeText(xml, Charsets.UTF_8)
        }
        logger.lifecycle("Bitmap aliases: ${plan.aliases.size} names, ${plan.excludedPaths.size} files, " +
                "${plan.duplicateBytes} duplicate source bytes; original artwork retained")
    }
}
