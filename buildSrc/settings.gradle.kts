pluginManagement {
    repositories {
        val isCi = System.getenv().containsKey("CI") ||
                System.getenv().containsKey("GITHUB_ACTIONS") ||
                System.getenv().containsKey("JENKINS_HOME")
        val useMirrors = System.getenv("USE_MIRRORS")?.toBooleanStrictOrNull() == true

        if (!isCi || useMirrors) {
            maven(url = "https://mirrors.tencent.com/nexus/repository/maven-public/")
            maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {

    // Use Maven Central and the Gradle Plugin Portal for resolving dependencies in the shared build logic (`buildSrc`) project.
    @Suppress("UnstableApiUsage")
    repositories {
        val isCi = System.getenv().containsKey("CI") ||
                System.getenv().containsKey("GITHUB_ACTIONS") ||
                System.getenv().containsKey("JENKINS_HOME")
        val useMirrors = System.getenv("USE_MIRRORS")?.toBooleanStrictOrNull() == true

        if (!isCi || useMirrors) {
            maven(url = "https://mirrors.tencent.com/nexus/repository/maven-public/")
            maven(url = "https://maven.aliyun.com/repository/public/")
        }
        mavenCentral()
    }

    // Reuse the version catalog from the main build.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
