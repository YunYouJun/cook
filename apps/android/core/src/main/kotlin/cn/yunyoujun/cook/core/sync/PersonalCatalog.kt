package cn.yunyoujun.cook.core.sync

import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.StuffItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
enum class CatalogCategory(
    val rawValue: String,
    val title: String,
    val emoji: String,
) {
    @SerialName("vegetable")
    VEGETABLE("vegetable", "蔬菜", "🥬"),

    @SerialName("meat")
    MEAT("meat", "肉和蛋", "🥩"),

    @SerialName("staple")
    STAPLE("staple", "主食", "🍚"),

    @SerialName("tools")
    TOOLS("tools", "厨具", "🍳"),
}

@Serializable
data class CatalogEntry(
    val category: CatalogCategory,
    val name: String,
) {
    val id: String
        get() = "${if (category == CatalogCategory.TOOLS) "tool" else "stuff"}:$name"
}

sealed class PersonalCatalogException(message: String) : Exception(message) {
    class Invalid : PersonalCatalogException("无法识别食材和厨具配置。名称需为 1–30 个字符，不能包含换行或控制字符。")
    class Duplicate : PersonalCatalogException("这个名称已经存在；隐藏的内置条目可在下方恢复。")
    class TooLarge : PersonalCatalogException("文件不能超过 256 KB，最多包含 500 个自定义条目和 500 个隐藏条目。")
}

@Serializable
data class PersonalCatalog(
    val app: String = "cook",
    val kind: String = "personal-catalog",
    val version: Int = 1,
    val items: List<CatalogEntry> = emptyList(),
    val hidden: List<CatalogEntry> = emptyList(),
) {
    fun validate() {
        if (app != "cook" || kind != "personal-catalog" || version != 1) {
            throw PersonalCatalogException.Invalid()
        }
        for (entries in listOf(items, hidden)) {
            if (entries.size > MAX_ENTRIES) throw PersonalCatalogException.TooLarge()
            val seen = HashSet<String>()
            for (entry in entries) {
                if (entry.name.isEmpty() ||
                    entry.name != entry.name.trim() ||
                    entry.name.length > 30 ||
                    entry.name.any { it.code < 32 || it.code == 127 }
                ) {
                    throw PersonalCatalogException.Invalid()
                }
                if (!seen.add(entry.id)) {
                    throw PersonalCatalogException.Duplicate()
                }
            }
        }
    }

    fun export(): String {
        validate()
        val encoded = exportJson.encodeToString(this)
        if (encoded.toByteArray(Charsets.UTF_8).size > FILE_LIMIT) {
            throw PersonalCatalogException.TooLarge()
        }
        return encoded
    }

    fun merging(incoming: PersonalCatalog): PersonalCatalog {
        incoming.validate()
        val next = copy(
            items = items + incoming.items.filter { entry -> items.none { it.id == entry.id } },
            hidden = hidden + incoming.hidden.filter { entry -> hidden.none { it.id == entry.id } },
        )
        next.validate()
        return next
    }

    fun applyingTo(base: FoodCatalog, includeHidden: Boolean = false): FoodCatalog {
        fun group(category: CatalogCategory): List<StuffItem> {
            val originals = base.itemsIn(category)
            val originalNames = originals.map { it.name }.toSet()
            val keptOriginals = originals.filter { item ->
                includeHidden || CatalogEntry(category, item.name) !in hidden
            }
            val customAdded = items
                .filter { it.category == category && it.name !in originalNames }
                .map { StuffItem(name = it.name, emoji = category.emoji) }
            return keptOriginals + customAdded
        }
        return FoodCatalog(
            vegetable = group(CatalogCategory.VEGETABLE),
            meat = group(CatalogCategory.MEAT),
            staple = group(CatalogCategory.STAPLE),
            tools = group(CatalogCategory.TOOLS),
        )
    }

    companion object {
        const val FILE_LIMIT = 256 * 1024
        const val MAX_ENTRIES = 500
        private val ROOT_KEYS = setOf("app", "kind", "version", "items", "hidden")
        private val ENTRY_KEYS = setOf("category", "name")
        private val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        private val exportJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun parse(text: String): PersonalCatalog {
            if (text.toByteArray(Charsets.UTF_8).size > FILE_LIMIT) {
                throw PersonalCatalogException.TooLarge()
            }
            val obj = try {
                strictJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw PersonalCatalogException.Invalid()
            }
            if (obj.keys != ROOT_KEYS) throw PersonalCatalogException.Invalid()
            for (field in listOf("items", "hidden")) {
                val arr = runCatching { obj[field]!!.jsonArray }.getOrNull() ?: throw PersonalCatalogException.Invalid()
                for (el in arr) {
                    val entryObj = runCatching { el.jsonObject }.getOrNull() ?: throw PersonalCatalogException.Invalid()
                    if (entryObj.keys != ENTRY_KEYS) throw PersonalCatalogException.Invalid()
                }
            }
            val parsed = try {
                strictJson.decodeFromString<PersonalCatalog>(text)
            } catch (e: Exception) {
                throw PersonalCatalogException.Invalid()
            }
            parsed.validate()
            return parsed
        }
    }
}

fun FoodCatalog.itemsIn(category: CatalogCategory): List<StuffItem> = when (category) {
    CatalogCategory.VEGETABLE -> vegetable
    CatalogCategory.MEAT -> meat
    CatalogCategory.STAPLE -> staple
    CatalogCategory.TOOLS -> tools
}
