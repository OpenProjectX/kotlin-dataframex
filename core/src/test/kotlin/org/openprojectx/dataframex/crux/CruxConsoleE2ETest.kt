package org.openprojectx.dataframex.crux

import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
class CruxConsoleE2ETest {
    @Test
    fun `queries container data as a dataframe`() {
        val nodeEndpoint = URI.create("http://${console.host}:${console.getMappedPort(8080)}/tx-log")
        val transaction = """
            [[:crux.tx/put {:crux.db/id :ticker/acme :ticker/name "ACME" :ticker/price 42}]
             [:crux.tx/put {:crux.db/id :ticker/jetbrains :ticker/name "JetBrains" :ticker/price 73}]]
        """.trimIndent()
        val response = http.send(
            HttpRequest.newBuilder(nodeEndpoint)
                .header("Content-Type", "application/edn")
                .POST(HttpRequest.BodyPublishers.ofString(transaction))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "Seed transaction failed: ${response.body()}" }

        val client = CruxConsoleClient(
            "http://${console.host}:${console.getMappedPort(5000)}/console/api/query",
        )
        val query = """
            {:find [ticker name price]
             :where [[ticker :ticker/name name]
                     [ticker :ticker/price price]]
             :order-by [[price :asc]]}
        """.trimIndent()

        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var frame = client.query(query)
        while (frame.rowsCount() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(200)
            frame = client.query(query)
        }

        assertEquals(listOf("ticker", "name", "price"), frame.columnNames())
        assertEquals(2, frame.rowsCount())
        assertEquals("ACME", frame[0]["name"])
        assertEquals(42L, frame[0]["price"])
    }

    companion object {
        private val http: HttpClient = HttpClient.newHttpClient()

        @Container
        @JvmField
        val console = CruxContainer(DockerImageName.parse("ghcr.io/openprojectx/crux-console:latest"))
            .withExposedPorts(5000, 8080)
            .waitingFor(
                Wait.forHttp("/")
                    .forPort(8080)
                    .forStatusCodeMatching { it in 200..399 }
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
    }
}

class CruxContainer(image: DockerImageName) : GenericContainer<CruxContainer>(image)
