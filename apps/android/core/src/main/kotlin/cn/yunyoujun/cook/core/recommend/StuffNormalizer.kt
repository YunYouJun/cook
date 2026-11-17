package cn.yunyoujun.cook.core.recommend

/**
 * Normalizes ingredient names through aliases (`aliases.json`), matching CookCore `StuffNormalizer`.
 */
class StuffNormalizer(
    val aliases: Map<String, String>,
) {
    fun normalize(name: String): String {
        val trimmed = name.trim()
        return aliases[trimmed] ?: trimmed
    }

    fun normalize(input: List<String>): List<String> = input.map { normalize(it) }

    fun unique(names: Iterable<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in names) {
            for (part in raw.split(*SEPARATORS)) {
                val canonical = normalize(part)
                if (canonical.isNotEmpty()) {
                    seen.add(canonical)
                }
            }
        }
        return seen.toList()
    }

    companion object {
        private val SEPARATORS = charArrayOf(',', '，', '、', ';', '；', '\n', '\r')
    }
}
