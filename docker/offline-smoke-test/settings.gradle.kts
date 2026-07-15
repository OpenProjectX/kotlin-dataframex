pluginManagement {
    val offlineRepository = providers.environmentVariable("OFFLINE_M2_REPO")
        .orNull
        ?: error("OFFLINE_M2_REPO must point to the extracted Maven repository")

    repositories {
        maven {
            name = "offlineImageRepository"
            url = uri(offlineRepository)
            metadataSources {
                gradleMetadata()
                mavenPom()
                artifact()
            }
        }
    }
}

dependencyResolutionManagement {
    val offlineRepository = providers.environmentVariable("OFFLINE_M2_REPO")
        .orNull
        ?: error("OFFLINE_M2_REPO must point to the extracted Maven repository")

    repositories {
        maven {
            name = "offlineImageRepository"
            url = uri(offlineRepository)
            metadataSources {
                gradleMetadata()
                mavenPom()
                artifact()
            }
        }
    }
}

rootProject.name = "dataframex-offline-smoke-test"
include("kotlin-app", "kotlin-build-logic")
