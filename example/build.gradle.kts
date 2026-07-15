import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.attributes.plugin.GradlePluginApiVersion

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

val dependencyImage by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.runtimeClasspath.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        attribute(
            GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
            objects.named("9.6.1"),
        )
    }
}

// Kotlin publishes separate plugin implementations for the supported Gradle API levels. Resolve
// every runtime variant so the exported Maven repository is consistent with the copied .module
// metadata rather than containing only the variant selected by this build's Gradle version.
val kotlinGradlePluginApiVersions = listOf("8.0", "8.1", "8.2", "8.5", "8.6", "8.8", "8.11", "8.13")
val kotlinGradlePluginVariants = kotlinGradlePluginApiVersions.map { gradleApiVersion ->
    configurations.create("kotlinGradlePlugin${gradleApiVersion.replace(".", "")}DependencyImage") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
            attribute(
                GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
                objects.named(gradleApiVersion),
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.dataframe)
    implementation(libs.kandyLetsPlot)

    dependencyImage(libs.kotlinGradlePlugin)
    dependencyImage(libs.gradleKotlinDslPlugins)

    kotlinGradlePluginVariants.forEach { configuration ->
        add(configuration.name, libs.kotlinGradlePlugin)
    }
}

tasks.register("resolveDependencyImage") {
    description = "Resolves runtime dependencies and all Kotlin Gradle plugin compatibility variants."
    group = "distribution"
    val resolvedArtifacts = files(listOf(dependencyImage) + kotlinGradlePluginVariants)
    inputs.files(resolvedArtifacts)
    doLast {
        resolvedArtifacts.files
    }
}

tasks.register<ExportDependencyRepository>("exportDependencyRepository") {
    description = "Exports resolved dependencies, Gradle metadata, and classifier JARs in Maven layout."
    group = "distribution"
    dependenciesToResolve.from(dependencyImage, kotlinGradlePluginVariants)
    val configuredOutput = layout.dir(
        providers.gradleProperty("dependencyRepositoryOutput").map { project.file(it) },
    )
    repositoryDirectory.set(configuredOutput.orElse(layout.buildDirectory.dir("dependency-repository")))
}

application {
    mainClass.set("org.openprojectx.dataframex.example.CruxExampleKt")
}
