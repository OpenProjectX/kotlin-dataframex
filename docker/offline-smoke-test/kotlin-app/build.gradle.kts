plugins {
    kotlin("jvm")
}

val dataframexVersion = providers.environmentVariable("DATAFRAMEX_VERSION")
    .orNull
    ?: error("DATAFRAMEX_VERSION must identify the artifact published into the image")

dependencies {
    // Resolve the published example rather than only core so its complete runtime graph must be
    // present in the extracted image repository.
    implementation("org.openprojectx.kotlin.dataframex:example:$dataframexVersion")
}

kotlin {
    jvmToolchain(17)
}

val publishedExampleOutput = layout.buildDirectory.dir("published-example")

val runPublishedExample = tasks.register<JavaExec>("runPublishedExample") {
    description = "Runs the published example using only artifacts from the offline repository."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openprojectx.dataframex.example.CruxExampleKt")
    outputs.dir(publishedExampleOutput)

    doFirst {
        val endpoint = providers.environmentVariable("CRUX_CONSOLE_QUERY_URL")
            .orNull
            ?: error("CRUX_CONSOLE_QUERY_URL must point to the running CI test container")
        args(endpoint, publishedExampleOutput.get().asFile.absolutePath)
    }
}

tasks.register("verifyPublishedExample") {
    description = "Runs the published example and verifies its CSV, HTML, and Kandy outputs."
    group = "verification"
    dependsOn(runPublishedExample)

    doLast {
        val output = publishedExampleOutput.get().asFile
        listOf("tickers.csv", "tickers.html", "prices.html").forEach { filename ->
            val artifact = output.resolve(filename)
            check(artifact.isFile && artifact.length() > 0) {
                "Published example did not create a non-empty $artifact"
            }
        }
    }
}
