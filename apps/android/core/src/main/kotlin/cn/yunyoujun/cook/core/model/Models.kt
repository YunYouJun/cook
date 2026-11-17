package cn.yunyoujun.cook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecipeChunkRef(
    val index: Int,
    val file: String? = null,
    val count: Int? = null,
)

@Serializable
data class RecipeMeta(
    val schemaVersion: Int,
    val dataVersion: String,
    val chunked: Boolean = false,
    val total: Int = 0,
    val version: Long = 0,
    val chunks: List<RecipeChunkRef>? = null,
)

@Serializable
data class Recipe(
    val id: Int,
    val name: String,
    val link: String? = null,
    val bv: String? = null,
    val stuff: List<String> = emptyList(),
    val emojis: List<String>? = null,
    val difficulty: String? = null,
    val tags: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val score: Int? = null,
) {
    val videoUrl: String?
        get() = bv?.takeIf { it.isNotEmpty() }?.let { "https://www.bilibili.com/video/$it" }
}

@Serializable
data class IncompatibleRule(
    val foodA: String,
    val foodB: String,
    val reason: String,
)

@Serializable
data class StuffItem(
    val name: String,
    val emoji: String = "",
    val image: String? = null,
    val alias: String? = null,
    val icon: String? = null,
    val label: String? = null,
    val more: Boolean? = null,
)

@Serializable
data class FoodCatalog(
    val vegetable: List<StuffItem> = emptyList(),
    val meat: List<StuffItem> = emptyList(),
    val staple: List<StuffItem> = emptyList(),
    val tools: List<StuffItem> = emptyList(),
)

@Serializable
data class Pantry(
    val options: List<String> = emptyList(),
    val defaults: List<String> = emptyList(),
)

@Serializable
data class QueryRules(
    val description: String? = null,
    val stuff: Map<String, String> = emptyMap(),
    val ignore: List<String> = emptyList(),
    val tools: Map<String, String> = emptyMap(),
    val have: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val lack: List<String> = emptyList(),
    val noSpicy: List<String> = emptyList(),
    val difficulty: Map<String, List<String>> = emptyMap(),
    val methodGroups: Map<String, List<String>> = emptyMap(),
    val mode: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ShelfLife(
    @SerialName("default")
    val defaultDays: Int = 5,
    val items: Map<String, Int> = emptyMap(),
)

@Serializable
enum class DietaryGoal(
    val rawValue: String,
    val title: String,
    val subtitle: String,
    val explanation: String,
) {
    @SerialName("weight-aware")
    WEIGHT_AWARE(
        rawValue = "weight-aware",
        title = "控制体重",
        subtitle = "查看份量、添加油用量参考",
        explanation = "按原文核对份量和添加油，不代表低热量或低脂，也不计算每日能量。",
    ),

    @SerialName("less-added-sugar")
    LESS_ADDED_SUGAR(
        rawValue = "less-added-sugar",
        title = "减少添加糖",
        subtitle = "寻找做法中未额外添加糖的菜",
        explanation = "核对原料、调料及步骤中的添加糖。不表示总糖为零、低碳水或适合血糖管理。",
    ),

    @SerialName("balanced-pairing")
    BALANCED_PAIRING(
        rawValue = "balanced-pairing",
        title = "均衡搭配",
        subtitle = "了解这道菜提供什么，还能搭配什么",
        explanation = "按做法中的食物类别给出搭配提示。单道菜不等于一餐营养齐全，仍需结合份量和整餐安排。",
    ),
}

@Serializable
enum class DietaryFoodGroup(
    val rawValue: String,
    val title: String,
) {
    @SerialName("staple")
    STAPLE("staple", "主食"),

    @SerialName("protein")
    PROTEIN("protein", "蛋白质来源"),

    @SerialName("vegetables")
    VEGETABLES("vegetables", "蔬菜"),
}

@Serializable
enum class RecommendationMode(val rawValue: String) {
    @SerialName("loose")
    LOOSE("loose"),

    @SerialName("strict")
    STRICT("strict"),

    @SerialName("survival")
    SURVIVAL("survival"),
}
