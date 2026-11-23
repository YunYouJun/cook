package cn.yunyoujun.cook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.ShelfLife
import cn.yunyoujun.cook.core.model.StuffItem
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeCategory
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgePhotoFocus
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.data.local.FridgeProduceClassifier
import cn.yunyoujun.cook.data.local.FridgeProduceMatch
import cn.yunyoujun.cook.data.local.FridgeProduceModel
import cn.yunyoujun.cook.data.local.FridgeStorageStart
import cn.yunyoujun.cook.data.local.FridgeStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.ShowcaseAssetLibrary
import cn.yunyoujun.cook.data.local.ShowcaseKind
import cn.yunyoujun.cook.data.remote.FridgeVisionException
import cn.yunyoujun.cook.data.remote.FridgeVisionProvider
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.platform.RecipeShareLinks
import cn.yunyoujun.cook.platform.CookSceneTarget
import cn.yunyoujun.cook.platform.nearestSceneTarget
import cn.yunyoujun.cook.ui.state.FridgePhotoDates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeParityTest {
    @Test
    fun overlappingKitchenTargetsPreferTheNearestIngredientAndLeaveDistantTapsToMeshPicking() {
        val egg = CookSceneTarget(0, "鸡蛋", 60f, 65f)
        val tomato = CookSceneTarget(1, "番茄", 83f, 68f)
        val targets = listOf(egg, tomato)
        assertEquals(egg, nearestSceneTarget(targets, 69f, 66f, 24f))
        assertEquals(tomato, nearestSceneTarget(targets, 73f, 66f, 24f))
        assertNull(nearestSceneTarget(targets, 30f, 66f, 24f))
        assertNull(nearestSceneTarget(emptyList(), 60f, 65f, 24f))
    }

    @Test
    fun produceModelUsesBundledLabelsAndOmitsUnknownResults() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val labels = assets.open("cook/produce/labels.txt").bufferedReader().use { it.readLines() }
        val produce = labels.filterNot { it.startsWith("other_") }
        assertEquals(22, produce.size)
        assertEquals(produce.size, produce.toSet().size)
        val names = produce.map { name -> FridgeLabelParser.imageNames(listOf(name to 1f)).single() }
        assertEquals(22, names.toSet().size)
        val model = assets.open("cook/produce/model.json").bufferedReader().use {
            Json.decodeFromString<FridgeProduceModel>(it.readText())
        }
        assertEquals("classifier-${model.sha256}.onnx", model.file)
        assertEquals(BuildConfig.COOK_BUNDLED_PRODUCE_MODEL, assets.list("cook/produce").orEmpty().contains("classifier.onnx"))

        val candidates = listOf("apple", "banana", "other_0")
        val values = floatArrayOf(.32f, .31f, .3f)
        val accepted = FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(.87f, .08f, .05f))
        assertEquals(listOf("苹果"), FridgeLabelParser.imageNames(accepted))
        assertTrue(FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(.39f, .16f, .45f)).isEmpty())
        assertTrue(FridgeProduceClassifier.acceptedProduce(candidates, values, floatArrayOf(Float.NaN, .3f, .4f)).isEmpty())
    }

    @Test
    fun quarterTurnsConfirmOnlyAnOriginalCandidateRecognizedInBothDirections() {
        val labels = listOf("banana", "eggplant", "other_2", "other_10")
        val original = floatArrayOf(.18f, .02f, .78f, .02f)
        val banana = { listOf("banana" to .64f) }
        val reverseBanana = { listOf("banana" to .51f) }
        val eggplant = { listOf("eggplant" to .77f) }

        assertEquals(
            reverseBanana(),
            FridgeProduceClassifier.confirmTurnedProduce(labels, original, banana, reverseBanana),
        )
        assertTrue(FridgeProduceClassifier.confirmTurnedProduce(labels, original, banana, eggplant).isEmpty())
        assertTrue(FridgeProduceClassifier.confirmTurnedProduce(labels, original, banana, { emptyList() }).isEmpty())
        assertTrue(FridgeProduceClassifier.confirmTurnedProduce(labels, original, eggplant, {
            error("A label outside the original leading candidates must not trigger another pass")
        }).isEmpty())
        assertTrue(FridgeProduceClassifier.confirmTurnedProduce(
            labels, floatArrayOf(.12f, .06f, .57f, .25f),
            { error("An unknown subject must not trigger either extra pass") }, banana,
        ).isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wholeProduceFramePreservesBothEndsAndRotatesWithThePhoto() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val classifier = FridgeProduceClassifier(assets)
        val width = 300
        val height = 100
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(width * height) { index ->
                when (index % width) {
                    in 0 until 40 -> Color.RED
                    in 260 until width -> Color.BLUE
                    else -> Color.BLACK
                }
            }
            photo.setPixels(colors, 0, width, 0, 0, width, height)
            val side = 224
            val blueChannel = 2 * side * side

            val crop = classifier.imageTensor(photo, rotationDegrees = 0)
            assertTrue(crop.get(112 * side + 10) < 0f)
            assertTrue(crop.get(blueChannel + 112 * side + 214) < 0f)

            val whole = classifier.imageTensor(photo, rotationDegrees = 0, fitWholeImage = true)
            assertTrue(whole.get(112 * side + 10) > 1f)
            assertTrue(whole.get(blueChannel + 112 * side + 214) > 1f)
            assertTrue(whole.get(112) > 1f)

            val rotated = classifier.imageTensor(photo, rotationDegrees = 90, fitWholeImage = true)
            assertTrue(rotated.get(10 * side + 112) > 1f)
            assertTrue(rotated.get(blueChannel + 214 * side + 112) > 1f)

            val firstRegion = Rect(0, 0, width / 2, height)
            val first = classifier.imageTensor(photo, rotationDegrees = 0, fitWholeImage = true, region = firstRegion)
            assertTrue(first.get(112 * side + 10) > 1f)
            assertTrue(first.get(blueChannel + 112 * side + 214) < 0f)

            val secondRegion = Rect(width / 2, 0, width, height)
            val second = classifier.imageTensor(photo, rotationDegrees = 90, fitWholeImage = true, region = secondRegion)
            assertTrue(second.get(blueChannel + 214 * side + 112) > 1f)
            assertTrue(second.get(10 * side + 112) < 0f)
        } finally {
            photo.recycle()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun focusedRecognitionKeepsSmallRegionsAtTheirOriginalResolution(): Unit = runBlocking {
        val width = 4800
        val height = 1200
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            photo.eraseColor(Color.WHITE)
            ByteArrayOutputStream().use { output ->
                assertTrue(photo.compress(Bitmap.CompressFormat.JPEG, 96, output))
                output.toByteArray()
            }
        } finally {
            photo.recycle()
        }

        val full = FridgePhotoInspector.validateAndDecodeInMemory(bytes)
        assertEquals(2400 to 600, full.width to full.height)
        val preview = FridgePhotoInspector.preview(bytes, captureRotation = 90)
        try {
            assertEquals(150 to 600, preview.width to preview.height)
        } finally {
            preview.recycle()
        }
        val focus = FridgePhotoFocus(sizeFraction = .2f, horizontalPosition = .9f, verticalPosition = .75f)
        FridgePhotoInspector.classifyInMemoryBytes(bytes, captureRotation = 90, focus = focus) { region, degrees ->
            assertEquals(240 to 240, region.width to region.height)
            assertEquals(0, degrees)
            FridgeProduceMatch()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun galleryPhotoFocusMatchesPreviewAndVisionUploadInAllExifDirections() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File.createTempFile("fridge-exif", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888)
        val (red, green, blue, yellow) = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val colors = listOf(red, green, blue, yellow)
        val cases = listOf(
            Triple(ExifInterface.ORIENTATION_NORMAL, 0, colors),
            Triple(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 0, listOf(green, red, yellow, blue)),
            Triple(ExifInterface.ORIENTATION_ROTATE_180, 180, listOf(yellow, blue, green, red)),
            Triple(ExifInterface.ORIENTATION_FLIP_VERTICAL, 180, listOf(blue, yellow, red, green)),
            Triple(ExifInterface.ORIENTATION_TRANSPOSE, 270, listOf(red, blue, green, yellow)),
            Triple(ExifInterface.ORIENTATION_ROTATE_90, 90, listOf(blue, red, yellow, green)),
            Triple(ExifInterface.ORIENTATION_TRANSVERSE, 90, listOf(yellow, green, blue, red)),
            Triple(ExifInterface.ORIENTATION_ROTATE_270, 270, listOf(green, yellow, red, blue)),
        )
        try {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height) { index ->
                colors[(index / width / (height / 2)) * 2 + (index % width / (width / 2))]
            }
            source.setPixels(pixels, 0, width, 0, 0, width, height)
            val service = FridgeVisionService()
            fun dominantColor(pixel: Int) = Color.rgb(Color.red(pixel) and 128, Color.green(pixel) and 128, Color.blue(pixel) and 128)
            for ((orientation, rotation, expected) in cases) {
                fixture.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                ExifInterface(fixture.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val bytes = fixture.readBytes()
                assertEquals("EXIF $orientation", rotation, FridgePhotoInspector.validateAndDecodeInMemory(bytes).rotationDegrees)
                val swapped = rotation == 90 || rotation == 270
                val dimensions = if (swapped) height to width else width to height
                val expectedColors = expected.map(::dominantColor)
                val preview = FridgePhotoInspector.preview(bytes, captureRotation = null)
                try {
                    assertEquals(dimensions, preview.width to preview.height)
                    assertEquals(expectedColors.first(), dominantColor(preview.getPixel(6, 6)))
                } finally {
                    preview.recycle()
                }
                val focus = FridgePhotoFocus(sizeFraction = .25f, horizontalPosition = 0f, verticalPosition = 0f)
                FridgePhotoInspector.classifyInMemoryBytes(bytes, captureRotation = null, focus = focus) { crop, degrees ->
                    assertEquals("EXIF $orientation crop", 12 to 12, crop.width to crop.height)
                    assertEquals(0, degrees)
                    assertEquals(expectedColors.first(), dominantColor(crop.getPixel(6, 6)))
                    FridgeProduceMatch()
                }
                val jpeg = service.prepare(bytes)
                val output = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                try {
                    assertEquals(dimensions, output.width to output.height)
                    val actual = listOf(1, 3).flatMap { y ->
                        listOf(1, 3).map { x ->
                            dominantColor(output.getPixel(output.width * x / 4, output.height * y / 4))
                        }
                    }
                    assertEquals("EXIF $orientation", expectedColors, actual)
                } finally {
                    output.recycle()
                }
            }
        } finally {
            source.recycle()
            fixture.delete()
        }
    }

    @Test
    fun packagedSceneContainsTheKitchenTargetsAndShowcasePacksUseTheSharedManifest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = context.assets
        fun modelNodes(bytes: ByteArray): List<JsonObject> {
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, header.int) // glTF
            assertEquals(2, header.int)
            assertEquals(bytes.size, header.int)
            val jsonSize = header.int
            assertEquals(0x4E4F534A, header.int) // JSON
            val data = Json.parseToJsonElement(String(bytes, 20, jsonSize, Charsets.UTF_8)).jsonObject
            return data.getValue("nodes").jsonArray.map { it.jsonObject }
        }
        val manifest = assets.open("cook/scenes/kitchen.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        val kitchen = modelNodes(assets.open("cook/scenes/cloud-kitchen.glb").use { it.readBytes() })
        val byName = kitchen.associateBy { it["name"]?.jsonPrimitive?.content }
        val ingredientNodes = manifest.jsonObject.getValue("ingredients").jsonArray.map { it.jsonObject }
        for (ingredient in ingredientNodes) {
            val node = ingredient.getValue("node").jsonPrimitive.content
            val ring = "Selection_" + ingredient.getValue("id").jsonPrimitive.content
            val children = byName.getValue(node).getValue("children").jsonArray
            assertTrue("$ring should remain under $node", children.any { kitchen[it.jsonPrimitive.int] == byName.getValue(ring) })
        }
        val cooking = setOf("CookPot", "PotFood", "StoveFlame") + (0..5).map { "Steam_$it" }
        assertTrue(byName.keys.containsAll(cooking))
        assertTrue(modelNodes(assets.open("cook/scenes/showcase-plate.glb").use { it.readBytes() }).isNotEmpty())
        val showcase = ShowcaseAssetLibrary.bundled(context, this)
        assertEquals(setOf(250, 497, 588), showcase.dishes.keys)
        for ((recipeId, packs) in showcase.dishes) {
            assertTrue(assets.open("cook/showcase/$recipeId.webp").use { it.read() } >= 0)
            assertEquals(listOf(ShowcaseKind.Image, ShowcaseKind.Model), packs.map { it.asset.kind })
            val (image, model) = packs
            assertNull(image.installBundled())
            assertTrue(model.hasBundledModel)
            model.installBundled()!!.join()
            val localFile = requireNotNull(model.state.value.installation).file
            assertEquals(model.asset.filename, localFile.name)
            assertEquals(model.asset.bytes.toLong(), localFile.length())
            assertTrue(modelNodes(localFile.readBytes()).isNotEmpty())
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
    fun browserFallbackKeepsTheOriginalShareOrRebuildsTheSameRecipeFromOlderState() {
        val canonical = RecipeShareLinks.url(250)
        val linked = "https://cook.yunle.fun/recipes/share?id=%32%35%30&from=share%20card#ingredients"
        assertEquals(linked, RecipeShareLinks.browserUrl(250L, linked))
        assertEquals(canonical, RecipeShareLinks.browserUrl(250L, null))
        assertEquals(canonical, RecipeShareLinks.browserUrl(250L, linked.replace("%32%35%30", "251")))
        assertEquals(canonical, RecipeShareLinks.browserUrl(250L, linked.replace("cook.yunle.fun", "example.com")))
        assertEquals(canonical, RecipeShareLinks.browserUrl(250L, linked + "x".repeat(10_000)))
    }

    @Test
    fun explicitLearningAndUndoPreserveOtherCooksAndPersistAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CookingJournalStore(context)
        val catalog = JournalRecipeId.Catalog(250)
        val personal = JournalRecipeId.Personal("custom-tomato-egg")
        val now = System.currentTimeMillis()
        assertTrue(store.recordCooked(catalog, "番茄炒蛋", now))
        assertFalse(store.recordCooked(catalog, "番茄炒蛋", now))
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
        store.removeLearned(personalConfirmation.record.id)
        store.removeCooked(requireNotNull(replacement.autoCookedId))
        val reopened = CookingJournalStore(context).state.value
        assertEquals(listOf(replacement.record), reopened.learned)
        assertTrue(reopened.entries(learnedOnly = false).any { it.learned && it.cookedId == null })
    }

    @Test
    fun automaticFridgeCategoryUsesAndroidDairyGroupAndIncludesFruitWithoutStorageAdvice() {
        val food = FoodCatalog(
            vegetable = listOf(StuffItem("番茄")),
            meat = listOf(StuffItem("牛奶"), StuffItem("鸡蛋")),
        )
        assertEquals("dairy", FridgeCategory.automaticId("牛奶", food))
        assertEquals("meat", FridgeCategory.automaticId("鸡蛋", food))
        assertEquals("veg", FridgeCategory.automaticId("番茄", food))
        assertEquals("veg", FridgeCategory.automaticId("香蕉", food))
        assertEquals(FridgeBatchItem.DEFAULT_CATEGORY_ID, FridgeCategory.automaticId("自制饮料", food))
    }

    @Test
    fun photoReviewRespectsConfirmedDatesAndRecalculatesFromCorrectedProductionAndCutDays() {
        val today = LocalDate.of(2026, 11, 21)
        val start = today.minusDays(2)
        val shelfLife = ShelfLife()
        val empty = FridgeLabelParser.parse("", today = today)
        val blueberries = FridgePhotoDates(startDay = start).review(empty, "蓝莓", today, shelfLife)
        assertEquals(today, blueberries.expiry)
        assertEquals(FridgeDateKind.ADVISORY, blueberries.kind)
        assertEquals(FridgeStorageStart(start.toString(), wasCut = false), blueberries.storageStart)

        val watermelon = FridgePhotoDates(startDay = start, cut = true).review(empty, "西瓜", today, shelfLife)
        assertEquals(start.plusDays(3), watermelon.expiry)
        assertEquals(FridgeStorageStart(start.toString(), wasCut = true), watermelon.storageStart)

        val label = FridgeLabelParser.parse("MFG: 2026-11-07 SHELF LIFE: 10 DAYS", today = today)
        val corrected = LocalDate.of(2026, 11, 9)
        val calculated = FridgePhotoDates(productionOverride = corrected).review(label, "牛奶", today, shelfLife)
        assertEquals(corrected, calculated.production)
        assertEquals(corrected.plusDays(10), calculated.expiry)
        assertEquals(FridgeDateKind.CALCULATED, calculated.kind)
        assertNull(calculated.storageStart)

        val ambiguous = FridgeLabelParser.parse("2026-11-01 / 2026-11-09", today = today)
        assertNull(FridgePhotoDates().review(ambiguous, "牛奶", today, shelfLife).expiry)
        val selected = FridgePhotoDates(basis = FridgeExpiryBasis.PACKAGE, customExpiry = ambiguous.dateCandidates.first())
        val invalid = selected.copy(productionOverride = corrected).review(ambiguous, "牛奶", today, shelfLife)
        assertEquals("记录日期早于生产日期，请核对。", invalid.problem)
    }

    @Test
    fun editingOneFridgeBatchPreservesProvenanceAndOtherBatches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FridgeStore(context)
        val date = LocalDate.of(2026, 11, 27)
        val milk = FridgeBatchItem(
            ingredientName = "牛奶",
            categoryId = "dairy",
            quantityText = "500 ml",
            addedAtMs = 100,
            productionIsoDate = "2026-11-20",
            expiresOnIsoDate = date.toString(),
            dateKind = FridgeDateKind.CALCULATED,
        )
        val otherBatch = milk.copy(id = "milk-second-batch", addedAtMs = 200)
        store.addItems(listOf(milk, otherBatch))
        store.addCategory("本周采购")
        val category = store.state.value.categories.single { it.name == "本周采购" }

        store.updateItem(milk.id, " 900 ml ", category.id, date, FridgeDateKind.CALCULATED)
        val unchangedDate = milk.copy(quantityText = "900 ml", categoryId = category.id)
        assertEquals(listOf(unchangedDate, otherBatch), store.state.value.stock)
        val replacementDate = date.plusDays(2)
        store.updateItem(milk.id, "900 ml", category.id, replacementDate, FridgeDateKind.CALCULATED)
        val edited = unchangedDate.copy(expiresOnIsoDate = replacementDate.toString(), dateKind = FridgeDateKind.MANUAL)
        assertEquals(listOf(edited, otherBatch), FridgeStore(context).state.value.stock)

        assertTrue(runCatching { store.updateItem(milk.id, "1 l", "missing-category", date, FridgeDateKind.PACKAGE_EXPIRY) }.isFailure)
        assertEquals(listOf(edited, otherBatch), store.state.value.stock)
        store.markConsumed(milk.id, nowMs = 300)
        assertTrue(runCatching { store.updateItem(milk.id, "1 l", category.id, date, FridgeDateKind.PACKAGE_EXPIRY) }.isFailure)
        store.deleteCategory(category.id) { "dairy" }
        assertEquals(listOf(edited.copy(categoryId = "dairy", consumedAtMs = 300)), store.state.value.consumed)
        store.restoreItem(milk.id)
        assertEquals(listOf(edited.copy(categoryId = "dairy"), otherBatch), FridgeStore(context).state.value.stock)
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
