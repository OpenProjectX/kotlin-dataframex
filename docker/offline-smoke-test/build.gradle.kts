plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("org.gradle.kotlin.kotlin-dsl") version "6.6.4" apply false
}

// Repository declarations intentionally live only in settings.gradle.kts. Declaring both plugin
// versions at the root also keeps their shared Kotlin implementation in one classloader.
