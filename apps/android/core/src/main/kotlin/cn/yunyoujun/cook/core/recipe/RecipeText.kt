package cn.yunyoujun.cook.core.recipe

import cn.yunyoujun.cook.core.data.CookData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/**
 * 图文做法正文里的一段（packages/data/recipe-text.json，结构见 schema/recipe-text.schema.json）。
 * 文字是行内 Markdown，可能有加粗和外链；未知块类型按段落回退。
 */
@Serializable(with = RecipeTextBlockSerializer::class)
sealed interface RecipeTextBlock {
    data class Heading(val text: String) : RecipeTextBlock

    data class Paragraph(val text: String) : RecipeTextBlock

    data class ListBlock(
        val ordered: Boolean,
        val start: Int = 1,
        val items: List<String>,
    ) : RecipeTextBlock

    data class ImageBlock(
        val src: String,
        val width: Int,
        val height: Int,
        val alt: String? = null,
    ) : RecipeTextBlock
}

object RecipeTextBlockSerializer : KSerializer<RecipeTextBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RecipeTextBlock")

    override fun deserialize(decoder: Decoder): RecipeTextBlock {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RecipeTextBlock requires JSON decoder")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val type = obj["type"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            ?: throw SerializationException("RecipeTextBlock missing type")
        return when (type) {
            "heading" -> {
                val text = obj["text"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    ?: throw SerializationException("Heading block missing text")
                RecipeTextBlock.Heading(text)
            }
            "list" -> {
                val ordered = obj["ordered"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
                    ?: throw SerializationException("List block missing ordered")
                val start = obj["start"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int ?: 1
                val items = obj["items"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: throw SerializationException("List block missing items")
                RecipeTextBlock.ListBlock(ordered = ordered, start = start, items = items)
            }
            "image" -> {
                val src = obj["src"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    ?: throw SerializationException("Image block missing src")
                val width = obj["width"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
                    ?: throw SerializationException("Image block missing width")
                val height = obj["height"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
                    ?: throw SerializationException("Image block missing height")
                val alt = obj["alt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                RecipeTextBlock.ImageBlock(src = src, width = width, height = height, alt = alt)
            }
            else -> {
                val text = obj["text"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""
                RecipeTextBlock.Paragraph(text)
            }
        }
    }

    override fun serialize(encoder: Encoder, value: RecipeTextBlock) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RecipeTextBlock requires JSON encoder")
        val element: JsonObject = when (value) {
            is RecipeTextBlock.Heading -> buildJsonObject {
                put("type", "heading")
                put("text", value.text)
            }
            is RecipeTextBlock.Paragraph -> buildJsonObject {
                put("type", "paragraph")
                put("text", value.text)
            }
            is RecipeTextBlock.ListBlock -> buildJsonObject {
                put("type", "list")
                put("ordered", value.ordered)
                put("start", value.start)
                put("items", JsonArray(value.items.map { JsonPrimitive(it) }))
            }
            is RecipeTextBlock.ImageBlock -> buildJsonObject {
                put("type", "image")
                put("src", value.src)
                put("width", value.width)
                put("height", value.height)
                if (value.alt != null) put("alt", value.alt)
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }
}

@Serializable
data class RecipeText(
    val blocks: List<RecipeTextBlock>,
    @kotlinx.serialization.Transient
    val amountAnnotations: RecipeAmountAnnotations.Entry? = null,
) {
    val imageCount: Int
        get() = blocks.count { it is RecipeTextBlock.ImageBlock }
}

class RecipeTextLibrary(
    val source: Source,
    val imageBase: String? = null,
    private val recipes: Map<String, RecipeText>,
    private val amountAnnotations: RecipeAmountAnnotations? = null,
) {
    @Serializable
    data class Source(
        val name: String,
        val url: String,
        val license: String,
        val commit: String,
    )

    @Serializable
    private data class WireDocument(
        val source: Source,
        val imageBase: String? = null,
        val recipes: Map<String, RecipeText>,
    )

    val count: Int
        get() = recipes.size

    fun text(recipeId: Int): RecipeText? {
        val text = recipes[recipeId.toString()] ?: return null
        val annotations = amountAnnotations?.entry(recipeId, text)
        return text.copy(amountAnnotations = annotations)
    }

    fun imageURL(src: String): String? {
        val base = imageBase?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { URI(base).resolve(src).toString() }.getOrNull()
    }

    companion object {
        const val FILE_NAME = "recipe-text.json"

        fun decode(
            jsonText: String,
            amountAnnotations: RecipeAmountAnnotations? = null,
        ): RecipeTextLibrary {
            val doc = CookData.coreJson.decodeFromString<WireDocument>(jsonText)
            return RecipeTextLibrary(
                source = doc.source,
                imageBase = doc.imageBase,
                recipes = doc.recipes,
                amountAnnotations = amountAnnotations,
            )
        }

        fun loadFromDirectory(directory: File): RecipeTextLibrary? {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return null
            val annotations = RecipeAmountAnnotations.loadFromDirectory(directory)
            return decode(file.readText(Charsets.UTF_8), annotations)
        }
    }
}
