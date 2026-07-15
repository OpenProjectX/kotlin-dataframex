# Dependency image

The release workflow publishes:

```text
ghcr.io/openprojectx/kotlin-dataframex:<version>
```

This is a data image for building and running DataFrameX without Maven Central or the Gradle Plugin
Portal. It contains:

- `/m2/repository`: a Maven-layout repository with project artifacts, JARs, POMs, Gradle `.module`
  metadata, plugin markers, and Kotlin Gradle Plugin classifier JARs.
- `/dependencies`: a flat directory of runtime JARs for simple classpath use.

## Included dependencies

The complete `example.runtimeClasspath` is included by default. Adding a runtime dependency to the
example automatically adds it and its transitive runtime graph to the image; it does not need to be
duplicated in the image configuration.

The image additionally includes:

- locally published `core` and `example` artifacts;
- Kotlin Gradle Plugin 2.3.21 and its compiler/tooling dependencies;
- matching `kotlin-gradle-plugin` and `kotlin-gradle-plugin-api` runtime classifiers for Gradle
  8.0, 8.1, 8.2, 8.5, 8.6, 8.8, 8.11, and 8.13;
- Kotlin DSL 6.6.4 and its transitive dependencies;
- Kotlin JVM, serialization, Kotlin DSL, Nexus publishing, and release plugin markers and
  implementations used by this build.

Some coordinates legitimately contain only a POM or Gradle metadata—for example plugin markers,
BOMs, and parent POMs. Runtime library coordinates must provide their referenced JARs. The offline
runtime test described below detects missing example runtime JARs.

## Extract the repository

```shell
image=ghcr.io/openprojectx/kotlin-dataframex:latest
container=$(docker create "$image")
docker cp "$container:/m2/repository" ./m2-repository
docker cp "$container:/dependencies" ./dependencies
docker rm "$container"
```

If the contents of `/m2/repository` are copied into `~/.m2/repository`, configure `mavenLocal()` as
the only repository for both plugins and dependencies:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
    }
}
```

For another extraction location, use a file repository instead:

```kotlin
maven(url = uri("/path/to/m2-repository"))
```

Then build without remote fallback:

```shell
./gradlew --offline build
```

## Build locally

```shell
version=$(awk -F= '/^version/{gsub(/[[:space:]]/, "", $2); print $2}' gradle.properties)
docker build \
  --build-arg PROJECT_VERSION="$version" \
  -t kotlin-dataframex-dependencies .
```

The `:example:exportDependencyRepository` Gradle task resolves the example runtime graph and Kotlin
plugin compatibility variants, then exports complete Gradle cache entries into Maven layout. Maven
finishes the standard POM graph and creates `/dependencies`.

The export task also reads every exported Gradle `.module` file and downloads all non-documentation
artifacts declared directly by its variants. This includes root/common artifacts such as
`kotlinpoet-2.3.0.jar` even when normal JVM resolution follows an `available-at` redirect to
`kotlinpoet-jvm`.

## Offline verification

The Docker build first compiles an isolated Kotlin application and Kotlin DSL build using an empty
Gradle module cache, the image repository as the only repository, and `--offline`.

CI performs a stronger runtime check with `docker/verify-dependency-image.sh`:

1. Extract `/m2/repository` from the built image.
2. Start `ghcr.io/openprojectx/crux-console:latest` and seed ticker documents.
3. Resolve the published `example` and its full runtime graph from only the extracted repository.
4. Run the real example with Gradle `--offline`.
5. Verify non-empty CSV, HTML table, and Kandy visualization outputs.

This makes a missing example runtime JAR or a missing pre-redirect artifact fail CI before the
dependency image is published.

See [Kotlin Gradle Plugin variant resolution](kotlin-gradle-plugin-variant-resolution.md) for the
`ProjectIsolationStartParameterAccessorG76` investigation and why both `.module` metadata and its
referenced classifier JARs are required.
