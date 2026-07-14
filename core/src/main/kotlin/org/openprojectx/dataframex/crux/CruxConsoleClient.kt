package org.openprojectx.dataframex.crux

import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface CruxQueryTransport {
    fun execute(query: String): String
}

fun interface CruxResultDecoder {
    fun decode(response: String): List<List<Any?>>
}

object EdnCruxResultDecoder : CruxResultDecoder {
    override fun decode(response: String): List<List<Any?>> {
        val root = Edn.read(response)
        val resultRows = when (root) {
            is EdnList -> root.values
            is EdnVector -> root.values
            is EdnSet -> root.values.toList()
            else -> error("Expected an EDN result collection, got ${root?.javaClass?.simpleName ?: "nil"}")
        }
        return resultRows.map { row ->
            val values = when (row) {
                is EdnVector -> row.values
                is EdnList -> row.values
                else -> listOf(row)
            }
            values.map(Edn::toKotlin)
        }
    }
}

class CruxHttpException(
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException("Crux Console query failed with HTTP $statusCode: $responseBody")

class HttpCruxQueryTransport(
    private val endpoint: URI = DEFAULT_ENDPOINT,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : CruxQueryTransport {
    constructor(endpoint: String) : this(URI.create(endpoint))

    override fun execute(query: String): String {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", "application/edn")
            .header("Content-Type", "application/edn")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw CruxHttpException(response.statusCode(), response.body())
        }
        return response.body()
    }

    companion object {
        val DEFAULT_ENDPOINT: URI = URI.create("http://localhost:5000/console/api/query")
    }
}

/**
 * Queries Crux Console and maps each positional EDN tuple to a dynamic DataFrame row.
 * Transport, column resolution, and decoding are replaceable extension points.
 */
class CruxConsoleClient(
    private val transport: CruxQueryTransport = HttpCruxQueryTransport(),
    private val columnNameResolver: ColumnNameResolver = FindClauseColumnNameResolver,
    private val resultDecoder: CruxResultDecoder = EdnCruxResultDecoder,
) {
    constructor(endpoint: String) : this(HttpCruxQueryTransport(endpoint))

    fun query(query: String): DataFrame<*> {
        val columnNames = columnNameResolver.resolve(query)
        val rows = resultDecoder.decode(transport.execute(query))
        rows.forEachIndexed { index, row ->
            require(row.size == columnNames.size) {
                "Result row $index has ${row.size} values, but :find defines ${columnNames.size} columns"
            }
        }
        val columns = columnNames.mapIndexed { columnIndex, name ->
            name to rows.map { it[columnIndex] }
        }
        return dataFrameOf(*columns.toTypedArray())
    }
}

fun DataFrame.Companion.readCrux(
    query: String,
    endpoint: String = HttpCruxQueryTransport.DEFAULT_ENDPOINT.toString(),
): DataFrame<*> = CruxConsoleClient(endpoint).query(query)
