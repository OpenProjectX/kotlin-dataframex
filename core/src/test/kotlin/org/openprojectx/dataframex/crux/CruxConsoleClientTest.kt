package org.openprojectx.dataframex.crux

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CruxConsoleClientTest {
    @Test
    fun `posts EDN and creates typed dataframe`() {
        var receivedBody = ""
        var receivedContentType = ""
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/console/api/query") { exchange ->
                receivedBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
                val response = "([:ticker/a 12 true] [:ticker/b 19.5 false])".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/edn")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val query = "{:find [ticker price active] :where [[ticker :price price]]}"
            val frame = CruxConsoleClient("http://localhost:${server.address.port}/console/api/query").query(query)

            assertEquals(query, receivedBody)
            assertEquals("application/edn", receivedContentType)
            assertEquals(listOf("ticker", "price", "active"), frame.columnNames())
            assertEquals("ticker/a", frame[0]["ticker"])
            assertEquals(12L, frame[0]["price"])
            assertEquals(19.5, frame[1]["price"])
            assertEquals(false, frame[1]["active"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `maps nested EDN values and tagged literals`() {
        val client = CruxConsoleClient(
            transport = CruxQueryTransport {
                "([[1 2] {:name \"Ada\" :roles #{:admin :author}} #uuid \"123e4567-e89b-12d3-a456-426614174000\"])"
            },
        )
        val frame = client.query("{:find [numbers person id] :where []}")

        assertEquals(listOf(1L, 2L), frame[0]["numbers"])
        assertEquals(mapOf("name" to "Ada", "roles" to linkedSetOf("admin", "author")), frame[0]["person"])
        assertEquals("123e4567-e89b-12d3-a456-426614174000", frame[0]["id"].toString())
    }

    @Test
    fun `empty result keeps columns from find clause`() {
        val frame = CruxConsoleClient(CruxQueryTransport { "()" })
            .query("{:find [ticker price] :where [[ticker :price price]]}")

        assertEquals(listOf("ticker", "price"), frame.columnNames())
        assertEquals(0, frame.rowsCount())
    }

    @Test
    fun `reports HTTP errors with response body`() {
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/query") { exchange ->
                val response = "{:error \"bad query\"}".toByteArray()
                exchange.sendResponseHeaders(400, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val error = assertFailsWith<CruxHttpException> {
                CruxConsoleClient("http://localhost:${server.address.port}/query")
                    .query("{:find [e] :where []}")
            }
            assertEquals(400, error.statusCode)
            assertEquals("{:error \"bad query\"}", error.responseBody)
        } finally {
            server.stop(0)
        }
    }
}
