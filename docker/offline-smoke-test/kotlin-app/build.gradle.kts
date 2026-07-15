plugins {
    kotlin("jvm")
}

val dataframexVersion = providers.environmentVariable("DATAFRAMEX_VERSION")
    .orNull
    ?: error("DATAFRAMEX_VERSION must identify the artifact published into the image")

dependencies {
    implementation("org.openprojectx.kotlin.dataframex:core:$dataframexVersion")
}

kotlin {
    jvmToolchain(17)
}
