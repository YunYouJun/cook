package cn.yunyoujun.cook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgeProduceClassifier
import cn.yunyoujun.cook.data.local.FridgeProduceModel
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.remote.FridgeVisionException
import cn.yunyoujun.cook.data.remote.FridgeVisionProvider
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.platform.RecipeShareLinks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeParityTest {
    @Test
    fun produceModelMetadataShipsWithoutWeightsAndLabelsMapToIngredients() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val labels = assets.open("cook/produce/labels.txt").bufferedReader().use { it.readLines() }
        val produce = labels.filterNot { it.startsWith("other_") }
        assertEquals(22, produce.size)
        assertEquals(produce.size, produce.toSet().size)
        val names = produce.map { name -> FridgeLabelParser.imageNames(listOf(name to 1f)).single() }
        assertEquals(22, names.toSet().size)
        val manifest = assets.open("cook/produce/model.json").bufferedReader().use { it.readText() }
        assertTrue(Json.decodeFromString<FridgeProduceModel>(manifest).file.endsWith(".onnx"))
        assertFalse(assets.list("cook/produce").orEmpty().any { it.endsWith(".onnx") })

        val candidates = listOf("apple", "banana", "other_0")
        val values = floatArrayOf(.32f, .31f, .3f)
        val accepted = FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(.87f, .08f, .05f))
        assertEquals(listOf("苹果"), FridgeLabelParser.imageNames(accepted))
        assertTrue(FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(.39f, .16f, .45f)).isEmpty())
        assertTrue(FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(Float.NaN, .3f, .4f)).isEmpty())
    }

    @Test
    fun packagedSceneContainsTheKitchenTargetsAndAllShowcaseModels() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        fun modelNodes(path: String): Set<String> {
            val bytes = assets.open(path).use { it.readBytes() }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, header.int) // glTF
            assertEquals(2, header.int)
            assertEquals(bytes.size, header.int)
            val jsonSize = header.int
            assertEquals(0x4E4F534A, header.int) // JSON
            val data = Json.parseToJsonElement(String(bytes, 20, jsonSize, Charsets.UTF_8)).jsonObject
            return data.getValue("nodes").jsonArray.mapNotNull { node ->
                node.jsonObject["name"]?.jsonPrimitive?.content
            }.toSet()
        }
        val manifest = assets.open("cook/scenes/kitchen.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        val required = manifest.jsonObject.getValue("ingredients").jsonArray
            .map { it.jsonObject.getValue("node").jsonPrimitive.content }.toSet() + "CookPot"
        assertTrue(modelNodes("cook/scenes/cloud-kitchen.glb").containsAll(required))
        assertTrue(modelNodes("cook/scenes/showcase-plate.glb").isNotEmpty())
        val showcaseFiles = assets.list("cook/showcase").orEmpty()
        val images = showcaseFiles.filter { it.endsWith(".webp") }
        assertTrue(images.isNotEmpty())
        images.forEach { image ->
            assertTrue(modelNodes("cook/showcase/${image.removeSuffix(".webp")}.glb").isNotEmpty())
        }
    }

    @Test
    fun shareLinksMatchIosRouteAndRejectAmbiguousOrNoncanonicalIds() {
        assertEquals(250L, RecipeShareLinks.recipeId(RecipeShareLinks.url(250)))
        assertEquals(0L, RecipeShareLinks.recipeId("https://cook.yunle.fun:443/recipes/share?%69d=%30"))
        assertEquals(9_007_199_254_740_991L, RecipeShareLinks.recipeId(RecipeShareLinks.url(0).replace("id=0", "id=9007199254740991")))
        listOf(
            "https://cook.yunle.fun/recipes/share/?id=250&id=250",
            "https://cook.yunle.fun/recipes/share/?id=+250",
            "https://cook.yunle.fun/recipes/share/?id=0250",
            "https://cook.yunle.fun/recipes/share/?id=9007199254740992",
            "https://cook.yunle.fun/recipes%2Fshare?id=250",
            "https://someone@cook.yunle.fun/recipes/share/?id=250",
            "https://cook.yunle.fun:444/recipes/share/?id=250",
            "https://cook.yunyoujun.cn/recipes/share/?id=250",
            "http://cook.yunle.fun/recipes/share/?id=250",
        ).forEach { assertNull(it, RecipeShareLinks.recipeId(it)) }
    }

    @Test
    fun explicitLearningAndUndoPreserveOtherCooksAndPersistAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CookingJournalStore(context)
        val catalog = JournalRecipeId.Catalog(250)
        val personal = JournalRecipeId.Personal("custom-tomato-egg")
        val now = System.currentTimeMillis()
        store.recordCooked(catalog, "番茄炒蛋", now)
        store.recordCooked(catalog, "番茄炒蛋", now)
        val catalogConfirmation = requireNotNull(store.confirmLearned(catalog, "番茄炒蛋", now))
        assertNull(catalogConfirmation.autoCookedId)
        assertNull(store.confirmLearned(catalog, "番茄炒蛋", now))
        val personalConfirmation = requireNotNull(store.confirmLearned(personal, "番茄炒蛋", now))
        assertEquals(2, store.state.value.cooked.size)
        store.undoConfirmation(personalConfirmation)
        store.undoConfirmation(catalogConfirmation)
        assertEquals(listOf(catalog), store.state.value.cooked.map { it.recipeId })
        val replacement = requireNotNull(store.confirmLearned(personal, "番茄炒蛋", now))
        store.undoConfirmation(personalConfirmation)
        store.removeCooked(requireNotNull(replacement.autoCookedId))
        val reopened = CookingJournalStore(context).state.value
        assertEquals(listOf(replacement.record), reopened.learned)
        assertTrue(reopened.entries(learnedOnly = false).any { it.learned && it.cookedId == null })
    }

    @Test
    fun usedFridgeBatchIsExcludedFromRemindersAndRestoresWithoutChangingItsDates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FridgeStore(context)
        val today = LocalDate.now()
        val item = FridgeBatchItem(
            ingredientName = "番茄",
            categoryId = "veg",
            expiresOnIsoDate = today.toString(),
            dateKind = FridgeDateKind.PACKAGE_EXPIRY,
        )
        store.addItems(listOf(item))
        assertEquals(1, store.dueSoonCount(today))
        store.markConsumed(item.id, nowMs = 20)
        store.markConsumed(item.id, nowMs = 30)
        val restoredStore = FridgeStore(context)
        assertTrue(restoredStore.state.value.stock.isEmpty())
        assertEquals(0, restoredStore.dueSoonCount(today))
        assertEquals(listOf(item.copy(consumedAtMs = 20)), restoredStore.state.value.consumed)
        restoredStore.restoreItem(item.id)
        assertEquals(listOf(item), restoredStore.state.value.stock)
        assertEquals(1, restoredStore.dueSoonCount(today))
    }

    @Test
    fun visionEligibilityOmitsPhotoAndUploadBindsConsentToExactProvider() = runBlocking {
        val transport = VisionTransport()
        val service = FridgeVisionService(transport)
        val credential = YunlefunCredential("fixture-token", "refresh", "fixture-member", Long.MAX_VALUE, "device")
        val provider = service.memberProvider(credential)
        assertEquals(FridgeVisionProvider.CLOUDBASE, provider)
        assertEquals("GET", transport.method)
        assertNull(transport.body)
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        assertEquals(listOf("鸡蛋", "番茄"), service.recognize(jpeg, provider, credential))
        assertEquals("POST", transport.method)
        val body = Json.parseToJsonElement(requireNotNull(transport.body)).jsonObject
        assertEquals(setOf("schemaVersion", "consentVersion", "provider", "image"), body.keys)
        assertEquals("cloudbase", body.getValue("provider").jsonPrimitive.content)
        assertEquals("1", body.getValue("consentVersion").jsonPrimitive.content)
        assertFalse(requireNotNull(transport.body).contains("fixture-token"))

        transport.providerChanged = true
        val failure = runCatching { service.recognize(jpeg, provider, credential) }.exceptionOrNull()
        assertTrue(failure is FridgeVisionException.ProviderChanged)
    }

    private class VisionTransport : HttpTransport {
        var method: String? = null
        var body: String? = null
        var providerChanged = false

        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            jsonBody: String?,
            maxResponseBytes: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponsePayload {
            assertEquals("https://api.yunle.fun/cook-fridge-vision", url)
            assertEquals("Bearer fixture-token", headers["Authorization"])
            assertEquals(8192, maxResponseBytes)
            this.method = method
            body = jsonBody
            return when {
                method == "GET" -> HttpResponsePayload(200, """{"ok":true,"schemaVersion":1,"member":true,"provider":"cloudbase"}""")
                providerChanged -> HttpResponsePayload(409, """{"ok":false,"code":"PROVIDER_CHANGED"}""")
                else -> HttpResponsePayload(200, """{"ok":true,"schemaVersion":1,"ingredients":["鸡蛋","番茄"]}""")
            }
        }
    }
}
