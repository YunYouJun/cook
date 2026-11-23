package cn.yunyoujun.cook.data.remote

import android.net.Uri
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentException
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.ai.SearchIntentValidator
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.ai.StepGuidanceValidator
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.model.RecommendationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID

data class HttpResponsePayload(
    val statusCode: Int,
    val bodyText: String,
)

internal class HttpResponseTooLargeException(maxBytes: Int) :
    IllegalStateException("Response exceeded $maxBytes bytes")

fun interface HttpPostTransport {
    suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): HttpResponsePayload
}

interface HttpTransport : HttpPostTransport {
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        maxResponseBytes: Int = 256 * 1024,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResponsePayload

    override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): HttpResponsePayload =
        request(method = "POST", url = url, headers = headers, jsonBody = jsonBody)
}

/**
 * 隔离凭据传输实现：
 * - 禁用自动重定向（防止 Authorization / Bearer 头跨站泄漏）
 * - 禁用缓存与 Cookie，无全局拦截器，不自动重试
 * - 限制响应体上限（默认 256 KiB）与明确超时
 */
object IsolatedHttpPostTransport : HttpTransport {
    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        jsonBody: String?,
        maxResponseBytes: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponsePayload = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = jsonBody != null
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            if (jsonBody != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        continuation.invokeOnCancellation {
            cancelled.set(true)
            // 立即断开底层 HttpURLConnection / socket，使阻塞的 IO 读写快速失败并释放连接
            Dispatchers.IO.asExecutor().execute {
                runCatching { connection.disconnect() }
            }
        }
        Dispatchers.IO.asExecutor().execute {
            try {
                if (cancelled.get() || !continuation.isActive) return@execute
                if (jsonBody != null) {
                    val payloadBytes = jsonBody.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(payloadBytes.size)
                    connection.outputStream.use { it.write(payloadBytes) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
                val buffer = ByteArrayOutputStream()
                stream?.use { input ->
                    val chunk = ByteArray(4096)
                    var total = 0
                    while (true) {
                        if (cancelled.get() || !continuation.isActive) {
                            throw CancellationException("HTTP request cancelled")
                        }
                        val read = input.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > maxResponseBytes) {
                            throw HttpResponseTooLargeException(maxResponseBytes)
                        }
                        buffer.write(chunk, 0, read)
                    }
                }
                if (cancelled.get() || !continuation.isActive) return@execute
                continuation.resume(
                    HttpResponsePayload(
                        statusCode = status,
                        bodyText = buffer.toString(Charsets.UTF_8.name()),
                    ),
                )
            } catch (e: Throwable) {
                if (continuation.isActive && !cancelled.get()) {
                    continuation.resumeWithException(e)
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }
}

sealed class FeedbackSendException(message: String) : Exception(message) {
    class NotConfigured : FeedbackSendException("当前构建设定未配置反馈接口地址，可使用支持中心或邮件草稿反馈。")
    class Rejected(val code: String) : FeedbackSendException("反馈未能提交（错误码：$code），请稍后重试或改用邮件。")
}

data class FeedbackReceipt(
    val id: String,
    val ticketUrl: String?,
)

enum class RecipeIssueKind(val rawValue: String, val label: String) {
    VIDEO("video", "视频打不开"),
    TEXT("text", "图文链接打不开"),
    STUFF("stuff", "食材或厨具不对"),
    INFO("info", "难度或做法不对"),
    OTHER("other", "其他问题"),
    ;

    companion object {
        fun availableFor(recipe: Recipe): List<RecipeIssueKind> = entries.filter { issue ->
            when (issue) {
                VIDEO -> !recipe.bv.isNullOrEmpty()
                TEXT -> !recipe.link.isNullOrEmpty()
                else -> true
            }
        }
    }
}

enum class FeedbackLinkIssue(val rawValue: String, val label: String) {
    UNSPECIFIED("", "暂不确定"),
    UNAVAILABLE("unavailable", "内容不存在或已下架"),
    LOADING("loading", "一直加载或网络错误"),
    LOGIN("login", "要求登录或没有访问权限"),
    WRONG_CONTENT("wrong-content", "打开的不是这道菜"),
    OTHER("other", "其他情况"),
}

enum class AppFeedbackCategory(val rawValue: String, val label: String, val placeholder: String) {
    BUG("bug", "用着有问题", "在哪个页面、做了什么、看到了什么"),
    IDEA("idea", "功能建议", "想要什么功能，用在什么时候"),
    RECIPE("recipe", "想要的菜没有", "想做哪道菜，有哪些食材或做法偏好"),
    OTHER("other", "其他", "说说具体情况"),
}

class FeedbackClient(
    rawEndpoint: String = BuildConfig.COOK_FEEDBACK_URL,
    private val version: String = BuildConfig.VERSION_NAME,
    private val build: String = BuildConfig.VERSION_CODE.toString(),
    val environment: String = if (BuildConfig.DEBUG) "test" else "production",
    private val sendsSignals: Boolean = !BuildConfig.DEBUG,
    private val transport: HttpPostTransport = IsolatedHttpPostTransport,
) {
    val reporterId: String = UUID.randomUUID().toString()
    val endpoint: String? = routedEndpoint(rawEndpoint, environment)
    val isTest: Boolean
        get() = environment == "test"
    val isConfigured: Boolean
        get() = endpoint != null

    private val sentSignalKeys = HashSet<String>()

    suspend fun signalEmpty(
        stuff: List<String>,
        tool: String?,
        mode: RecommendationMode,
        recipeVersion: String,
        consentAllowed: Boolean,
    ) {
        if (!sendsSignals || !consentAllowed || !isConfigured || stuff.isEmpty()) return
        val key = signalKey("empty", stuff, tool.orEmpty(), mode.rawValue, null)
        if (!synchronized(sentSignalKeys) { sentSignalKeys.add(key) }) return
        val payload = JsonObject(
            mapOf(
                "action" to JsonPrimitive("signal"),
                "type" to JsonPrimitive("empty"),
                "stuff" to JsonArray(stuff.map(::JsonPrimitive)),
                "tool" to JsonPrimitive(tool.orEmpty()),
                "mode" to JsonPrimitive(mode.rawValue),
            ),
        )
        runCatching { post(payload, recipeVersion = recipeVersion, entryPoint = "search") }
    }

    suspend fun signalOpen(
        stuff: List<String>,
        tool: String?,
        mode: RecommendationMode,
        recipeId: Int,
        rank: Int,
        recipeVersion: String,
        consentAllowed: Boolean,
    ) {
        if (!sendsSignals || !consentAllowed || !isConfigured || stuff.isEmpty()) return
        val key = signalKey("open", stuff, tool.orEmpty(), mode.rawValue, recipeId)
        if (!synchronized(sentSignalKeys) { sentSignalKeys.add(key) }) return
        val payload = JsonObject(
            mapOf(
                "action" to JsonPrimitive("signal"),
                "type" to JsonPrimitive("open"),
                "stuff" to JsonArray(stuff.map(::JsonPrimitive)),
                "tool" to JsonPrimitive(tool.orEmpty()),
                "mode" to JsonPrimitive(mode.rawValue),
                "recipeId" to JsonPrimitive(recipeId),
                "rank" to JsonPrimitive(rank),
            ),
        )
        runCatching { post(payload, recipeVersion = recipeVersion, entryPoint = "search") }
    }

    suspend fun reportRecipeIssue(
        recipe: Recipe,
        issue: RecipeIssueKind,
        note: String,
        linkIssue: FeedbackLinkIssue = FeedbackLinkIssue.UNSPECIFIED,
        recipeVersion: String,
    ) {
        val cleanNote = note.trim().take(MAX_RECIPE_NOTE)
        val effectiveLinkIssue = if (issue == RecipeIssueKind.VIDEO || issue == RecipeIssueKind.TEXT) {
            linkIssue.rawValue
        } else {
            ""
        }
        val payload = JsonObject(
            mapOf(
                "action" to JsonPrimitive("report"),
                "recipeId" to JsonPrimitive(recipe.id),
                "recipeName" to JsonPrimitive(recipe.name),
                "kind" to JsonPrimitive(issue.rawValue),
                "note" to JsonPrimitive(cleanNote),
                "linkIssue" to JsonPrimitive(effectiveLinkIssue),
                "sourceBv" to JsonPrimitive(recipe.bv.orEmpty()),
                "sourceLink" to JsonPrimitive(recipe.link.orEmpty()),
            ),
        )
        post(payload, recipeVersion = recipeVersion, entryPoint = "recipe-detail")
    }

    suspend fun sendAppFeedback(
        category: AppFeedbackCategory,
        note: String,
        contact: String,
        recipeVersion: String,
        submissionId: String = UUID.randomUUID().toString(),
    ): FeedbackReceipt? {
        val payload = JsonObject(
            mapOf(
                "action" to JsonPrimitive("feedback"),
                "category" to JsonPrimitive(category.rawValue),
                "note" to JsonPrimitive(note.trim().take(MAX_FEEDBACK_NOTE)),
                "contact" to JsonPrimitive(contact.trim().take(MAX_CONTACT)),
                "submissionId" to JsonPrimitive(submissionId),
            ),
        )
        val result = post(payload, recipeVersion = recipeVersion, entryPoint = "app-feedback")
        val receiptObj = result["receipt"] as? JsonObject ?: return null
        val id = receiptObj["id"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
        return FeedbackReceipt(id = id, ticketUrl = parseTicketUrl(receiptObj))
    }

    private suspend fun post(
        fields: JsonObject,
        recipeVersion: String,
        entryPoint: String,
    ): JsonObject {
        val url = endpoint ?: throw FeedbackSendException.NotConfigured()
        val bodyMap = fields.toMutableMap()
        bodyMap["deviceId"] = JsonPrimitive(reporterId)
        bodyMap["platform"] = JsonPrimitive("android")
        bodyMap["version"] = JsonPrimitive(version)
        bodyMap["environment"] = JsonPrimitive(environment)
        bodyMap["context"] = JsonObject(
            mapOf(
                "entryPoint" to JsonPrimitive(entryPoint),
                "origin" to JsonPrimitive(""),
                "path" to JsonPrimitive("/$entryPoint"),
                "build" to JsonPrimitive(build),
                "recipeVersion" to JsonPrimitive(recipeVersion),
            ),
        )
        val response = transport.post(
            url = url,
            headers = emptyMap(),
            jsonBody = JsonObject(bodyMap).toString(),
        )
        val parsed = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }.getOrNull()
        val ok = parsed?.get("ok")?.jsonPrimitive?.booleanOrNull == true
        if (response.statusCode != 200 || !ok) {
            val code = parsed?.get("code")?.jsonPrimitive?.takeIf { it.isString }?.content ?: "http-${response.statusCode}"
            throw FeedbackSendException.Rejected(code)
        }
        return checkNotNull(parsed)
    }

    companion object {
        const val SUPPORT_EMAIL = "kf@yunle.fun"
        const val MAX_RECIPE_NOTE = 200
        const val MAX_FEEDBACK_NOTE = 500
        const val MAX_CONTACT = 100
        private val TICKET_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

        fun routedEndpoint(rawUrl: String, environment: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) return null
            if (environment == "test" && uri.host.equals("api.yunle.fun", ignoreCase = true) &&
                uri.path.trimEnd('/') == "/cook-feedback"
            ) {
                return "https://api.yunle.fun/cook-feedback-test"
            }
            return uri.toString()
        }

        fun parseTicketUrl(receipt: JsonObject): String? {
            val ticketId = receipt["ticketId"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
            if (!TICKET_ID_REGEX.matches(ticketId)) return null
            val value = receipt["ticketUrl"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (
                uri.scheme?.lowercase() == "https" &&
                uri.host == "support.yunle.fun" &&
                uri.port == -1 &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.path == "/tickets/$ticketId"
            ) {
                return uri.toString()
            }
            return null
        }

        fun buildMailToUri(topic: String, lines: List<String>, version: String = BuildConfig.VERSION_NAME): Uri {
            val body = (lines.filter { it.isNotEmpty() } + listOf("", "---", "来自 云食册（Android $version）"))
                .joinToString("\n")
            val subjectEncoded = URLEncoder.encode("【云食册反馈】$topic", Charsets.UTF_8.name()).replace("+", "%20")
            val bodyEncoded = URLEncoder.encode(body, Charsets.UTF_8.name()).replace("+", "%20")
            return Uri.parse("mailto:$SUPPORT_EMAIL?subject=$subjectEncoded&body=$bodyEncoded")
        }

        fun buildMailPreviewText(topic: String, lines: List<String>, version: String = BuildConfig.VERSION_NAME): String {
            val body = (lines.filter { it.isNotEmpty() } + listOf("", "---", "来自 云食册（Android $version）"))
                .joinToString("\n")
            return "收件人：$SUPPORT_EMAIL\n主题：【云食册反馈】$topic\n\n$body"
        }

        private fun signalKey(
            type: String,
            stuff: List<String>,
            tool: String,
            mode: String,
            recipeId: Int?,
        ): String = listOf(
            type,
            stuff.sorted().joinToString("、"),
            tool,
            mode,
            recipeId?.toString().orEmpty(),
        ).joinToString("|")
    }
}

/**
 * 隔离的 DeepSeek BYOK 客户端：
 * - 单次传入 API Key，不保留全局 Interceptor
 * - 所有响应先经由 StepGuidanceValidator / SearchIntentValidator 严格白名单与数字校对
 */
class DeepSeekByokClient(
    private val endpoint: String = DEEPSEEK_CHAT_ENDPOINT,
    private val model: String = DEFAULT_MODEL,
    private val transport: HttpPostTransport = IsolatedHttpPostTransport,
    private val connectionTransport: HttpTransport = IsolatedHttpPostTransport,
) {
    suspend fun checkConnection(apiKey: String): Boolean {
        val key = apiKey.trim()
        require(validKey(key)) { "请输入有效的 DeepSeek API Key。" }
        val response = connectionTransport.request(
            method = "GET",
            url = "https://api.deepseek.com/user/balance",
            headers = mapOf("Authorization" to "Bearer $key"),
            maxResponseBytes = 8192,
        )
        return when (response.statusCode) {
            200 -> runCatching {
                Json.parseToJsonElement(response.bodyText).jsonObject["is_available"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: error("未收到有效的余额状态。")
            401, 403 -> error("DeepSeek 未接受此 Key，请核对后重试。")
            402 -> false
            429 -> error("检查过于频繁，请稍后再试。")
            else -> error("暂时无法连接 DeepSeek，请稍后再试。")
        }
    }

    fun validKey(value: String): Boolean = value.length in 1..512 && value.all { it.code in 33..126 }

    suspend fun requestStepGuidance(
        apiKey: String,
        prompt: StepGuidancePrompt,
    ): StepGuidanceDraft {
        val cleanKey = apiKey.trim()
        if (!validKey(cleanKey)) throw StepGuidanceException.NotConfigured()
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "temperature" to JsonPrimitive(0),
                "max_tokens" to JsonPrimitive(700),
                "response_format" to JsonObject(mapOf("type" to JsonPrimitive("json_object"))),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(prompt.systemPrompt))),
                        JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(prompt.userMessage))),
                    ),
                ),
            ),
        )
        val payload = try {
            transport.post(
                url = endpoint,
                headers = mapOf("Authorization" to "Bearer $cleanKey"),
                jsonBody = body.toString(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw StepGuidanceException.TimedOut()
        } catch (_: UnknownHostException) {
            throw StepGuidanceException.Offline()
        } catch (_: Exception) {
            throw StepGuidanceException.Unavailable()
        }

        when (payload.statusCode) {
            in 200..299 -> Unit
            401, 403 -> throw StepGuidanceException.Authentication()
            402 -> throw StepGuidanceException.InsufficientBalance()
            429 -> throw StepGuidanceException.RateLimited()
            else -> throw StepGuidanceException.Unavailable()
        }

        val content = extractAssistantContent(payload.bodyText)
            ?: throw StepGuidanceException.InvalidResponse()
        return StepGuidanceValidator.decodeDraft(content, prompt)
    }

    suspend fun requestSearchIntent(
        apiKey: String,
        naturalQuery: String,
        data: CookData,
        availableGoals: List<DietaryGoal>,
    ): SearchIntent {
        val cleanKey = apiKey.trim()
        if (!validKey(cleanKey)) {
            throw SearchIntentException.InvalidResponse()
        }
        val validator = SearchIntentValidator(data, availableGoals)
        val vocab = validator.vocabulary
        val sysPrompt = """
            You parse a Chinese cooking search sentence into a single JSON object.
            Return ONLY JSON with keys: schemaVersion (1), dataVersion ("${data.meta.dataVersion}"), policyVersion (1), source ("deepseek"), query ({stuff, unknown:[], avoid, tool, noSpicy, difficulty, methodGroups}), goal (null or ${availableGoals.joinToString("/") { it.rawValue }}), unresolved ([]).
            Allowed stuff: ${vocab.stuff.take(120).joinToString(", ")}
            Allowed tools: ${vocab.tools.joinToString(", ")}
            Allowed difficulty: ${vocab.difficulty.joinToString(", ")}
            Allowed methodGroups: ${vocab.methodGroups.joinToString(", ")}
            Put unrecognized desires, diet goals or time requests in unresolved.
        """.trimIndent()
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "temperature" to JsonPrimitive(0),
                "max_tokens" to JsonPrimitive(600),
                "response_format" to JsonObject(mapOf("type" to JsonPrimitive("json_object"))),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(sysPrompt))),
                        JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(naturalQuery.take(500)))),
                    ),
                ),
            ),
        )
        val payload = transport.post(
            url = endpoint,
            headers = mapOf("Authorization" to "Bearer $cleanKey"),
            jsonBody = body.toString(),
        )
        if (payload.statusCode !in 200..299) {
            throw SearchIntentException.InvalidResponse()
        }
        val content = extractAssistantContent(payload.bodyText)
            ?: throw SearchIntentException.InvalidResponse()
        val decoded = validator.decode(content, expectedSource = SearchIntentSource.DEEPSEEK)
        return CookAIIntentValidation.preserveLocalConditions(
            remote = decoded,
            text = naturalQuery,
            data = data,
            availableGoals = availableGoals,
        )
    }

    private fun extractAssistantContent(rawBody: String): String? = runCatching {
        val root = Json.parseToJsonElement(rawBody).jsonObject
        root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        private const val DEEPSEEK_CHAT_ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val DEFAULT_MODEL = "deepseek-chat"
    }
}
