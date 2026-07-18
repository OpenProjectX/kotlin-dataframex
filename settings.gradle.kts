import java.io.File

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.openprojectx.gradle.dependency.bundle") {
                useVersion(providers.gradleProperty("dependencyBundlePluginVersion").get())
            }
        }
    }

    repositories {
        val isCi = System.getenv().containsKey("CI") ||
                System.getenv().containsKey("GITHUB_ACTIONS") ||
                System.getenv().containsKey("JENKINS_HOME")
        val useMirrors = System.getenv("USE_MIRRORS")?.toBooleanStrictOrNull() == true

        // Prefer the plugin bundle copied from the bootstrap image. This also avoids needless
        // remote lookups for the snapshot marker during local development.
        mavenLocal()

        if (!isCi || useMirrors) {
            maven(url = "https://mirrors.tencent.com/nexus/repository/maven-public/")
            maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        }

        // The dependency-bundle plugin is bootstrapped from its OCI image into Maven local in
        // CI/Docker. Released versions can be resolved from the configured remote repositories.
        System.getenv("DEPENDENCY_BUNDLE_PLUGIN_REPOSITORY")
            ?.takeIf { it.isNotBlank() }
            ?.let { maven(url = it) }

        // Plugin implementation artifacts are commonly published to Maven Central even when
        // their marker POM is hosted by the Plugin Portal.
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://repo.spring.io/plugins-release")
    }
}

dependencyResolutionManagement {
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
        gradlePluginPortal()
    }
}

rootProject.name = "kotlin-dataframex"

val excludeProjects: String? by settings

val buildFiles = fileTree(rootDir) {
    val excludes = excludeProjects?.split(",")
    include("**/*.gradle", "**/*.gradle.kts")
    exclude(
        "build",
        "**/gradle",
        "settings.gradle",
        "settings.gradle.kts",
        "buildSrc",
        "/build.gradle",
        "/build.gradle.kts",
        ".*",
        "out"
    )
    exclude("docker/offline-smoke-test/**")
    exclude("**/grails3")
    if (!excludes.isNullOrEmpty()) {
        exclude(excludes)
    }
}

val rootDirPath = rootDir.absolutePath + File.separator
buildFiles.forEach { buildFile ->
    val isDefaultName = buildFile.name.startsWith("build.gradle")
    val isKotlin = buildFile.name.endsWith(".kts")

    if (isDefaultName) {
        val buildFilePath = buildFile.parentFile.absolutePath
        val projectPath = buildFilePath
            .replace(rootDirPath, "")
            .replace(File.separator, ":")

        println("Adding project $projectPath")
        include(projectPath)
    } else {
        val projectName = if (isKotlin) {
            buildFile.name.removeSuffix(".gradle.kts")
        } else {
            buildFile.name.removeSuffix(".gradle")
        }

        val projectPath = ":$projectName"
        println("Adding project $projectPath")
        include(projectPath)

        val project = findProject(projectPath)
        project?.name = projectName
        project?.projectDir = buildFile.parentFile
        project?.buildFileName = buildFile.name
    }
}


gradle.extra["isCi"] = System.getenv().containsKey("CI") ||
        System.getenv().containsKey("GITHUB_ACTIONS") ||
        System.getenv().containsKey("JENKINS_HOME")
