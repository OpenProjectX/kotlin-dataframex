import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

    /** Repositories used to retrieve files declared by metadata but not selected by resolution. */
    @get:Input
    abstract val artifactRepositoryUrls: ListProperty<String>

    init {
        gradleModuleCache.convention(
            project.layout.dir(
                project.provider {
                    project.gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
                },
            ),
        )
        artifactRepositoryUrls.convention(
            listOf(
                "https://repo.maven.apache.org/maven2",
                "https://plugins.gradle.org/m2",
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

        val declaredArtifactCount = materializeModuleArtifacts(repositoryRoot)

        if (artifactCount == 0 || moduleMetadataCount == 0) {
            throw GradleException(
                "Exported $artifactCount artifacts and $moduleMetadataCount Gradle metadata files from $cacheRoot",
            )
        }

        logger.lifecycle(
            "Exported {} cached artifacts, including {} .module files, and materialized {} metadata-declared artifacts to {}",
            artifactCount,
            moduleMetadataCount,
            declaredArtifactCount,
            repositoryRoot,
        )
    }

    /**
     * A Gradle variant can redirect to another module with `available-at`, while other variants in
     * the same metadata file directly declare artifacts. Normal JVM resolution follows the
     * redirect and therefore never puts those root/common artifacts in Gradle's cache. Preserve
     * all directly declared files so the exported repository remains useful before that redirect.
     */
    private fun materializeModuleArtifacts(repositoryRoot: java.nio.file.Path): Int {
        var downloaded = 0
        val moduleFiles = Files.walk(repositoryRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".module") }
                .toList()
        }

        moduleFiles.forEach { moduleFile ->
            val metadata = JsonSlurper().parse(moduleFile.toFile()) as? Map<*, *>
                ?: throw GradleException("Invalid Gradle module metadata: $moduleFile")
            val variants = metadata["variants"] as? List<*> ?: emptyList<Any>()
            variants.forEach { rawVariant ->
                val variant = rawVariant as? Map<*, *> ?: return@forEach
                val attributes = variant["attributes"] as? Map<*, *>
                val isDocumentation = attributes?.get("org.gradle.category") == "documentation"
                val isSources = attributes?.get("org.gradle.docstype") == "sources"
                if (isDocumentation && !isSources) return@forEach
                val files = variant["files"] as? List<*> ?: emptyList<Any>()
                files.forEach { rawFile ->
                    val file = rawFile as? Map<*, *> ?: return@forEach
                    val artifactUrl = file["url"] as? String
                        ?: throw GradleException("Artifact without a URL in $moduleFile")
                    val destination = moduleFile.parent.resolve(artifactUrl).normalize()
                    if (!destination.startsWith(moduleFile.parent)) {
                        throw GradleException("Unsafe artifact URL '$artifactUrl' in $moduleFile")
                    }

                    if (!Files.isRegularFile(destination)) {
                        downloadDeclaredArtifact(repositoryRoot, destination)
                        downloaded++
                    }
                    verifyChecksum(destination, file, moduleFile)
                }
            }
        }
        return downloaded
    }

    private fun downloadDeclaredArtifact(
        repositoryRoot: java.nio.file.Path,
        destination: java.nio.file.Path,
    ) {
        val relativeUrl = repositoryRoot.relativize(destination).joinToString("/")
        val failures = mutableListOf<String>()

        artifactRepositoryUrls.get().forEach { repositoryUrl ->
            val url = URI.create("${repositoryUrl.trimEnd('/')}/$relativeUrl").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "kotlin-dataframex-dependency-export")
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    failures += "$url returned HTTP $status"
                    return@forEach
                }

                Files.createDirectories(destination.parent)
                val temporary = Files.createTempFile(destination.parent, destination.fileName.toString(), ".part")
                try {
                    connection.inputStream.use { input ->
                        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                    }
                    try {
                        Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
                logger.info("Downloaded metadata-declared artifact {}", url)
                return
            } catch (exception: Exception) {
                failures += "$url failed: ${exception.message}"
            } finally {
                connection.disconnect()
            }
        }

        throw GradleException(
            "Could not download metadata-declared artifact $relativeUrl:\n${failures.joinToString("\n")}",
        )
    }

    private fun verifyChecksum(
        artifact: java.nio.file.Path,
        metadata: Map<*, *>,
        moduleFile: java.nio.file.Path,
    ) {
        val expected = when {
            metadata["sha512"] is String -> "SHA-512" to metadata["sha512"] as String
            metadata["sha256"] is String -> "SHA-256" to metadata["sha256"] as String
            metadata["sha1"] is String -> "SHA-1" to metadata["sha1"] as String
            else -> return
        }
        val digest = MessageDigest.getInstance(expected.first)
        Files.newInputStream(artifact).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected.second, ignoreCase = true)) {
            throw GradleException(
                "Checksum mismatch for $artifact declared by $moduleFile: expected ${expected.second}, got $actual",
            )
        }
    }
}
