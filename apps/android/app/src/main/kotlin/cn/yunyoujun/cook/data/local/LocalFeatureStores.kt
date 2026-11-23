package cn.yunyoujun.cook.data.local

import android.content.Context
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.StuffItem
import cn.yunyoujun.cook.core.recommend.RecipeMatch
import cn.yunyoujun.cook.core.recipe.RecipeAmounts
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeStepUsagePlan
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.core.sync.CatalogEntry
import cn.yunyoujun.cook.core.sync.PersonalCatalog
import cn.yunyoujun.cook.core.sync.PersonalCatalogException
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeImport
import cn.yunyoujun.cook.core.sync.PrivateRecipeNativeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

object BoundedIoStreams {
    class TooLargeException(val maxBytes: Int) :
        IllegalArgumentException("文件超过大小上限（最大 ${maxBytes / 1024} KB）")

    fun readBytesBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes.coerceAtLeast(1024), 64 * 1024))
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw TooLargeException(maxBytes)
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    fun readUtf8Bounded(input: InputStream, maxBytes: Int): String =
        readBytesBounded(input, maxBytes).toString(Charsets.UTF_8)
}

internal object AtomicJsonFiles {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun readTextRecoverable(file: File): String? {
        val bak = File(file.parentFile, "${file.name}.bak")
        if (file.isFile && file.length() > 0L) {
            return file.readText(Charsets.UTF_8)
        }
        if (bak.isFile && bak.length() > 0L) {
            runCatching {
                Files.copy(bak.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return bak.readText(Charsets.UTF_8)
        }
        return null
    }

    inline fun <reified T> readOrDefault(file: File, defaultValue: () -> T): T {
        val bak = File(file.parentFile, "${file.name}.bak")
        if (file.isFile) {
            runCatching {
                return json.decodeFromString<T>(file.readText(Charsets.UTF_8))
            }
        }
        if (bak.isFile) {
            runCatching {
                val recovered = json.decodeFromString<T>(bak.readText(Charsets.UTF_8))
                Files.copy(bak.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                bak.delete()
                return recovered
            }
        }
        return defaultValue()
    }

    fun writeAtomicText(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${file.name}.tmp")
        val bak = File(parent, "${file.name}.bak")
        val bytes = content.toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        val movedAtomically = runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            bak.delete()
            true
        }.getOrDefault(false)
        if (!movedAtomically) {
            if (file.isFile && file.length() > 0L) {
                Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                bak.delete()
            } catch (e: Exception) {
                if (bak.isFile && (!file.isFile || file.length() == 0L)) {
                    runCatching {
                        Files.copy(bak.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                throw e
            } finally {
                tmp.delete()
            }
        }
    }

    inline fun <reified T> writeAtomic(file: File, value: T) {
        writeAtomicText(file, json.encodeToString(value))
    }
}

@Serializable
data class FridgeCategory(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
) {
    companion object {
        internal const val NAME_LIMIT = 20
        private val dairyNames = setOf("牛奶", "酸奶", "奶酪", "芝士", "奶油", "淡奶油", "黄油")

        internal fun nameError(name: String, categories: List<FridgeCategory>, editingId: String? = null): String? {
            val trimmed = name.trim()
            return when {
                trimmed.isEmpty() -> "请输入分类名称。"
                trimmed.length > NAME_LIMIT -> "分类名称最多 $NAME_LIMIT 个字。"
                trimmed == "自动归类" || categories.any { it.id != editingId && it.name.equals(trimmed, ignoreCase = true) } ->
                    "已有同名分类。"
                else -> null
            }
        }

        fun automaticId(name: String, food: FoodCatalog): String = when {
            name in dairyNames -> "dairy"
            name in ProduceStorageAdvice.PRODUCE_NAMES || food.vegetable.any { it.name == name } -> "veg"
            food.meat.any { it.name == name } -> "meat"
            else -> FridgeBatchItem.DEFAULT_CATEGORY_ID
        }
    }
}

@Serializable
enum class FridgeDateKind(val label: String) {
    PACKAGE_EXPIRY("包装保质期"),
    CALCULATED("生产日期与保质期推算"),
    ESTIMATED("按常见天数推算"),
    ADVISORY("建议尽早食用"),
    MANUAL("自设食用计划"),
    ;

    val isSuggested: Boolean get() = this != PACKAGE_EXPIRY && this != CALCULATED

    fun afterDateEdit(changed: Boolean): FridgeDateKind =
        if (changed && (this == ESTIMATED || this == ADVISORY || this == CALCULATED)) MANUAL else this
}

@Serializable
data class FridgeStorageStart(val isoDate: String, val wasCut: Boolean) {
    val label: String get() = if (wasCut) "切开日期" else "购买日期"
}

@Serializable
data class FridgeBatchItem(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val ingredientName: String,
    val categoryId: String = DEFAULT_CATEGORY_ID,
    val quantityText: String = "",
    val addedAtMs: Long = System.currentTimeMillis(),
    val expiresOnIsoDate: String? = null,
    val dateKind: FridgeDateKind = FridgeDateKind.ESTIMATED,
    val consumedAtMs: Long? = null,
    val productionIsoDate: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val storageStart: FridgeStorageStart? = null,
) {
    fun daysRemaining(today: LocalDate = LocalDate.now()): Long? {
        val iso = expiresOnIsoDate ?: return null
        val target = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
        return ChronoUnit.DAYS.between(today, target)
    }

    companion object {
        const val DEFAULT_CATEGORY_ID = "default-other"
    }
}

internal data class FridgeStockEntry(val batch: FridgeBatchItem, val remaining: Long?) {
    val usableForCooking: Boolean get() = remaining == null || remaining >= 0
    val needsAttention: Boolean get() = remaining != null && remaining in 0L..2L
}

@Serializable
data class FridgeDocument(
    val categories: List<FridgeCategory> = defaultCategories(),
    val items: List<FridgeBatchItem> = emptyList(),
) {
    val stock: List<FridgeBatchItem> get() = items.filter { it.consumedAtMs == null }
    val consumed: List<FridgeBatchItem> get() = items.filter { it.consumedAtMs != null }.sortedByDescending { it.consumedAtMs }

    internal fun stockOn(today: LocalDate): List<FridgeStockEntry> =
        stock.map { FridgeStockEntry(it, it.daysRemaining(today)) }
            .sortedBy { it.remaining ?: Long.MAX_VALUE }

    companion object {
        fun defaultCategories(): List<FridgeCategory> = listOf(
            FridgeCategory("veg", "蔬菜水果", isDefault = true),
            FridgeCategory("meat", "肉蛋水产", isDefault = true),
            FridgeCategory("dairy", "乳品熟食", isDefault = true),
            FridgeCategory(FridgeBatchItem.DEFAULT_CATEGORY_ID, "其他", isDefault = true),
        )
    }
}

class FridgeStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "fridge-store-v1.json")
    private val _state = MutableStateFlow(readInitial())
    val state: StateFlow<FridgeDocument> = _state.asStateFlow()

    private fun readInitial(): FridgeDocument {
        val doc = AtomicJsonFiles.readOrDefault(file) { FridgeDocument() }
        return if (doc.categories.isEmpty()) doc.copy(categories = FridgeDocument.defaultCategories()) else doc
    }

    fun addItem(
        ingredientName: String,
        categoryId: String,
        quantityText: String,
        shelfLifeDays: Int,
        explicitIsoDate: String? = null,
        dateKind: FridgeDateKind = FridgeDateKind.ESTIMATED,
        productionIsoDate: String? = null,
        storageStart: FridgeStorageStart? = null,
    ) {
        val trimmed = ingredientName.trim()
        if (trimmed.isEmpty()) return
        val expires = explicitIsoDate ?: LocalDate.now().plusDays(shelfLifeDays.coerceIn(1, 365).toLong()).toString()
        val item = FridgeBatchItem(
            ingredientName = trimmed,
            categoryId = categoryId,
            quantityText = quantityText.trim(),
            addedAtMs = System.currentTimeMillis(),
            expiresOnIsoDate = expires,
            dateKind = dateKind,
            productionIsoDate = productionIsoDate,
            storageStart = storageStart,
        )
        addItems(listOf(item))
    }

    fun addItems(items: List<FridgeBatchItem>) {
        update { doc ->
            val categoryIds = doc.categories.mapTo(mutableSetOf()) { it.id }
            val additions = items.map { item ->
                if (item.categoryId in categoryIds) item else item.copy(categoryId = FridgeBatchItem.DEFAULT_CATEGORY_ID)
            }
            doc.copy(items = additions + doc.items)
        }
    }

    fun updateItem(
        itemId: String,
        quantityText: String,
        categoryId: String,
        expiry: LocalDate?,
        dateKind: FridgeDateKind,
    ) {
        require(expiry == null || expiry.year in 1..9999)
        update { prev ->
            require(prev.categories.any { it.id == categoryId })
            val index = prev.items.indexOfFirst { it.id == itemId && it.consumedAtMs == null }
            require(index >= 0)
            val items = prev.items.toMutableList()
            val original = items[index]
            val date = expiry?.toString()
            val updatedKind = dateKind.afterDateEdit(date != original.expiresOnIsoDate)
            items[index] = original.copy(
                categoryId = categoryId,
                quantityText = quantityText.trim(),
                expiresOnIsoDate = date,
                dateKind = updatedKind,
                storageStart = original.storageStart.takeIf { date == original.expiresOnIsoDate && updatedKind == original.dateKind },
            )
            prev.copy(items = items)
        }
    }

    fun removeItem(itemId: String) {
        update { prev -> prev.copy(items = prev.items.filterNot { it.id == itemId }) }
    }

    fun markConsumed(itemId: String, nowMs: Long = System.currentTimeMillis()) {
        update { doc ->
            doc.copy(items = doc.items.map { item ->
                if (item.id == itemId && item.consumedAtMs == null) item.copy(consumedAtMs = nowMs) else item
            })
        }
    }

    fun restoreItem(itemId: String) {
        update { doc ->
            doc.copy(items = doc.items.map { item ->
                if (item.id == itemId) item.copy(consumedAtMs = null) else item
            })
        }
    }

    fun addCategory(name: String): FridgeCategory {
        val category = FridgeCategory(id = "cat-${UUID.randomUUID().toString().take(8)}", name = name.trim())
        update { prev ->
            FridgeCategory.nameError(category.name, prev.categories)?.let { throw IllegalArgumentException(it) }
            prev.copy(categories = prev.categories + category)
        }
        return category
    }

    fun renameCategory(categoryId: String, newName: String) {
        val trimmed = newName.trim()
        update { prev ->
            FridgeCategory.nameError(trimmed, prev.categories, categoryId)?.let { throw IllegalArgumentException(it) }
            prev.copy(
                categories = prev.categories.map {
                    if (it.id == categoryId) it.copy(name = trimmed) else it
                },
            )
        }
    }

    fun deleteCategory(categoryId: String, automaticIdFor: (String) -> String) {
        if (categoryId == FridgeBatchItem.DEFAULT_CATEGORY_ID) return
        update { prev ->
            val categories = prev.categories.filterNot { it.id == categoryId }
            val remainingIds = categories.mapTo(HashSet()) { it.id }
            prev.copy(
                categories = categories,
                items = prev.items.map {
                    if (it.categoryId != categoryId) return@map it
                    val automaticId = automaticIdFor(it.ingredientName)
                    it.copy(categoryId = automaticId.takeIf(remainingIds::contains) ?: FridgeBatchItem.DEFAULT_CATEGORY_ID)
                },
            )
        }
    }

    fun dueSoonCount(today: LocalDate = LocalDate.now()): Int =
        _state.value.stock.count { item ->
            val days = item.daysRemaining(today) ?: return@count false
            days in 0L..1L
        }

    fun formatLocalDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    internal fun restoreBackup(backup: FridgeDocument) = update { current ->
        if (current == FridgeDocument()) return@update backup
        val categories = current.categories.toMutableList()
        val byId = categories.associateBy { it.id }.toMutableMap()
        val categoryIds = backup.categories.associate { incoming ->
            val target = byId[incoming.id]
                ?: categories.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
                ?: incoming.also {
                    categories += it
                    byId[it.id] = it
                }
            incoming.id to target.id
        }
        val currentItems = current.items.mapTo(HashSet()) { it.id }
        val newItems = backup.items.filterNot { it.id in currentItems }.map {
            it.copy(categoryId = categoryIds.getValue(it.categoryId))
        }
        current.copy(categories = categories, items = current.items + newItems)
    }

    private fun update(transform: (FridgeDocument) -> FridgeDocument) {
        synchronized(this) {
            val next = transform(_state.value)
            if (next == _state.value) return
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
    }
}

internal data class KitchenClockReading(val epochMs: Long, val elapsedMs: Long = 0, val bootCount: Int? = null)

@Serializable
data class RecipeTimerState(
    val durationSeconds: Int = 300,
    val running: Boolean = false,
    val deadlineEpochMs: Long? = null,
    val pausedRemainingSeconds: Int? = null,
    val pausedRemainingMillis: Long? = null,
    val recipeName: String? = null,
    val dietaryGoal: DietaryGoal? = null,
    val deadlineElapsedMs: Long? = null,
    val bootCount: Int? = null,
    val runId: String? = null,
) {
    enum class Phase { READY, RUNNING, PAUSED, FINISHED }

    internal val alarmKey: String? get() = deadlineEpochMs?.let { runId ?: it.toString() }

    internal fun elapsedDeadline(now: KitchenClockReading): Long? =
        deadlineElapsedMs.takeIf { bootCount != null && bootCount == now.bootCount }

    private fun remainingMillis(now: KitchenClockReading): Long = (
        if (running && deadlineEpochMs != null) {
            elapsedDeadline(now)?.minus(now.elapsedMs) ?: (deadlineEpochMs - now.epochMs)
        } else {
            pausedRemainingMillis ?: (pausedRemainingSeconds ?: durationSeconds) * 1000L
        }
    ).coerceAtLeast(0)

    internal fun remainingSeconds(now: KitchenClockReading): Int = ((remainingMillis(now) + 999L) / 1000L).toInt()

    internal fun phase(now: KitchenClockReading): Phase = when {
        remainingMillis(now) == 0L -> Phase.FINISHED
        running && deadlineEpochMs != null -> Phase.RUNNING
        pausedRemainingMillis != null -> Phase.PAUSED
        // Older builds also used a full paused duration when setting the timer.
        pausedRemainingSeconds != null && pausedRemainingSeconds != durationSeconds -> Phase.PAUSED
        else -> Phase.READY
    }

    internal fun start(now: KitchenClockReading, recipeName: String, dietaryGoal: DietaryGoal? = null): RecipeTimerState {
        val resuming = phase(now) == Phase.PAUSED
        val interval = if (resuming) remainingMillis(now)
        else durationSeconds * 1000L
        return RecipeTimerState(
            durationSeconds = durationSeconds,
            running = true,
            deadlineEpochMs = now.epochMs + interval,
            recipeName = recipeName,
            dietaryGoal = if (resuming) this.dietaryGoal else dietaryGoal,
            deadlineElapsedMs = now.bootCount?.let { now.elapsedMs + interval },
            bootCount = now.bootCount,
            runId = UUID.randomUUID().toString(),
        )
    }

    internal fun pause(now: KitchenClockReading): RecipeTimerState = copy(
        running = false,
        deadlineEpochMs = null,
        pausedRemainingSeconds = null,
        pausedRemainingMillis = remainingMillis(now),
        deadlineElapsedMs = null,
        bootCount = null,
        runId = null,
    )

    internal fun restoreClock(now: KitchenClockReading, wallClockChanged: Boolean): RecipeTimerState {
        if (phase(now) != Phase.RUNNING || now.bootCount == null) return this
        val remaining = remainingMillis(now)
        if (elapsedDeadline(now) == null) {
            return copy(deadlineElapsedMs = now.elapsedMs + remaining, bootCount = now.bootCount)
        }
        return if (wallClockChanged) copy(deadlineEpochMs = now.epochMs + remaining) else this
    }

    companion object {
        val DURATION_MINUTES = 1..180
    }
}

@Serializable
data class RecipeWorkbenchEntry(
    val recipeId: Int,
    val servings: Int = 1,
    val checkedIngredients: Set<String> = emptySet(),
    val checkedSteps: Set<String> = emptySet(),
    val timer: RecipeTimerState = RecipeTimerState(),
)

@Serializable
data class WorkbenchSnapshot(
    val entries: Map<Int, RecipeWorkbenchEntry> = emptyMap(),
)

class KitchenWorkbenchStore(
    context: Context? = null,
    explicitFile: File? = null,
) {
    private val file = explicitFile
        ?: File(requireNotNull(context) { "Context or explicitFile required" }.applicationContext.filesDir, "kitchen-workbench-v1.json")
    private val _state = MutableStateFlow(AtomicJsonFiles.readOrDefault(file) { WorkbenchSnapshot() })
    val state: StateFlow<WorkbenchSnapshot> = _state.asStateFlow()

    fun entry(recipeId: Int): RecipeWorkbenchEntry =
        _state.value.entries[recipeId] ?: RecipeWorkbenchEntry(recipeId = recipeId)

    fun toggleIngredient(recipeId: Int, ingredientLine: String, previousKeys: Set<String> = emptySet()) {
        updateEntry(recipeId) { entry ->
            val keys = previousKeys + ingredientLine
            val next = if (keys.any { it in entry.checkedIngredients }) {
                entry.checkedIngredients - keys
            } else {
                entry.checkedIngredients + ingredientLine
            }
            entry.copy(checkedIngredients = next)
        }
    }

    fun toggleStep(recipeId: Int, stepId: String) {
        updateEntry(recipeId) { entry ->
            val next = if (stepId in entry.checkedSteps) {
                entry.checkedSteps - stepId
            } else {
                entry.checkedSteps + stepId
            }
            entry.copy(checkedSteps = next)
        }
    }

    /**
     * 修改份数时只取消用量实际发生变化的原料与步骤勾选，其余勾选保持保留。
     */
    fun updateServings(
        recipeId: Int,
        newServings: Int,
        presentation: RecipePresentation?,
    ) {
        val clamped = newServings.coerceIn(RecipeAmounts.SERVING_RANGE.first, RecipeAmounts.SERVING_RANGE.last)
        updateEntry(recipeId) { current ->
            if (current.servings == clamped) return@updateEntry current
            if (presentation == null) {
                return@updateEntry current.copy(servings = clamped)
            }
            val quantities = presentation.allQuantities
            val oldAmounts = RecipeAmounts(quantities, current.servings)
            val newAmounts = RecipeAmounts(quantities, clamped)
            val oldPlan = RecipeStepUsagePlan(presentation.steps, oldAmounts, presentation.amountAnnotations)
            val newPlan = RecipeStepUsagePlan(presentation.steps, newAmounts, presentation.amountAnnotations)
            val retainedIngredients = current.checkedIngredients.filter { key ->
                val qty = if (key.startsWith("catalog:")) {
                    quantities.preparationQuantity(key.removePrefix("catalog:"))
                } else quantities.firstOrNull { it.preparationKey == key }
                if (qty == null) return@filter true
                oldAmounts.amount(qty).text == newAmounts.amount(qty).text
            }.toSet()
            val retainedSteps = current.checkedSteps.filter { stepId ->
                val step = presentation.steps.firstOrNull { it.id == stepId } ?: return@filter true
                oldAmounts.instruction(step.text).text == newAmounts.instruction(step.text).text &&
                    oldPlan.instruction(step)?.text == newPlan.instruction(step)?.text
            }.toSet()
            current.copy(
                servings = clamped,
                checkedIngredients = retainedIngredients,
                checkedSteps = retainedSteps,
            )
        }
    }

    fun updateTimer(recipeId: Int, timer: RecipeTimerState) {
        updateEntry(recipeId) { it.copy(timer = timer) }
    }

    internal fun finishTimer(recipeId: Int, alarmKey: String, now: KitchenClockReading): Boolean =
        updateEntry(recipeId) { entry ->
            val timer = entry.timer
            if (timer.running && timer.alarmKey == alarmKey && timer.phase(now) == RecipeTimerState.Phase.FINISHED) {
                entry.copy(timer = timer.pause(now))
            } else {
                entry
            }
        }

    fun resetRecipeChecks(recipeId: Int) {
        updateEntry(recipeId) { it.copy(checkedIngredients = emptySet(), checkedSteps = emptySet()) }
    }

    fun clearPrepared(recipeId: Int) {
        updateEntry(recipeId) { it.copy(checkedIngredients = emptySet()) }
    }

    fun clearSteps(recipeId: Int) {
        updateEntry(recipeId) { it.copy(checkedSteps = emptySet()) }
    }

    private fun updateEntry(recipeId: Int, transform: (RecipeWorkbenchEntry) -> RecipeWorkbenchEntry): Boolean =
        synchronized(this) {
            val prev = _state.value
            val current = prev.entries[recipeId] ?: RecipeWorkbenchEntry(recipeId = recipeId)
            val nextEntry = transform(current)
            if (nextEntry == current) return@synchronized false
            val next = prev.copy(entries = prev.entries + (recipeId to nextEntry))
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
            true
        }
}

@Serializable
data class PersonalCatalogState(
    val customEntries: List<CatalogEntry> = emptyList(),
    val hiddenEntries: List<CatalogEntry> = emptyList(),
) {
    val hiddenBuiltInNames: Set<String>
        get() = hiddenEntries.mapTo(HashSet()) { it.name }

    fun toDocument(): PersonalCatalog = PersonalCatalog(
        items = customEntries,
        hidden = hiddenEntries,
    )
}

class PersonalCatalogStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "personal-catalog-v1.json")
    private val _state = MutableStateFlow(AtomicJsonFiles.readOrDefault(file) { PersonalCatalogState() })
    val state: StateFlow<PersonalCatalogState> = _state.asStateFlow()

    fun addCustomEntry(name: String, category: CatalogCategory): Result<CatalogEntry> = runCatching {
        val entry = CatalogEntry(category = category, name = name.trim())
        update { prevState ->
            val nextDoc = prevState.toDocument().copy(items = prevState.customEntries + entry)
            nextDoc.validate()
            prevState.copy(customEntries = nextDoc.items)
        }
        entry
    }

    fun removeCustomEntry(id: String) {
        update { prev -> prev.copy(customEntries = prev.customEntries.filterNot { it.id == id }) }
    }

    fun toggleHiddenEntry(category: CatalogCategory, name: String) {
        val target = CatalogEntry(category = category, name = name)
        update { prev ->
            val exists = prev.hiddenEntries.any { it.id == target.id }
            val next = if (exists) {
                prev.hiddenEntries.filterNot { it.id == target.id }
            } else if (prev.hiddenEntries.size < PersonalCatalog.MAX_ENTRIES) {
                prev.hiddenEntries + target
            } else {
                throw PersonalCatalogException.TooLarge()
            }
            prev.copy(hiddenEntries = next)
        }
    }

    fun exportJson(): String = _state.value.toDocument().export()

    internal class ImportChanged : IllegalStateException()

    internal fun restoreBackup(backup: PersonalCatalog, expectedState: PersonalCatalogState? = null) = update { current ->
        if (expectedState != null && current !== expectedState) throw ImportChanged()
        val merged = current.toDocument().merging(backup)
        PersonalCatalogState(customEntries = merged.items, hiddenEntries = merged.hidden)
    }

    fun extraStuffItems(includeTools: Boolean = false): List<StuffItem> =
        _state.value.customEntries
            .filter { includeTools == (it.category == CatalogCategory.TOOLS) }
            .map { StuffItem(name = it.name, icon = "", emoji = it.category.emoji) }

    private fun update(transform: (PersonalCatalogState) -> PersonalCatalogState) {
        synchronized(this) {
            val next = transform(_state.value)
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
    }
}

@Serializable
data class PrivateRecipesState(
    val recipes: List<PrivateRecipe> = emptyList(),
    val deletedIds: Set<String> = emptySet(),
    val nativeV2Json: String = "",
)

class PrivateRecipesStore(
    context: Context? = null,
    explicitFile: File? = null,
) {
    private val file = explicitFile
        ?: File(requireNotNull(context) { "Context or explicitFile required" }.applicationContext.filesDir, "private-recipes-v1.json")
    private val _state = MutableStateFlow(loadInitialState(file))
    val state: StateFlow<PrivateRecipesState> = _state.asStateFlow()

    fun upsertRecipe(recipe: PrivateRecipe): PrivateRecipe? {
        val validated = recipe.validated()
        var replaced: PrivateRecipe? = null
        update { prev ->
            val idx = prev.recipes.indexOfFirst { it.customId == validated.customId }
            val nextList = if (idx >= 0) {
                replaced = prev.recipes[idx]
                prev.recipes.toMutableList().apply { this[idx] = validated }
            } else {
                listOf(validated) + prev.recipes
            }
            prev.copy(recipes = nextList, deletedIds = prev.deletedIds - validated.customId)
        }
        return replaced
    }

    fun deleteRecipe(customId: String): PrivateRecipe? {
        var removed: PrivateRecipe? = null
        update { prev ->
            removed = prev.recipes.firstOrNull { it.customId == customId }
            prev.copy(
                recipes = prev.recipes.filterNot { it.customId == customId },
                deletedIds = prev.deletedIds + customId,
            )
        }
        return removed
    }

    @Synchronized
    internal fun removeUnreferencedVideo(video: PrivateRecipeVideo, videos: PrivateRecipeVideoStore): Boolean {
        val referenced = state.value.recipes.any {
            val active = it.video
            active != null && active.location == video.location && active.deviceID == video.deviceID && active.fileName == video.fileName
        }
        return referenced || videos.remove(video)
    }

    fun exportPortableV1(): String =
        PrivateRecipeFile(recipes = _state.value.recipes).export()

    fun exportBackupV2(): String =
        PrivateRecipeNativeFile.export(recipes = _state.value.recipes, backup = true)

    internal class ImportPreview(
        val plan: PrivateRecipeImport,
        internal val incoming: List<PrivateRecipe>,
        internal val current: PrivateRecipesState,
    )

    internal class ImportChanged : IllegalStateException()

    internal fun previewImport(rawText: String): ImportPreview =
        previewImport(PrivateRecipeNativeFile.parse(rawText))

    internal fun previewImport(incoming: List<PrivateRecipe>): ImportPreview {
        val current = _state.value
        return ImportPreview(
            plan = PrivateRecipeImport(current.recipes, incoming, current.deletedIds),
            incoming = incoming,
            current = current,
        )
    }

    @Synchronized
    internal fun commitImport(preview: ImportPreview, keepCopies: Boolean): Int {
        val current = _state.value
        if (current !== preview.current) throw ImportChanged()
        val inserts = preview.plan.recordsToInsert(
            keepCopies = keepCopies,
            currentIds = current.recipes.map { it.customId }.toSet(),
        )
        if (inserts.isEmpty()) return 0
        val nextRecipes = inserts + current.recipes
        val next = current.copy(
            recipes = nextRecipes,
            deletedIds = current.deletedIds - inserts.map { it.customId }.toSet(),
            nativeV2Json = PrivateRecipeNativeFile.export(nextRecipes, backup = true),
        )
        AtomicJsonFiles.writeAtomic(file, next)
        _state.value = next
        return inserts.size
    }

    internal fun restoreBackup(backup: BackupPrivateRecipes) = update { current ->
        val activeIds = current.recipes.mapTo(HashSet()) { it.customId }
        val deletedIds = (current.deletedIds + backup.deletedIds) - activeIds
        val additions = PrivateRecipeImport(current.recipes, backup.recipes, deletedIds)
            .recordsToInsert(keepCopies = false, currentIds = activeIds)
        current.copy(recipes = additions + current.recipes, deletedIds = deletedIds)
    }

    private fun update(transform: (PrivateRecipesState) -> PrivateRecipesState) {
        synchronized(this) {
            val transformed = transform(_state.value)
            val next = transformed.copy(
                nativeV2Json = PrivateRecipeNativeFile.export(transformed.recipes, backup = true),
            )
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
    }

    companion object {
        private fun loadInitialState(file: File): PrivateRecipesState {
            val raw = AtomicJsonFiles.readOrDefault(file) { PrivateRecipesState() }
            if (raw.nativeV2Json.isNotBlank()) {
                val restored = runCatching { PrivateRecipeNativeFile.parse(raw.nativeV2Json) }.getOrNull()
                if (restored != null) {
                    return raw.copy(recipes = restored)
                }
            }
            return raw
        }
    }
}
