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
