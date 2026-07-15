# Kotlin Gradle Plugin variant resolution in an offline Maven repository

This document records the investigation of the following failure when Kotlin 2.3.21 is used
with the Gradle 9.6.1 wrapper and dependencies are supplied by a copied Maven local repository:

```text
java.lang.NoSuchMethodError:
  'org.gradle.internal.buildoption.BuildOption$Value
  org.gradle.api.internal.StartParameterInternal.getIsolatedProjects()'
    at org.jetbrains.kotlin.gradle.plugin.internal
      .ProjectIsolationStartParameterAccessorG76$isProjectIsolationEnabled$2.invoke(...)
```

## Finding

`ProjectIsolationStartParameterAccessorG76` is Kotlin Gradle Plugin's Gradle 7.6 compatibility
implementation. It is not a Gradle Kotlin DSL generated accessor, and its presence does not mean
that the wrapper is running Gradle 7.6. It means that dependency resolution loaded Kotlin's
Gradle 7.6 fallback plugin JAR.

For Kotlin Gradle Plugin 2.3.21, a Gradle 9.6.1 build should select the newest compatible
published variant:

```text
kotlin-gradle-plugin-2.3.21-gradle813.jar
kotlin-gradle-plugin-api-2.3.21-gradle813.jar
```

The `gradle813` variant uses `DefaultProjectIsolationStartParameterAccessor` and the public
`BuildFeatures.isolatedProjects` API. The locally selected `gradle813` plugin JAR does not contain
`ProjectIsolationStartParameterAccessorG76`.

## Why the fallback fails on Gradle 9.6.1

The Gradle 7.6 compatibility accessor was compiled against this internal method signature:

```text
BuildOption$Value StartParameterInternal.getIsolatedProjects()
```

Inspection of the Gradle 9.6.1 distribution used by this project shows this signature instead:

```text
Option$Value<Boolean> StartParameterInternal.getIsolatedProjects()
```

The return type is part of a JVM method descriptor. Although the Java method name is unchanged,
the descriptor is different, so code compiled against the earlier internal API fails with
`NoSuchMethodError`.

This illustrates why the fallback implementation must not be loaded when a newer compatible
plugin variant is available.

## How Kotlin's Gradle variants are selected

Kotlin 2.3.21 publishes several variants of the same logical plugin component, including variants
for Gradle 8.0, 8.1, 8.2, 8.5, 8.6, 8.8, 8.11, and 8.13. Kotlin's internal wrapper registration
selects implementations appropriate to the variant; names such as `G76` are Kotlin's compatibility
implementation names.

Gradle Module Metadata in `kotlin-gradle-plugin-2.3.21.module` describes the variants and their
artifacts. Each Gradle-specific variant declares an `org.gradle.plugin.api-version` attribute.
Gradle uses the running Gradle version as the consumer value, considers lower plugin API versions
compatible, and prefers the highest compatible version. Consequently, Gradle 9.6.1 selects the
Kotlin `gradle813` variant.

See:

- [Gradle plugin API version attribute](https://docs.gradle.org/current/userguide/variant_attributes.html#sub:gradle_plugin_api_version)
- [Kotlin Gradle Plugin 2.3.21 artifacts](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.3.21/)
- [Kotlin Gradle Plugin API 2.3.21 artifacts](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.3.21/)

When the `.module` file is missing, Gradle can fall back to Maven POM metadata. A Maven POM cannot
represent these Gradle variants, so resolution can load the unclassified fallback artifact:

```text
kotlin-gradle-plugin-2.3.21.jar
```

That fallback contains the Gradle 7.6 compatibility implementation and produces the observed
`G76` stack trace on Gradle 9.6.1.

## Requirements for a copied Maven repository

Copying only POMs and unclassified JARs is insufficient. Copying only `.module` files is also
insufficient: every artifact referenced by those files must exist in the same repository.

At minimum, the Kotlin plugin entries used by this build must include:

```text
org/jetbrains/kotlin/kotlin-gradle-plugin/2.3.21/
├── kotlin-gradle-plugin-2.3.21.module
├── kotlin-gradle-plugin-2.3.21.pom
├── kotlin-gradle-plugin-2.3.21.jar
└── kotlin-gradle-plugin-2.3.21-gradle813.jar

org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.3.21/
├── kotlin-gradle-plugin-api-2.3.21.module
├── kotlin-gradle-plugin-api-2.3.21.pom
├── kotlin-gradle-plugin-api-2.3.21.jar
└── kotlin-gradle-plugin-api-2.3.21-gradle813.jar
```

Source JARs and checksums may also be copied, but they are not required to execute the plugin.
Plugin marker POMs are required when the consumer applies Kotlin through a `plugins {}` block.

Gradle associates component metadata and its artifacts with the repository from which the
component was resolved. If `mavenLocal()` supplies the `.module` file but does not contain the
referenced `gradle813` JAR, Gradle should not be expected to complete that component by combining
files from a later JFrog or Maven Central repository.

The image's synchronization step must therefore copy the resolved variant JARs as well as the
`.module` files from the Gradle cache into canonical Maven repository paths.

## Consumer repository configuration

Normal dependencies and plugins have separate repository configuration. A consumer that applies
Kotlin with the plugins DSL must put `mavenLocal()` in `pluginManagement.repositories`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        // Internal repositories follow.
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // Internal repositories follow.
    }
}
```

Repository configuration for an independent `buildSrc` or included build must be applied in that
build's settings as well.

## Verification

First inspect the copied repository:

```shell
repo="$HOME/.m2/repository/org/jetbrains/kotlin"

find "$repo/kotlin-gradle-plugin/2.3.21" -maxdepth 1 -type f -printf '%f\n' | sort
find "$repo/kotlin-gradle-plugin-api/2.3.21" -maxdepth 1 -type f -printf '%f\n' | sort
```

Both `.module` files and both `gradle813` JARs should be present. Then stop daemons that may already
have loaded the fallback plugin and refresh dependency metadata:

```shell
./gradlew --stop
./gradlew help --refresh-dependencies --info
```

The diagnostic output should show selection of the `gradle813` runtime variant and the
`*-gradle813.jar` artifacts. A stack trace containing
`ProjectIsolationStartParameterAccessorG76` is evidence that the fallback plugin artifact is still
being loaded.

For a configuration that exposes the plugin dependency, `dependencyInsight` can display the
selected variant and its attributes:

```shell
./gradlew dependencyInsight \
  --configuration <plugin-classpath-configuration> \
  --dependency org.jetbrains.kotlin:kotlin-gradle-plugin
```

The exact configuration name depends on whether the plugin is resolved by the root build,
`buildSrc`, or an included build.
