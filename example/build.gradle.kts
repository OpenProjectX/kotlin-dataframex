plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.dataframe)
    implementation(libs.kandyLetsPlot)
}

application {
    mainClass.set("org.openprojectx.dataframex.example.CruxExampleKt")
}
