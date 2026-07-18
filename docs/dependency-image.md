# Dependency bundle image

Releases publish `ghcr.io/openprojectx/kotlin-dataframex:<version>`. The image is a portable data
bundle for environments without Maven Central or the Gradle Plugin Portal:

- `/m2/repository` contains project publications, runtime and build dependencies, POMs, Gradle
  `.module` metadata, source JARs, plugin markers, and Kotlin Gradle Plugin classifier JARs.
- `/dependencies` contains the example's runtime JARs as a flat classpath.
- `/dependency-bundle/dependency-graph.json` preserves the resolved graph, variants, edges, reasons,
  checksums, and artifact paths.
- `/dependency-bundle/dependency-graph.txt` is a human-readable dependencies-style report.

The `org.openprojectx.gradle.dependency.bundle` plugin owns graph capture and Maven-layout export.
This project only declares what it needs: all `runtimeClasspath` and `testRuntimeClasspath`
configurations, buildscript dependencies, sources, Kotlin DSL 6.6.4, and Kotlin Gradle Plugin 2.3.21
variants for Gradle 8.0 through 8.13. Adding an example runtime dependency automatically includes
its transitive graph.

## Build and inspect

The plugin is bootstrapped from its own image, so the DataFrameX Docker build does not first need a
plugin portal:

```shell
version=$(awk -F= '/^version/{gsub(/[[:space:]]/, "", $2); print $2}' gradle.properties)
docker build --build-arg PROJECT_VERSION="$version" -t kotlin-dataframex-dependencies .
```

Local Docker builds use the configured Tencent and Aliyun Maven mirrors. Pass
`--build-arg USE_MIRRORS=false` to match CI, which resolves directly from the standard upstream
repositories.

To produce the same bundle directly with Gradle:

```shell
./gradlew prepareDependencyBundle
```

The default output is `build/dependency-bundle`. Override it with
`-PdependencyBundleOutput=/some/path`.

## Use in a restricted environment

```shell
image=ghcr.io/openprojectx/kotlin-dataframex:latest
container=$(docker create "$image")
mkdir -p "$HOME/.m2/repository"
docker cp "$container:/m2/repository/." "$HOME/.m2/repository"
docker rm "$container"
```

Configure the copied repository for both plugin and ordinary dependency resolution:

```kotlin
// settings.gradle.kts
pluginManagement.repositories { mavenLocal() }
dependencyResolutionManagement.repositories { mavenLocal() }
```

An independent `buildSrc` or included build needs the same repository configuration. Then verify
that no remote fallback is possible:

```shell
./gradlew --offline build
```

For JFrog gap analysis, keep the serialized graph and run the plugin's audit in the restricted
environment:

```shell
./gradlew auditArtifactRepository \
  -PartifactRepositoryUrl=https://jfrog.example/artifactory/maven-virtual
```

Set `JFROG_USERNAME` and `JFROG_PASSWORD` when authentication is required. The report is written to
`build/reports/dependency-audit` and retains graph context, rather than producing only a flat list.

## Verification

The Docker build compiles a Kotlin DSL smoke project with an empty Gradle module cache, the bundled
Maven repository as its only repository, and `--offline`. CI additionally runs
`docker/verify-dependency-image.sh`, which starts Crux Console, resolves the published independent
example from the image, runs it, and checks its CSV, HTML, and Kandy outputs.

Some coordinates correctly contain only metadata, such as BOMs, parent POMs, and plugin markers.
Runtime coordinates and artifacts referenced by `.module` files must contain their JARs. CI checks
the pre-redirect KotlinPoet JAR and sources explicitly and fails on missing runtime artifacts.

See [Kotlin Gradle Plugin variant resolution](kotlin-gradle-plugin-variant-resolution.md) for the
`ProjectIsolationStartParameterAccessorG76` investigation.
