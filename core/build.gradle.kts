plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-library`
}


dependencies {
    api(libs.dataframe)

    testImplementation(kotlin("test"))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainersJunit)
}
