package cn.yunyoujun.cook.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.ShelfLife
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

/**
 * 估算日期始终是食用计划，不能由照片判断食物是否安全。
 * 对齐 iOS `FridgeIntake.swift` (`FridgeExpiryBasis`).
 */
enum class FridgeExpiryBasis(
    val rawValue: String,
    val title: String,
) {
    ESTIMATED("estimated", "食用时间建议"),
    PACKAGE("package", "包装标注日期"),
    CALCULATED("calculated", "生产日期与保质期推算"),
    MANUAL("manual", "自己设定的食用计划"),
    ;

    val isSuggested: Boolean
        get() = this == ESTIMATED || this == MANUAL

    fun toDateKind(): FridgeDateKind = when (this) {
        PACKAGE -> FridgeDateKind.PACKAGE_EXPIRY
        CALCULATED -> FridgeDateKind.CALCULATED
        ESTIMATED -> FridgeDateKind.ADVISORY
        MANUAL -> FridgeDateKind.MANUAL
    }
}

/**
 * 取公开储存建议区间的较短端，完整蔬果与切开蔬果分别计算。
 * 对齐 iOS `ProduceStorageAdvice`。
 */
data class ProduceStorageAdvice(
    val days: Int,
    val condition: String,
    val sourceUrl: String,
) {
    fun dateFrom(day: LocalDate): LocalDate = day.plusDays(days.toLong())

    companion object {
        const val UMAINE_SOURCE =
            "https://extension.umaine.edu/food-health/2025/05/29/storing-and-washing-fresh-fruits-and-vegetables/"
        const val FDA_APPLE_SOURCE =
            "https://www.fda.gov/food/consumers/how-cut-food-waste-and-maintain-food-safety"

        val FRUIT_NAMES = listOf(
            "草莓", "蓝莓", "树莓", "黑莓", "桃", "油桃", "梨", "苹果",
            "香蕉", "葡萄", "橙子", "菠萝", "西瓜",
        )
        val PRODUCE_NAMES = FRUIT_NAMES + listOf(
            "西兰花", "胡萝卜", "白萝卜", "玉米", "黄瓜", "茄子",
            "生菜", "菠菜", "青菜", "青椒", "西葫芦", "豆角", "荷兰豆",
        )
        private val EXTRA_CUT_NAMES = setOf("番茄", "土豆", "洋葱", "菌菇")

        fun recommendation(name: String, cut: Boolean = false): ProduceStorageAdvice? {
            if (cut) {
                if (name !in PRODUCE_NAMES && name !in EXTRA_CUT_NAMES) return null
                // 切开不会延长完整食材的建议时间，例如蓝莓原本只建议 2 天。
                val baseDays = recommendation(name, cut = false)?.days ?: 3
                val days = minOf(if (name == "番茄") 2 else 3, baseDays)
                return ProduceStorageAdvice(
                    days = days,
                    condition = "切开后及时密封冷藏，保持 4℃ 或以下；从切开当天起算。",
                    sourceUrl = UMAINE_SOURCE,
                )
            }
            val days = when (name) {
                "草莓", "蓝莓", "树莓", "黑莓" -> 2
                "桃", "油桃", "梨" -> 5
                "西兰花", "豆角" -> 3
                "胡萝卜", "白萝卜" -> 7
                "玉米", "茄子", "菠菜", "青菜" -> 1
                "生菜" -> 5
                "黄瓜" -> 7
                "青椒" -> 14
                "西葫芦", "荷兰豆" -> 2
                "苹果" -> return ProduceStorageAdvice(
                    days = 28,
                    condition = "完整苹果冷藏，保持 4℃ 或以下；从购买当天起算。",
                    sourceUrl = FDA_APPLE_SOURCE,
                )
                else -> return null
            }
            val ripeness = if (name in setOf("桃", "油桃", "梨")) "已成熟、完整" else "完整"
            return ProduceStorageAdvice(
                days = days,
                condition = "${ripeness}蔬果冷藏，保持 4℃ 或以下；从购买当天起算。",
                sourceUrl = UMAINE_SOURCE,
            )
        }
    }
}

internal data class FridgeIntakeEstimate(
    val days: Int,
    val kind: FridgeDateKind,
    val storageCondition: String? = null,
) {
    fun dateFrom(day: LocalDate): LocalDate = day.plusDays(days.toLong())
}

internal fun estimateFridgeIntake(
    name: String,
    shelfLife: ShelfLife,
    plannedDays: Int? = null,
    cut: Boolean = false,
): FridgeIntakeEstimate {
    if (plannedDays != null) return FridgeIntakeEstimate(plannedDays, FridgeDateKind.MANUAL)
    val advice = ProduceStorageAdvice.recommendation(name, cut)
    return if (advice != null) {
        FridgeIntakeEstimate(advice.days, FridgeDateKind.ADVISORY, advice.condition)
    } else {
        FridgeIntakeEstimate(shelfLife.items[name] ?: shelfLife.defaultDays, FridgeDateKind.ESTIMATED)
    }
}

data class FridgeLabelScan(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val names: List<String>,
    val productionDay: LocalDate? = null,
    val expiryDay: LocalDate? = null,
    val basis: FridgeExpiryBasis = FridgeExpiryBasis.ESTIMATED,
    val dateCandidates: List<LocalDate> = emptyList(),
    val messages: List<String> = emptyList(),
) {
    val suggestsBatchReview: Boolean
        get() = names.size != 1 && productionDay == null && expiryDay == null && dateCandidates.isEmpty()
}

/**
 * 只接受带完整 4 位年份的日期。无标签、多个相互冲突的日期或非法日期都留给用户确认。
 * 1:1 对齐 iOS `FridgeLabelParser` 与 `FridgePhotoRecognition`。
 */
object FridgeLabelParser {
    private const val DATE_PATTERN_STR =
        """(?<![0-9])((?:19|20|21)[0-9]{2})(?:\s*[-./年]\s*([0-9]{1,2})\s*[-./月]\s*([0-9]{1,2})\s*日?|([0-9]{2})([0-9]{2}))(?![0-9])"""
    private const val PRODUCTION_LABELS_STR =
        """生产日期|生产时间|制造日期|(?i:\bMFG\b|\bMFD\b|\bPROD\b)"""
    private const val EXPIRY_LABELS_STR =
        """保质期至|有效期至|有效日期|到期日期|到期日|限期使用日期|食用期限|此日期前食用|(?i:\bEXP\b|\bUSE\s+BY\b|\bBEST\s+BEFORE\b)"""
    private const val SHELF_LIFE_PATTERN_STR =
        """(?:保质期|(?i:\bSHELF\s+LIFE\b))[：:\s]*([0-9]{1,4})\s*(天|日|个月|月|年|(?i:days?|months?|years?))"""

    private val dateRegex = Regex(DATE_PATTERN_STR)
    private val productionLabelRegex = Regex(PRODUCTION_LABELS_STR)
    private val expiryLabelRegex = Regex(EXPIRY_LABELS_STR)
    private val productionLabeledDateRegex = Regex("(?:$PRODUCTION_LABELS_STR)[：:\\s]*$DATE_PATTERN_STR")
    private val expiryLabeledDateRegex = Regex("(?:$EXPIRY_LABELS_STR)[：:\\s]*$DATE_PATTERN_STR")
    private val shelfLifeRegex = Regex(SHELF_LIFE_PATTERN_STR)

    private val CLASSIFIER_NAME_MAP = mapOf(
        "apple" to "苹果",
        "banana" to "香蕉",
        "bell_pepper" to "青椒",
        "blueberry" to "蓝莓",
        "blackberry" to "黑莓",
        "broccoli" to "西兰花",
        "carrot" to "胡萝卜",
        "cucumber" to "黄瓜",
        "eggplant" to "茄子",
        "grape" to "葡萄",
        "lettuce" to "生菜",
        "mushroom" to "菌菇",
        "onion" to "洋葱",
        "oranges" to "橙子",
        "peach" to "桃",
        "pear" to "梨",
        "pineapple" to "菠萝",
        "potato" to "土豆",
        "raspberry" to "树莓",
        "strawberry" to "草莓",
        "tomato" to "番茄",
        "watermelon" to "西瓜",
    )

    internal fun ingredientVocabulary(food: FoodCatalog, normalizer: StuffNormalizer): Set<String> {
        val ingredients = (food.vegetable + food.meat + food.staple).map { it.name }.toSet() + ProduceStorageAdvice.PRODUCE_NAMES
        return ingredients + normalizer.aliases.filterValues { it in ingredients }.keys
    }

    /**
     * 从 OCR 文本中匹配已知食材名：「日本豆腐」不再同时建议「豆腐」。
     */
    fun matchKnownNames(
        text: String,
        knownNames: Collection<String>,
        classifications: List<Pair<String, Float>> = emptyList(),
    ): List<String> {
        val matches = knownNames
            .filter { it.isNotEmpty() && text.contains(it) }
            .sortedWith { a, b ->
                if (a.length == b.length) a.compareTo(b) else b.length.compareTo(a.length)
            }
        val names = ArrayList<String>()
        for (name in matches) {
            if (names.none { it.contains(name) }) {
                names.add(name)
            }
        }
        if (names.isEmpty() && classifications.isNotEmpty()) {
            names.addAll(imageNames(classifications))
        }
        return names.take(6)
    }

    /**
     * 仅映射具体类别；不把 fruit、vegetable 这样的泛类猜成某一种食材。
     */
    fun imageNames(classifications: List<Pair<String, Float>>): List<String> {
        val result = ArrayList<String>()
        for ((identifier, confidence) in classifications.sortedByDescending { it.second }) {
            if (confidence < 0.35f) continue
            val mapped = CLASSIFIER_NAME_MAP[identifier] ?: continue
            if (mapped !in result) {
                result.add(mapped)
            }
        }
        return result.take(3)
    }

    internal fun suggestedProduceNames(suggestions: List<String>): List<String> =
        suggestions.mapNotNull(CLASSIFIER_NAME_MAP::get).distinct()

    fun parse(
        text: String,
        names: List<String> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): FridgeLabelScan {
        val allDates = dateRegex.findAll(text).mapNotNull { parseMatchDate(it) }.distinct().sorted().toList()
        val productionDates = productionLabeledDateRegex.findAll(text).mapNotNull { parseMatchDate(it) }.toSet()
        val expiryDates = expiryLabeledDateRegex.findAll(text).mapNotNull { parseMatchDate(it) }.toSet()

        val messages = ArrayList<String>()
        var productionDay: LocalDate? = null
        var expiryDay: LocalDate? = null
        var basis = FridgeExpiryBasis.ESTIMATED

        if (productionDates.size == 1) {
            val day = productionDates.first()
            if (!day.isAfter(today)) {
                productionDay = day
            } else {
                messages.add("生产日期存在冲突或晚于今天，请核对包装。")
            }
        } else if (productionDates.isNotEmpty()) {
            messages.add("生产日期存在冲突或晚于今天，请核对包装。")
        }

        if (expiryDates.size == 1) {
            val day = expiryDates.first()
            if (productionDay != null && day.isBefore(productionDay)) {
                messages.add("到期日早于生产日期，请重新核对。")
            } else {
                expiryDay = day
                basis = FridgeExpiryBasis.PACKAGE
            }
        } else if (expiryDates.size > 1) {
            messages.add("识别到多个到期日，请选择正确日期。")
        }

        if (expiryDay == null && !expiryLabelRegex.containsMatchIn(text) && productionDay != null) {
            val calculated = calculatedExpiry(productionDay, text)
            if (calculated != null) {
                expiryDay = calculated
                basis = FridgeExpiryBasis.CALCULATED
                messages.add("按生产日期加包装保质期推算，请核对起算方式及包装上的储存条件。")
            }
        }

        if (expiryDay == null) {
            messages.add("没有确认到期日。可选择识别出的日期，或按食材生成食用计划。")
        }

        return FridgeLabelScan(
            text = text,
            names = names,
            productionDay = productionDay,
            expiryDay = expiryDay,
            basis = basis,
            dateCandidates = allDates,
            messages = messages,
        )
    }

    fun calculatedExpiry(productionDay: LocalDate, labelText: String): LocalDate? {
        val found = shelfLifeRegex.findAll(labelText).toList()
        if (found.size != 1) return null
        val match = found.first()
        val value = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val unit = match.groupValues[2].lowercase()
        return when {
            unit in setOf("天", "日", "day", "days") && value <= 3660 -> productionDay.plusDays(value.toLong())
            unit in setOf("月", "个月", "month", "months") && value <= 120 -> productionDay.plusMonths(value.toLong())
            unit in setOf("年", "year", "years") && value <= 10 -> productionDay.plusYears(value.toLong())
            else -> null
        }
    }

    private fun parseMatchDate(match: MatchResult): LocalDate? {
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: match.groupValues[4].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: match.groupValues[5].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
}

/** The position fractions describe the space left after fitting the square into the upright photo. */
internal data class FridgePhotoFocus(
    val sizeFraction: Float = .5f,
    val horizontalPosition: Float = .5f,
    val verticalPosition: Float = .5f,
) {
    fun bounds(width: Float, height: Float): RectF {
        val side = minOf(width, height) * sizeFraction
        val left = (width - side) * horizontalPosition
        val top = (height - side) * verticalPosition
        return RectF(left, top, left + side, top + side)
    }
}

/**
 * 照片仅在内存中缩小处理并保留 EXIF 方向。优先识别包装文字，没有匹配的食材时再运行本机蔬果分类。
 */
object FridgePhotoInspector {
    const val MAX_PHOTO_BYTES = 30 * 1024 * 1024
    const val MAX_PIXEL_DIMENSION = 2400
    private const val FOCUS_PREVIEW_DIMENSION = 640

    class UnreadableImageException :
        Exception("无法读取这张照片，请换一张清晰的照片或手动添加。")

    data class InMemoryPhotoSummary(
        val width: Int,
        val height: Int,
        val byteCount: Int,
        val rotationDegrees: Int = 0,
    )

    internal data class InMemoryRecognitionResult(
        val summary: InMemoryPhotoSummary,
        val recognizedText: String,
        val produce: FridgeProduceMatch = FridgeProduceMatch(),
        val modelUnavailable: Boolean = false,
    )

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private data class PhotoOrientation(val degrees: Int, val mirrored: Boolean = false) {
        fun uprightTransform(): Matrix = Matrix().apply {
            if (mirrored) setScale(-1f, 1f)
            postRotate(degrees.toFloat())
        }
    }

    private fun photoOrientation(bytes: ByteArray, captureRotation: Int?): PhotoOrientation {
        if (captureRotation != null) return PhotoOrientation(captureRotation)
        return when (readExifOrientation(bytes)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> PhotoOrientation(0, mirrored = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> PhotoOrientation(180, mirrored = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> PhotoOrientation(270, mirrored = true)
            ExifInterface.ORIENTATION_TRANSVERSE -> PhotoOrientation(90, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_90 -> PhotoOrientation(90)
            ExifInterface.ORIENTATION_ROTATE_180 -> PhotoOrientation(180)
            ExifInterface.ORIENTATION_ROTATE_270 -> PhotoOrientation(270)
            else -> PhotoOrientation(0)
        }
    }

    private fun requirePhotoSize(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) throw UnreadableImageException()
    }

    private fun sampledOptions(width: Int, height: Int, maxDimension: Int): BitmapFactory.Options {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) sampleSize *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    fun decodeBoundedInMemory(
        bytes: ByteArray,
        captureRotation: Int? = null,
    ): Pair<InMemoryPhotoSummary, Bitmap> = decodeBounded(bytes, captureRotation, MAX_PIXEL_DIMENSION)

    private fun decodeBounded(
        bytes: ByteArray,
        captureRotation: Int?,
        maxDimension: Int,
    ): Pair<InMemoryPhotoSummary, Bitmap> {
        requirePhotoSize(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw UnreadableImageException()
        }
        val decodeOpts = sampledOptions(bounds.outWidth, bounds.outHeight, maxDimension)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw UnreadableImageException()
        val orientation = photoOrientation(bytes, captureRotation)
        // 镜像方向先水平翻转，OCR、分类和上传准备各自应用同一个剩余旋转角度。
        val bitmap = if (orientation.mirrored) {
            try {
                val mirror = Matrix().apply { setScale(-1f, 1f) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, mirror, false)
            } finally {
                decoded.recycle()
            }
        } else decoded
        val summary = InMemoryPhotoSummary(
            width = bitmap.width,
            height = bitmap.height,
            byteCount = bytes.size,
            rotationDegrees = orientation.degrees,
        )
        return summary to bitmap
    }

    internal suspend fun preview(bytes: ByteArray, captureRotation: Int?): Bitmap = withContext(Dispatchers.Default) {
        val (summary, bitmap) = decodeBounded(bytes, captureRotation, FOCUS_PREVIEW_DIMENSION)
        val rotation = summary.rotationDegrees
        if (rotation == 0) return@withContext bitmap
        try {
            val transform = Matrix().apply { setRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, false)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeForRecognition(
        bytes: ByteArray,
        captureRotation: Int?,
        focus: FridgePhotoFocus?,
    ): Pair<InMemoryPhotoSummary, Bitmap> {
        if (focus == null) return decodeBoundedInMemory(bytes, captureRotation)
        requirePhotoSize(bytes)
        decodeFocusedRegion(bytes, captureRotation, focus)?.let { return it }

        // Formats without a region decoder can still use the bounded whole-photo decoder.
        val (summary, bitmap) = decodeBoundedInMemory(bytes, captureRotation)
        var crop: Bitmap? = null
        try {
            val rotation = Matrix().apply { setRotate(summary.rotationDegrees.toFloat()) }
            val bounds = sourceCropBounds(bitmap.width, bitmap.height, focus, rotation)
            crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height(), rotation, false)
            return summary.copy(width = crop.width, height = crop.height, rotationDegrees = 0) to crop
        } finally {
            if (crop !== bitmap) bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION") // The four-argument decoder is also available on API 26–30.
    private fun decodeFocusedRegion(
        bytes: ByteArray,
        captureRotation: Int?,
        focus: FridgePhotoFocus,
    ): Pair<InMemoryPhotoSummary, Bitmap>? {
        val transform = photoOrientation(bytes, captureRotation).uprightTransform()
        val decoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (_: IOException) {
            return null
        } ?: return null
        val region = try {
            if (decoder.width <= 0 || decoder.height <= 0) return null
            val bounds = sourceCropBounds(decoder.width, decoder.height, focus, transform)
            decoder.decodeRegion(bounds, sampledOptions(bounds.width(), bounds.height(), MAX_PIXEL_DIMENSION))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            decoder.recycle()
        } ?: return null
        var upright: Bitmap? = null
        try {
            upright = Bitmap.createBitmap(region, 0, 0, region.width, region.height, transform, false)
            return InMemoryPhotoSummary(upright.width, upright.height, bytes.size) to upright
        } finally {
            if (upright !== region) region.recycle()
        }
    }

    private fun sourceCropBounds(width: Int, height: Int, focus: FridgePhotoFocus, uprightTransform: Matrix): Rect {
        val upright = RectF(0f, 0f, width.toFloat(), height.toFloat())
        uprightTransform.mapRect(upright)
        val selected = focus.bounds(upright.width(), upright.height()).apply {
            offset(upright.left, upright.top)
        }
        val inverse = Matrix()
        check(uprightTransform.invert(inverse))
        inverse.mapRect(selected)
        val left = selected.left.roundToInt().coerceIn(0, width - 1)
        val top = selected.top.roundToInt().coerceIn(0, height - 1)
        val right = selected.right.roundToInt().coerceIn(left + 1, width)
        val bottom = selected.bottom.roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    fun validateAndDecodeInMemory(bytes: ByteArray): InMemoryPhotoSummary {
        val (summary, bitmap) = decodeBoundedInMemory(bytes)
        bitmap.recycle()
        return summary
    }

    internal suspend fun recognizeInMemoryBytes(
        bytes: ByteArray,
        knownNames: Collection<String>,
        captureRotation: Int? = null,
        focus: FridgePhotoFocus? = null,
        classify: (Bitmap, Int) -> FridgeProduceMatch,
    ): InMemoryRecognitionResult = withContext(Dispatchers.Default) {
        val (summary, bitmap) = decodeForRecognition(bytes, captureRotation, focus)
        try {
            recognizePhoto(bitmap, summary, knownNames, classify)
        } finally {
            bitmap.recycle()
        }
    }

    internal suspend fun classifyInMemoryBytes(
        bytes: ByteArray,
        captureRotation: Int?,
        focus: FridgePhotoFocus? = null,
        classify: (Bitmap, Int) -> FridgeProduceMatch,
    ): FridgeProduceMatch = withContext(Dispatchers.Default) {
        val (summary, bitmap) = decodeForRecognition(bytes, captureRotation, focus)
        try {
            classify(bitmap, summary.rotationDegrees)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizePhoto(
        bitmap: Bitmap,
        summary: InMemoryPhotoSummary,
        knownNames: Collection<String>,
        classify: (Bitmap, Int) -> FridgeProduceMatch,
    ): InMemoryRecognitionResult {
        val text = try {
            recognizeBitmapText(bitmap, summary.rotationDegrees)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        coroutineContext.ensureActive()
        val matches = FridgeLabelParser.matchKnownNames(text, knownNames)
        val result = try {
            val produce = if (matches.isEmpty()) classify(bitmap, summary.rotationDegrees) else FridgeProduceMatch()
            InMemoryRecognitionResult(summary, text, produce)
        } catch (_: FridgeProduceClassifier.ModelUnavailable) {
            InMemoryRecognitionResult(summary, text, modelUnavailable = true)
        }
        coroutineContext.ensureActive()
        return result
    }

    @Volatile
    internal var textRecognizerOverride: (suspend (Bitmap, Int) -> String)? = null

    private suspend fun recognizeBitmapText(bitmap: Bitmap, rotationDegrees: Int): String {
        textRecognizerOverride?.let { overrideFn ->
            return overrideFn(bitmap, rotationDegrees).trim()
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        return try {
            // ML Kit uses the bitmap asynchronously. Wait for completion before the caller recycles it.
            suspendCoroutine { cont ->
                recognizer.process(image)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) cont.resume(task.result.text.trim())
                        else cont.resumeWithException(task.exception ?: UnreadableImageException())
                    }
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
