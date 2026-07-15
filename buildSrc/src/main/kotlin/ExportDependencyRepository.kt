import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Resolves the supplied configurations and exports Gradle's complete module cache to Maven layout.
 *
 * Gradle Module Metadata is not exposed as a normal resolved artifact. Exporting each resolved
 * module's cache entry preserves its POM, `.module` metadata, and all resolved classifier JARs.
 * This task is intended for a clean, isolated Gradle user home such as the dependency-image build.
 */
@DisableCachingByDefault(because = "The task materializes Gradle's external module cache")
abstract class ExportDependencyRepository : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependenciesToResolve: ConfigurableFileCollection

    @get:Internal
    abstract val gradleModuleCache: DirectoryProperty

    @get:OutputDirectory
    abstract val repositoryDirectory: DirectoryProperty

    init {
        gradleModuleCache.convention(
            project.layout.dir(
                project.provider {
                    project.gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
                },
            ),
        )
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun export() {
        // Evaluating the file collection resolves every configured dependency graph first.
        dependenciesToResolve.files

        val cacheRoot = gradleModuleCache.get().asFile.toPath()
        val repositoryRoot = repositoryDirectory.get().asFile.toPath()
        if (!Files.isDirectory(cacheRoot)) {
            throw GradleException("Gradle module cache does not exist: $cacheRoot")
        }

        var artifactCount = 0
        var moduleMetadataCount = 0

        Files.walk(cacheRoot).use { cachedPaths ->
            cachedPaths.filter { Files.isRegularFile(it) }.forEach { cachedArtifact ->
                val relative = cacheRoot.relativize(cachedArtifact)
                if (relative.nameCount < 5) {
                    throw GradleException("Unexpected Gradle cache path: $cachedArtifact")
                }

                val group = relative.getName(0).toString().replace('.', '/')
                val module = relative.getName(1).toString()
                val version = relative.getName(2).toString()
                val filename = relative.fileName.toString()
                val destination = repositoryRoot.resolve(group).resolve(module).resolve(version).resolve(filename)

                Files.createDirectories(destination.parent)
                if (Files.exists(destination)) {
                    if (Files.mismatch(cachedArtifact, destination) != -1L) {
                        throw GradleException(
                            "Conflicting cached artifacts map to the same Maven path: $destination",
                        )
                    }
                } else {
                    Files.copy(cachedArtifact, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }

                artifactCount++
                if (filename.endsWith(".module")) {
                    moduleMetadataCount++
                }
            }
        }

        if (artifactCount == 0 || moduleMetadataCount == 0) {
            throw GradleException(
                "Exported $artifactCount artifacts and $moduleMetadataCount Gradle metadata files from $cacheRoot",
            )
        }

        logger.lifecycle(
            "Exported {} cached artifacts, including {} .module files, to {}",
            artifactCount,
            moduleMetadataCount,
            repositoryRoot,
        )
    }
}
