package org.openprojectx.dataframex.crux

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

internal data class EdnKeyword(val value: String)
internal data class EdnSymbol(val value: String)
internal data class EdnTagged(val tag: String, val value: Any?)
internal data class EdnList(val values: List<Any?>)
internal data class EdnVector(val values: List<Any?>)
internal data class EdnSet(val values: Set<Any?>)

internal object Edn {
    fun read(text: String): Any? = Parser(text).parseDocument()

    fun toKotlin(value: Any?): Any? = when (value) {
        is EdnKeyword -> value.value
        is EdnSymbol -> value.value
        is EdnList -> value.values.map(::toKotlin)
        is EdnVector -> value.values.map(::toKotlin)
        is EdnSet -> value.values.mapTo(linkedSetOf(), ::toKotlin)
        is EdnTagged -> when (value.tag) {
            "inst" -> (value.value as? String)?.let { text ->
                runCatching<Any> { Instant.parse(text) }.getOrElse { text }
            }
            "uuid" -> (value.value as? String)?.let { text ->
                runCatching<Any> { UUID.fromString(text) }.getOrElse { text }
            }
            else -> mapOf("tag" to value.tag, "value" to toKotlin(value.value))
        }
        is Map<*, *> -> value.entries.associate { (key, item) ->
            keyName(key) to toKotlin(item)
        }
        else -> value
    }

    private fun keyName(value: Any?): String = when (value) {
        is EdnKeyword -> value.value
        is EdnSymbol -> value.value
        else -> toKotlin(value).toString()
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun parseDocument(): Any? {
            skipIgnored()
            check(position < source.length) { "EDN input is empty" }
            val value = parseValue()
            skipIgnored()
            check(position == source.length) { "Unexpected EDN content at position $position" }
            return value
        }

        private fun parseValue(): Any? {
            skipIgnored()
            check(position < source.length) { "Unexpected end of EDN input" }
            return when (val char = source[position]) {
                '"' -> parseString()
                ':' -> EdnKeyword(parseToken().drop(1))
                '[' -> EdnVector(parseCollection('[', ']'))
                '(' -> EdnList(parseCollection('(', ')'))
                '{' -> parseMap()
                '#' -> parseDispatch()
                '\\' -> parseCharacter()
                else -> parseAtom(char)
            }
        }

        private fun parseCollection(open: Char, close: Char): List<Any?> {
            expect(open)
            val values = mutableListOf<Any?>()
            while (true) {
                skipIgnored()
                check(position < source.length) { "Unclosed '$open' collection" }
                if (source[position] == close) {
                    position++
                    return values
                }
                values += parseValue()
            }
        }

        private fun parseMap(): Map<Any?, Any?> {
            val values = parseCollection('{', '}')
            check(values.size % 2 == 0) { "EDN map requires an even number of forms" }
            return buildMap {
                values.chunked(2).forEach { (key, value) -> put(key, value) }
            }
        }

        private fun parseDispatch(): Any? {
            expect('#')
            if (peek('{')) return EdnSet(parseCollection('{', '}').toCollection(linkedSetOf()))
            if (peek('#')) {
                position++
                return when (val special = parseToken()) {
                    "Inf" -> Double.POSITIVE_INFINITY
                    "-Inf" -> Double.NEGATIVE_INFINITY
                    "NaN" -> Double.NaN
                    else -> error("Unsupported EDN symbolic value: ##$special")
                }
            }
            if (peek('_')) {
                position++
                parseValue()
                return parseValue()
            }
            val tag = parseToken()
            check(tag.isNotEmpty()) { "Missing EDN tag at position $position" }
            return EdnTagged(tag, parseValue())
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                when (val char = source[position++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        check(position < source.length) { "Unclosed EDN string escape" }
                        result.append(
                            when (val escaped = source[position++]) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                '\\' -> '\\'
                                '"' -> '"'
                                'u' -> {
                                    check(position + 4 <= source.length) { "Invalid unicode escape" }
                                    source.substring(position, position + 4).toInt(16).toChar().also { position += 4 }
                                }
                                else -> error("Unsupported EDN escape: \\$escaped")
                            },
                        )
                    }
                    else -> result.append(char)
                }
            }
            error("Unclosed EDN string")
        }

        private fun parseCharacter(): Char {
            expect('\\')
            val token = parseToken()
            return when (token) {
                "newline" -> '\n'
                "return" -> '\r'
                "space" -> ' '
                "tab" -> '\t'
                else -> token.singleOrNull() ?: error("Unsupported EDN character: \\$token")
            }
        }

        private fun parseAtom(first: Char): Any? {
            val token = parseToken()
            return when (token) {
                "nil" -> null
                "true" -> true
                "false" -> false
                else -> parseNumber(token) ?: EdnSymbol(token).also {
                    check(first != ',' && token.isNotEmpty()) { "Invalid EDN token at position $position" }
                }
            }
        }

        private fun parseNumber(token: String): Number? {
            val normalized = token.removeSuffix("N").removeSuffix("M")
            if (!normalized.firstOrNull().let { it == '+' || it == '-' || it?.isDigit() == true }) return null
            return runCatching {
                when {
                    token.endsWith("N") -> BigInteger(normalized)
                    token.endsWith("M") -> BigDecimal(normalized)
                    normalized.contains('.') || normalized.contains('e', true) -> normalized.toDouble()
                    else -> normalized.toLong()
                }
            }.getOrNull()
        }

        private fun parseToken(): String {
            val start = position
            while (position < source.length && !isDelimiter(source[position])) position++
            return source.substring(start, position)
        }

        private fun skipIgnored() {
            while (position < source.length) {
                when {
                    source[position].isWhitespace() || source[position] == ',' -> position++
                    source[position] == ';' -> while (position < source.length && source[position] != '\n') position++
                    else -> return
                }
            }
        }

        private fun expect(expected: Char) {
            check(position < source.length && source[position] == expected) {
                "Expected '$expected' at position $position"
            }
            position++
        }

        private fun peek(char: Char): Boolean = position < source.length && source[position] == char

        private fun isDelimiter(char: Char): Boolean =
            char.isWhitespace() || char == ',' || char in "[]{}()\";"
    }
}
