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
            objects.named("9.5.0"),
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.dataframe)
    implementation(libs.kandyLetsPlot)

    dependencyImage(libs.kotlinGradlePlugin)
    dependencyImage(libs.gradleKotlinDslPlugins)
}

tasks.register("resolveDependencyImage") {
    description = "Resolves all artifacts whose Gradle module metadata is copied into the dependency image."
    group = "distribution"
    inputs.files(dependencyImage)
    doLast {
        dependencyImage.files
    }
}

application {
    mainClass.set("org.openprojectx.dataframex.example.CruxExampleKt")
}
