import net.researchgate.release.ReleaseExtension

plugins {
    `maven-publish`
    signing
    id("org.openprojectx.gradle.dependency.bundle")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0" // nexus publish/close/release
    id("net.researchgate.release") version "3.1.0"

}

val dependencyBundleOutput = layout.dir(
    providers.gradleProperty("dependencyBundleOutput").map { file(it) },
).orElse(layout.buildDirectory.dir("dependency-bundle"))

dependencyBundle {
    configurations.addAll("runtimeClasspath", "testRuntimeClasspath")
    includeBuildDependencies.set(true)
    includeSources.set(true)
    outputDirectory.set(dependencyBundleOutput)
    module("org.gradle.kotlin:gradle-kotlin-dsl-plugins:${libs.versions.kotlinDsl.get()}")
    module(
        "org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:" +
                libs.versions.kotlinDsl.get(),
    )
    module(
        "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:" +
                libs.versions.kotlin.get(),
    )
    gradleApiVariants(
        "org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}",
        listOf("8.0", "8.1", "8.2", "8.5", "8.6", "8.8", "8.11", "8.13"),
    )
}

allprojects {
    group = "org.openprojectx.kotlin.dataframex"
}


subprojects {
    tasks.register<DependencyReportTask>("allDependencies") {}

    // Apply to every module (safe even if a module doesn't publish)
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // Configure publishing only when the project has a Java component (Kotlin/JVM typically applies java too)
    plugins.withId("java") {

        // ✅ Ensure required artifacts exist for Maven Central
        extensions.configure<JavaPluginExtension>("java") {
            withSourcesJar()
            withJavadocJar()
        }

        // Kotlin-only modules can produce "empty-ish" Javadoc; don't fail the build on doclint/errors
        tasks.withType(Javadoc::class.java).configureEach {
            isFailOnError = false
        }


        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "dependencyBundle"
                    url = dependencyBundleOutput.get().dir("m2/repository").asFile.toURI()
                }
            }
            publications {
                // Create once per project
                if (findByName("mavenJava") == null) {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])

                        // Prefer explicit artifactId; by default it's project.name
                        artifactId = project.name

                        pom {
                            // Module-specific name/description; override per-module if you want
                            name.set(project.name)
                            description.set("KOTLIN-DATAFRAMEX Spring Boot starter")
                            url.set("https://github.com/OpenProjectX/kotlin-dataframex")

                            licenses {
                                license {
                                    name.set("Apache License 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                }
                            }

                            developers {
                                developer {
                                    id.set("OpenProjectX")
                                    name.set("OpenProjectX")
                                    email.set("admin@openprojectx.org")
                                }
                            }

                            scm {
                                url.set("https://github.com/OpenProjectX/kotlin-dataframex")
                                connection.set("scm:git:https://github.com/OpenProjectX/kotlin-dataframex.git")
                                developerConnection.set("scm:git:ssh://git@github.com:OpenProjectX/kotlin-dataframex.git")
                            }
                        }
                    }
                }
            }
        }
    }

    // Signing: only configure keys if provided (keeps local dev painless)
    extensions.configure<SigningExtension>("signing") {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

        if (!keyFile.isNullOrBlank()) {
            val keyText = file(keyFile).readText()
            useInMemoryPgpKeys(keyText, keyPass)

            // Sign all publications created in this subproject
            val publishing = extensions.findByType(PublishingExtension::class.java)
            if (publishing != null) {
                sign(publishing.publications)
            }
        }
    }
}

val publishDependencyBundleArtifacts = tasks.register("publishDependencyBundleArtifacts") {
    group = "dependency bundle"
    description = "Publishes DataFrameX modules into the portable dependency repository."
}

gradle.projectsEvaluated {
    publishDependencyBundleArtifacts.configure {
        dependsOn(subprojects.flatMap { subproject ->
            subproject.tasks.matching {
                it.name.startsWith("publish") && it.name.endsWith("ToDependencyBundleRepository")
            }.toList()
        })
    }
    tasks.named("exportDependencyBundle") {
        dependsOn(publishDependencyBundleArtifacts)
    }
}

val stageDependencyBundleRuntime = tasks.register<Sync>("stageDependencyBundleRuntime") {
    group = "dependency bundle"
    description = "Stages the example runtime classpath as a convenient flat JAR directory."
    val exampleProject = project(":example")
    dependsOn(exampleProject.tasks.named("jar"))
    from(exampleProject.configurations.named("runtimeClasspath"))
    from(exampleProject.tasks.named("jar"))
    into(dependencyBundleOutput.map { it.dir("dependencies") })
}

tasks.register("prepareDependencyBundle") {
    group = "dependency bundle"
    description = "Creates the Maven repository, graph reports, and flat example runtime JARs."
    dependsOn("dependencyBundleReport", stageDependencyBundleRuntime)
}


nexusPublishing {

    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))

        }
    }
}

configure<ReleaseExtension> {
    buildTasks.set(listOf("publishToSonatype", "closeAndReleaseSonatypeStagingRepository"))
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")

    with(git) {
        requireBranch.set("master")
    }
}
