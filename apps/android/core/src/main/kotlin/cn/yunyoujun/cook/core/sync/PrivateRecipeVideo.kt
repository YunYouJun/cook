package cn.yunyoujun.cook.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 私人菜谱本地视频元数据（对齐 CookCore PrivateRecipeVideo / PrivateRecipeNativeFile）。
 * Android 端支持读取与保存 local 视频关联，iCloud 引用标记为不可在非 Apple 设备解析。
 */
@Serializable
data class PrivateRecipeVideo(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val fileExtension: String,
    val originalName: String,
    val byteCount: Long,
    val location: Location,
    val deviceID: String? = null,
) {
    @Serializable
    enum class Location(val rawValue: String) {
        @kotlinx.serialization.SerialName("local")
        LOCAL("local"),

        @kotlinx.serialization.SerialName("iCloud")
        ICLOUD("iCloud"),
    }

    val fileName: String
        get() = "$id.$fileExtension"

    val isAccessibleOnAndroid: Boolean
        get() = location == Location.LOCAL

    fun validated(): PrivateRecipeVideo {
        val normalizedId = runCatching { UUID.fromString(id).toString().lowercase() }.getOrNull()
        val deviceUuidValid = if (location == Location.ICLOUD) {
            deviceID == null
        } else {
            deviceID != null && runCatching { UUID.fromString(deviceID) }.isSuccess
        }
        val hasControlChars = originalName.any { it.isISOControl() }
        if (
            normalizedId != id ||
            fileExtension !in ALLOWED_EXTENSIONS ||
            byteCount <= 0L ||
            byteCount > BYTE_LIMIT ||
            originalName.isEmpty() ||
            originalName.length > 255 ||
            hasControlChars ||
            !deviceUuidValid
        ) {
            throw PrivateRecipeFileException.Invalid()
        }
        return this
    }

    companion object {
        const val BYTE_LIMIT: Long = 500L * 1024L * 1024L
        val ALLOWED_EXTENSIONS = setOf("mp4", "mov", "m4v")
    }
}

object PrivateRecipeNativeFile {
    private val wireJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        encodeDefaults = true
    }

    private val strictVideoJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val allowedVideoFields = setOf(
        "id",
        "fileExtension",
        "originalName",
        "byteCount",
        "location",
        "deviceID",
    )

    fun export(
        recipes: List<PrivateRecipe>,
        backup: Boolean = false,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val portableText = PrivateRecipeFile(
            recipes = recipes.map { it.copy(video = null) },
            exportedAt = exportedAt,
        ).export()
        val obj = Json.parseToJsonElement(portableText).jsonObject.toMutableMap()
        val videosMap = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (recipe in recipes) {
            val video = recipe.video ?: continue
            video.validated()
            videosMap[recipe.customId] = Json.encodeToJsonElement(PrivateRecipeVideo.serializer(), video)
        }
        obj["videos"] = JsonObject(videosMap)
        if (backup) {
            obj["version"] = JsonPrimitive(2)
        }
        val encoded = wireJson.encodeToString(JsonObject.serializer(), JsonObject(obj))
        if (encoded.toByteArray(Charsets.UTF_8).size > PrivateRecipeFile.BYTE_LIMIT) {
            throw PrivateRecipeFileException.TooLarge()
        }
        return encoded
    }

    fun parse(text: String): List<PrivateRecipe> {
        if (text.toByteArray(Charsets.UTF_8).size > PrivateRecipeFile.BYTE_LIMIT) {
            throw PrivateRecipeFileException.TooLarge()
        }
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw PrivateRecipeFileException.Invalid() }
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: throw PrivateRecipeFileException.Invalid()
        if (version != 1 && version != 2) throw PrivateRecipeFileException.Invalid()

        val portableObject = root.toMutableMap().apply {
            put("version", JsonPrimitive(1))
            remove("videos")
        }
        val parsed = PrivateRecipeFile.parse(JsonObject(portableObject).toString()).recipes.toMutableList()
        val rawVideos = root["videos"]
        if (rawVideos != null) {
            val videoEntries = (rawVideos as? JsonObject) ?: throw PrivateRecipeFileException.Invalid()
            val videos = LinkedHashMap<String, PrivateRecipeVideo>(videoEntries.size)
            val validRecipeIds = parsed.map { it.customId }.toSet()
            for ((customId, element) in videoEntries) {
                if (customId !in validRecipeIds) throw PrivateRecipeFileException.Invalid()
                val obj = element as? JsonObject ?: throw PrivateRecipeFileException.Invalid()
                if (!obj.keys.all { it in allowedVideoFields } || obj.values.any { it is JsonNull }) {
                    throw PrivateRecipeFileException.Invalid()
                }
                val video = runCatching {
                    strictVideoJson.decodeFromJsonElement(
                        PrivateRecipeVideo.serializer(),
                        obj,
                    )
                }.getOrElse { throw PrivateRecipeFileException.Invalid() }
                videos[customId] = video.validated()
            }
            for (i in parsed.indices) {
                val video = videos[parsed[i].customId]
                if (video != null) {
                    parsed[i] = parsed[i].copy(video = video)
                }
            }
        } else if (version == 2) {
            throw PrivateRecipeFileException.Invalid()
        }
        return parsed
    }
}
