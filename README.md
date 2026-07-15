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

Column names are derived structurally from the query's `:find` clause. `CruxConsoleClient` exposes
replaceable transport, column-resolution, and result-decoding collaborators for future APIs.

## Tests

```shell
./gradlew test
```

The end-to-end test uses `ghcr.io/openprojectx/crux-console:latest` and is skipped when Docker is
unavailable.

## Standalone example

Start Crux Console and seed documents with `:ticker/name` and `:ticker/price`, then run:

```shell
./gradlew :example:run --args="http://localhost:5000/console/api/query build/crux-example"
```

It reads and transforms Crux data, then writes CSV, HTML, and a Kandy visualization.

## Dependency image

`ghcr.io/openprojectx/kotlin-dataframex:<version>` contains the project artifacts, the example's
complete runtime dependency graph by default, and the Gradle/Kotlin tooling needed for offline
builds. See the [dependency image guide](docs/dependency-image.md).
