# kotlin-dataframex

Extensions for [Kotlin DataFrame](https://kotlin.github.io/dataframe/), currently targeting
`org.jetbrains.kotlinx:dataframe:1.0.0-Beta5`.

## Query Crux Console

```kotlin
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.openprojectx.dataframex.crux.readCrux

val frame = DataFrame.readCrux(
    query = """
        {:find [ticker name price]
         :where [[ticker :ticker/name name]
                 [ticker :ticker/price price]]}
    """.trimIndent(),
    endpoint = "http://localhost:5000/console/api/query",
)
```

The extension parses the EDN query structurally and uses the forms in `:find` as column names.
It supports bare or `{:query ...}`-wrapped map queries, vector queries, comments, aggregate
expressions, nested EDN values, keywords, UUIDs, and instants. Duplicate derived names receive
numeric suffixes.

For dependency injection or repeated queries, create a `CruxConsoleClient`. Its
`CruxQueryTransport`, `ColumnNameResolver`, and `CruxResultDecoder` collaborators are public
extension points so another HTTP API or response format can be added without changing the
DataFrame mapping.

## Tests

```shell
./gradlew test
```

`CruxConsoleE2ETest` uses Testcontainers with
`ghcr.io/openprojectx/crux-console:latest`, seeds its embedded Crux node, calls
`/console/api/query`, and verifies the resulting DataFrame. It is skipped automatically when
Docker is unavailable.

## Standalone example

Start Crux Console and seed documents with `:ticker/name` and `:ticker/price`, then run:

```shell
./gradlew :example:run --args="http://localhost:5000/console/api/query build/crux-example"
```

The example reads a DataFrame from Crux, filters and adds a calculated column, writes CSV and
HTML, and exports a Kandy bar chart as standalone HTML.

## Dependency image

The release workflow publishes `ghcr.io/openprojectx/kotlin-dataframex:<version>`. This is a
data image containing both locally published project artifacts and all runtime dependencies of
the example. It also contains `org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20`, matching this
project's version catalog, together with the plugin's transitive compiler and tooling dependencies.
The Gradle `kotlin-dsl` marker and `org.gradle.kotlin:gradle-kotlin-dsl-plugins:6.5.7` are included
with their transitive dependencies; `6.5.7` is the version embedded by this project's Gradle
9.5.0 wrapper:

- `/m2/repository` — a canonical Maven local repository with jars, POMs, and metadata.
- `/dependencies` — a flat directory of runtime jars for simple classpath use.

Extract either directory without running the image:

```shell
image=ghcr.io/openprojectx/kotlin-dataframex:latest
container=$(docker create "$image")
docker cp "$container:/m2/repository" ./m2-repository
docker cp "$container:/dependencies" ./dependencies
docker rm "$container"
```

To build it locally for the current snapshot version:

```shell
version=$(awk -F= '/^version/{gsub(/[[:space:]]/, "", $2); print $2}' gradle.properties)
docker build \
  --build-arg PROJECT_VERSION="$version" \
  -t kotlin-dataframex-dependencies .
```

The root `pom.xml` is the dependency manifest used by the image. Override its
`dataframex.version` property when resolving a different local publication:

```shell
./gradlew publishToMavenLocal -x test
mvn -Ddataframex.version=0.1.0-SNAPSHOT dependency:go-offline
```
