package org.openprojectx.dataframex.crux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FindClauseColumnNameResolverTest {
    @Test
    fun `reads names from map query while ignoring find text in comments and strings`() {
        val query = """
            {:note ":find [wrong]"
             ; :find [also-wrong]
             :find [market-title ticker-id (count price)]
             :where [[ticker-id :ticker/price price]]}
        """.trimIndent()

        assertEquals(
            listOf("market-title", "ticker-id", "count_price"),
            FindClauseColumnNameResolver.resolve(query),
        )
    }

    @Test
    fun `reads wrapped and vector queries and makes duplicate names safe`() {
        assertEquals(
            listOf("e", "price", "price_2"),
            FindClauseColumnNameResolver.resolve(
                "{:query {:where [[e :price price]] :find [e price price]}}",
            ),
        )
        assertEquals(
            listOf("e", "p"),
            FindClauseColumnNameResolver.resolve("[:find e p :where [e :price p]]"),
        )
    }

    @Test
    fun `rejects query without find`() {
        assertFailsWith<IllegalStateException> {
            FindClauseColumnNameResolver.resolve("{:where [[e :name n]]}")
        }
    }
}
