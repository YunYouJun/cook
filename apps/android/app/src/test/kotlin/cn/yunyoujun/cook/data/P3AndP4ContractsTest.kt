package cn.yunyoujun.cook.data

import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.core.search.CookQuery
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateVideoException
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.remote.CookAIIntentValidation
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.ManagedCookAIService
import cn.yunyoujun.cook.data.remote.ManagedStepGuidanceService
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserDataSyncService
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

class P3AndP4ContractsTest {
    private val dataDir = File("../../../packages/data").canonicalFile
    private val cookData: CookData by lazy { CookData.loadFromDirectory(dataDir) }
    private val textLibrary: RecipeTextLibrary by lazy {
        RecipeTextLibrary.loadFromDirectory(dataDir)!!
    }

    @Test
    fun fridgeLabelParser_andProduceStorageAdvice_enforceSafetyRules() {
        val today = LocalDate.of(2026, 11, 15)

        // 1. 「日本豆腐」不再同时建议「豆腐」
        val matched = FridgeLabelParser.matchKnownNames(
            text = "配料：水、大豆、日本豆腐专用凝固剂",
            knownNames = listOf("豆腐", "日本豆腐", "鸡蛋"),
        )
        assertEquals(listOf("日本豆腐"), matched)

        // 2. 图像分类只接受具体类别且置信度 >= 0.35，忽略泛类 fruit/vegetable
        val classified = FridgeLabelParser.imageNames(
            listOf(
                "fruit" to 0.99f,
                "vegetable" to 0.95f,
                "broccoli" to 0.82f,
                "tomato" to 0.40f,
                "apple" to 0.20f,
            ),
        )
        assertEquals(listOf("西兰花", "番茄"), classified)

        // 3. 生产日期 + 保质期自动推算
        val calcScan = FridgeLabelParser.parse(
            text = "鲜牛奶 生产日期：2026-11-10 保质期：7天",
            names = listOf("牛奶"),
            today = today,
        )
        assertEquals(LocalDate.of(2026, 11, 10), calcScan.productionDay)
        assertEquals(LocalDate.of(2026, 11, 17), calcScan.expiryDay)
        assertEquals(FridgeExpiryBasis.CALCULATED, calcScan.basis)

        // 4. 冲突到期日或早于生产日期绝不自动采用
        val conflictScan = FridgeLabelParser.parse(
            text = "生产日期 2026-11-12 保质期至 2026-11-10",
            today = today,
        )
        assertNull(conflictScan.expiryDay)
        assertTrue(conflictScan.messages.any { it.contains("到期日早于生产日期") })

        val multiExpiryScan = FridgeLabelParser.parse(
            text = "保质期至 2026-11-20 EXP 2026-11-25",
            today = today,
        )
        assertNull(multiExpiryScan.expiryDay)
        assertEquals(2, multiExpiryScan.dateCandidates.size)
        assertTrue(multiExpiryScan.messages.any { it.contains("识别到多个到期日") })

        // 5. 完整与切开蔬果建议天数（切开绝不延长蓝莓原本 2 天建议）
        val blueberryWhole = ProduceStorageAdvice.recommendation("蓝莓", cut = false)
        val blueberryCut = ProduceStorageAdvice.recommendation("蓝莓", cut = true)
        assertEquals(2, blueberryWhole?.days)
        assertEquals(2, blueberryCut?.days)

        val appleWhole = ProduceStorageAdvice.recommendation("苹果", cut = false)
        val appleCut = ProduceStorageAdvice.recommendation("苹果", cut = true)
        assertEquals(28, appleWhole?.days)
        assertEquals(3, appleCut?.days)
    }

    @Test
    fun privateRecipeVideoStore_stagesInstallsAndEnforcesDeviceAndCloudBoundaries() = runBlocking {
        val tempRoot = Files.createTempDirectory("cook-video-store-test").toFile()
        try {
            val deviceId = UUID.randomUUID().toString().lowercase()
            val store = PrivateRecipeVideoStore(
                context = null,
                localRootDir = File(tempRoot, "videos"),
                explicitDeviceId = deviceId,
            )

            val sourceMp4 = File(tempRoot, "demo.mp4").apply {
                writeBytes(ByteArray(2048) { (it % 127).toByte() })
            }
            val draft = store.stageFromFile(sourceMp4)
            assertTrue(draft.file.isFile)
            assertEquals("mp4", draft.video.fileExtension)

            val installed = store.install(draft)
            assertFalse(draft.file.exists())
            val playable = store.playableFile(installed)
            assertTrue(playable.isFile)
            assertEquals(2048L, playable.length())

            // iCloud 引用在 Android 端明确拒绝拉取
            val icloudVideo = PrivateRecipeVideo(
                id = UUID.randomUUID().toString().lowercase(),
                fileExtension = "mp4",
                originalName = "cloud.mp4",
                byteCount = 1024L,
                location = PrivateRecipeVideo.Location.ICLOUD,
                deviceID = null,
            )
            val icloudErr = runCatching { store.playableFile(icloudVideo) }.exceptionOrNull()
            assertTrue(icloudErr is PrivateVideoException.Unavailable)

            // 其他设备的 local 引用明确报 OtherDevice
            val foreignVideo = installed.copy(deviceID = UUID.randomUUID().toString().lowercase())
            val foreignErr = runCatching { store.playableFile(foreignVideo) }.exceptionOrNull()
            assertTrue(foreignErr is PrivateVideoException.OtherDevice)

            store.remove(installed)
            assertFalse(playable.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun yunlefunSso_andAccountAndSyncServices_enforceProtocolContracts() = runBlocking {
        val nowMs = 1_760_000_000_000L
        val sso = YunlefunSSO(createdAtMs = nowMs, nonce = "nonce-fixed-123", verifier = "verifier-fixed-456")
        assertTrue(sso.authorizationUrl.startsWith("https://www.yunle.fun/auth/sso?"))
        assertTrue(sso.authorizationUrl.contains("code_challenge_method=S256"))

        val validCode = "A".repeat(43)
        val payloadJson = JsonObject(
            mapOf(
                "nonce" to JsonPrimitive("nonce-fixed-123"),
                "iss" to JsonPrimitive(YunlefunSSO.ISSUER),
                "ok" to JsonPrimitive(true),
                "code" to JsonPrimitive(validCode),
            ),
        ).toString()
        val fragment = YunlefunSSO.encodeBase64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val callbackUrl = "${YunlefunSSO.REDIRECT_URI}#ylf_sso=$fragment"

        val fields = sso.exchangeFields(callbackUrl, nowMs = nowMs + 5_000L)
        assertEquals("exchangeSsoCode", fields["action"])
        assertEquals(validCode, fields["code"])
        assertEquals("verifier-fixed-456", fields["codeVerifier"])

        // 超过 10 分钟有效期立即拒绝
        val expiredErr = runCatching { sso.exchangeFields(callbackUrl, nowMs = nowMs + 600_001L) }.exceptionOrNull()
        assertTrue(expiredErr is YunlefunAccountException.InvalidCallback)

        // UserDataSyncService v2 + ownerDigest 校验
        val cred = YunlefunCredential(
            accessToken = "token_abc123",
            refreshToken = "refresh_xyz789",
            subject = "user-uid-42",
            expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
            deviceID = UUID.randomUUID().toString().lowercase(),
        )
        var capturedSyncBody: JsonObject? = null
        val syncService = UserDataSyncService(
            transport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload {
                    capturedSyncBody = Json.parseToJsonElement(jsonBody!!).jsonObject
                    return HttpResponsePayload(
                        statusCode = 200,
                        bodyText = """
                            {
                              "ok": true,
                              "protocolVersion": 2,
                              "document": {
                                "version": 1,
                                "favorites": [{"id": 1, "time": 1700000000000}],
                                "favoriteRemovals": [],
                                "histories": [],
                                "historyRemovals": [],
                                "historyClearedAt": 0
                              }
                            }
                        """.trimIndent(),
                    )
                }
            },
        )
        val syncedDoc = syncService.sync(UserDataDocument(), cred)
        assertEquals(1, syncedDoc.favorites.size)
        assertEquals(
            UserDataSyncService.sha256Hex("user-uid-42"),
            capturedSyncBody?.get("ownerDigest")?.jsonPrimitive?.content,
        )

        // MembershipService 权益解析与确定性 AccountToken
        val membershipService = MembershipService(
            transport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload = HttpResponsePayload(
                    statusCode = 200,
                    bodyText = """{"membership":{"isActive":true,"expireAt":1893456000000}}""",
                )
            },
        )
        val entitlement = membershipService.queryEntitlement(cred)
        assertTrue(entitlement.isActive)
        assertEquals(1893456000000L, entitlement.expiresAtEpochMs)
    }

    @Test
    fun cookAiIntentValidation_andManagedServices_preserveLocalAndRejectMedicalClaims() = runBlocking {
        val goals = DietaryGoal.entries
        val remoteIntent = SearchIntent(
            schemaVersion = 1,
            dataVersion = cookData.meta.dataVersion,
            policyVersion = 1,
            source = SearchIntentSource.DEEPSEEK,
            query = CookQuery(
                stuff = listOf("鸡蛋"),
                unknown = emptyList(),
                avoid = emptyList(),
                tool = null,
                noSpicy = false,
                difficulty = emptyList(),
                methodGroups = emptyList(),
            ),
            goal = null,
            unresolved = emptyList(),
        )
        val preserved = CookAIIntentValidation.preserveLocalConditions(
            remote = remoteIntent,
            text = "家里有番茄和鸡蛋，不要洋葱，不吃辣",
            data = cookData,
            availableGoals = goals,
        )
        assertTrue("番茄" in preserved.query.stuff && "鸡蛋" in preserved.query.stuff)
        assertTrue("洋葱" in preserved.query.avoid)
        assertTrue(preserved.query.noSpicy)

        // 模型把「糖尿病/减肥」擅自映射为内置饮食目标时必须拒绝
        val medicalMapped = remoteIntent.copy(goal = DietaryGoal.LESS_ADDED_SUGAR)
        val conflictErr = runCatching {
            CookAIIntentValidation.preserveLocalConditions(
                remote = medicalMapped,
                text = "适合糖尿病控糖吃的鸡蛋菜",
                data = cookData,
                availableGoals = goals,
            )
        }.exceptionOrNull()
        assertNotNull(conflictErr)

        // ManagedStepGuidanceService 校验合法响应
        val cred = YunlefunCredential(
            accessToken = "token_abc123",
            refreshToken = "refresh_xyz789",
            subject = "user-uid-42",
            expiresAtEpochMs = System.currentTimeMillis() + 3600_000L,
            deviceID = UUID.randomUUID().toString().lowercase(),
        )
        val sampleRecipe = cookData.recipes.first { textLibrary.text(it.id) != null }
        val sampleText = textLibrary.text(sampleRecipe.id)!!
        val sampleStep = RecipePresentation(sampleText).steps.first()
        val prompt = StepGuidancePrompt(
            recipe = sampleRecipe,
            text = sampleText,
            dataVersion = cookData.meta.dataVersion,
            step = sampleStep,
            servings = 1,
            kind = StepGuidanceKind.EXPLAIN,
            question = null,
            requestID = "req-001",
            contextRevision = "rev-001",
        )
        val managedGuidance = ManagedStepGuidanceService(
            transport = object : HttpTransport {
                override suspend fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    jsonBody: String?,
                    maxResponseBytes: Int,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                ): HttpResponsePayload = HttpResponsePayload(
                    statusCode = 200,
                    bodyText = """
                        {
                          "ok": true,
                          "guidance": {
                            "schemaVersion": 1,
                            "policyVersion": 1,
                            "dataVersion": "${prompt.request.dataVersion}",
                            "sourceFingerprint": "${prompt.request.sourceFingerprint}",
                            "recipeID": ${prompt.request.recipeID},
                            "stepID": ${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(prompt.request.stepID))},
                            "requestID": "req-001",
                            "contextRevision": "rev-001",
                            "status": "answer",
                            "answer": "请按当前步骤原文操作，注意火候。",
                            "sourceIDs": [${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive("step:${prompt.request.stepID}"))}]
                          }
                        }
                    """.trimIndent(),
                )
            },
        )
        val validatedResp = managedGuidance.complete(prompt, cred)
        assertEquals("req-001", validatedResp.requestID)
    }
}
