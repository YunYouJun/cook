package cn.yunyoujun.cook.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** The file envelope shared with Web and iOS for moving favorites and visits. */
object UserDataExportFile {
    // Leave room for the file metadata alongside a maximum-size sync document.
    const val MAX_FILE_BYTES = UserDataDocument.MAX_DOCUMENT_BYTES + 128
    private val legacyKeys = setOf(
        "version", "favorites", "favoriteRemovals", "histories", "historyRemovals", "historyClearedAt",
    )

    fun export(document: UserDataDocument, exportedAt: Long = System.currentTimeMillis()): String {
        document.validate()
        val payload = UserDataDocument.wireJson.encodeToJsonElement(UserDataDocument.serializer(), document).jsonObject
        return JsonObject(mapOf("app" to JsonPrimitive("cook"), "exportedAt" to JsonPrimitive(exportedAt)) + payload).toString()
    }

    fun parse(text: String): UserDataDocument {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            throw UserDataDocument.InvalidDocumentException("File exceeds $MAX_FILE_BYTES bytes")
        }
        val root = runCatching { UserDataDocument.wireJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw UserDataDocument.InvalidDocumentException("Malformed export JSON") }
        val version = root["version"] as? JsonPrimitive
        val recognizedFormat = (root["app"] == JsonPrimitive("cook") && "kind" !in root) || root.keys == legacyKeys
        if (!recognizedFormat || version == null || version.isString || version.intOrNull != UserDataDocument.SUPPORTED_VERSION) {
            throw UserDataDocument.InvalidDocumentException("Unrecognized export format")
        }
        return UserDataDocument.decode(JsonObject(root - "app" - "exportedAt").toString())
    }
}
