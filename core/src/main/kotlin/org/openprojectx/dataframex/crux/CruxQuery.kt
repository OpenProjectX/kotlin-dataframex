package org.openprojectx.dataframex.crux

/** Resolves positional result fields to DataFrame column names. */
fun interface ColumnNameResolver {
    fun resolve(query: String): List<String>
}

/** Reads column names from map and vector Datalog `:find` clauses. */
object FindClauseColumnNameResolver : ColumnNameResolver {
    override fun resolve(query: String): List<String> {
        val root = Edn.read(query)
        val queryForm = unwrapQuery(root)
        val find = when (queryForm) {
            is Map<*, *> -> queryForm.entries.firstOrNull { (key, _) -> key == EdnKeyword("find") }?.value
            is EdnVector -> formsAfterFind(queryForm.values)
            is EdnList -> formsAfterFind(queryForm.values)
            else -> null
        }
        val forms = when (find) {
            is EdnVector -> find.values
            is EdnList -> find.values
            is List<*> -> find
            null -> error("Query does not contain a :find clause")
            else -> listOf(find)
        }
        check(forms.isNotEmpty()) { "The query :find clause is empty" }
        return unique(forms.mapIndexed { index, form -> columnName(form, index) })
    }

    private fun unwrapQuery(root: Any?): Any? = if (root is Map<*, *>) {
        root.entries.firstOrNull { (key, _) -> key == EdnKeyword("query") }?.value ?: root
    } else {
        root
    }

    private fun formsAfterFind(values: List<Any?>): List<Any?>? {
        val index = values.indexOf(EdnKeyword("find"))
        if (index < 0) return null
        val end = (index + 1 until values.size)
            .firstOrNull { values[it] is EdnKeyword }
            ?: values.size
        return values.subList(index + 1, end)
    }

    private fun columnName(form: Any?, index: Int): String {
        val raw = when (form) {
            is EdnSymbol -> form.value
            is EdnKeyword -> form.value
            is EdnVector -> expressionName(form.values)
            is EdnList -> expressionName(form.values)
            else -> form?.toString().orEmpty()
        }
        return raw.trim().trimStart(':').replace(Regex("[^A-Za-z0-9_./?-]+"), "_")
            .trim('_').ifEmpty { "column${index + 1}" }
    }

    private fun expressionName(forms: List<Any?>): String = forms.joinToString("_") {
        when (it) {
            is EdnSymbol -> it.value
            is EdnKeyword -> it.value
            is EdnVector -> expressionName(it.values)
            is EdnList -> expressionName(it.values)
            else -> it.toString()
        }
    }

    private fun unique(names: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return names.map { name ->
            val count = counts.getOrDefault(name, 0) + 1
            counts[name] = count
            if (count == 1) name else "${name}_$count"
        }
    }
}
