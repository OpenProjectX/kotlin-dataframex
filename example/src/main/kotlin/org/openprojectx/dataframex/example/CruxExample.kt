package org.openprojectx.dataframex.example

import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHtml
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.save
import org.jetbrains.kotlinx.kandy.letsplot.layers.bars
import org.openprojectx.dataframex.crux.CruxConsoleClient
import java.io.File

fun main(args: Array<String>) {
    val endpoint = args.firstOrNull()
        ?: System.getenv("CRUX_CONSOLE_QUERY_URL")
        ?: "http://localhost:5000/console/api/query"
    val output = File(args.getOrNull(1) ?: "build/crux-example").apply { mkdirs() }

    val query = """
        {:find [ticker name price]
         :where [[ticker :ticker/name name]
                 [ticker :ticker/price price]]
         :order-by [[price :asc]]}
    """.trimIndent()

    val transformed = CruxConsoleClient(endpoint)
        .query(query)
        .filter { (it["price"] as Number).toDouble() > 0 }
        .add("priceWithTax") { (it["price"] as Number).toDouble() * 1.2 }

    transformed.print()
    transformed.writeCsv(File(output, "tickers.csv"))
    transformed.toStandaloneHtml().writeHtml(File(output, "tickers.html"))

    transformed.plot {
        bars {
            x("name")
            y("priceWithTax")
        }
    }.save("prices.html", path = output.path)

    println("Wrote CSV, HTML table, and Kandy visualization to ${output.absolutePath}")
}
