package cn.yunyoujun.cook.data.local

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@Serializable
enum class FridgeDateKind(val label: String) {
    PACKAGE_EXPIRY("包装保质期"),
    ESTIMATED("按常见天数推算"),
    ADVISORY("建议尽早食用"),
    MANUAL("手动修改"),
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
) {
    fun daysRemaining(today: LocalDate = LocalDate.now()): Int? {
        val iso = expiresOnIsoDate ?: return null
        val target = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
        return ChronoUnit.DAYS.between(today, target).toInt()
    }

    companion object {
        const val DEFAULT_CATEGORY_ID = "default-other"
    }
}

@Serializable
data class FridgeDocument(
    val categories: List<FridgeCategory> = defaultCategories(),
    val items: List<FridgeBatchItem> = emptyList(),
) {
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
    ) {
        val trimmed = ingredientName.trim()
        if (trimmed.isEmpty()) return
        val effectiveCategoryId = if (_state.value.categories.any { it.id == categoryId }) {
            categoryId
        } else {
            FridgeBatchItem.DEFAULT_CATEGORY_ID
        }
        val expires = explicitIsoDate ?: LocalDate.now().plusDays(shelfLifeDays.coerceIn(1, 365).toLong()).toString()
        val item = FridgeBatchItem(
            ingredientName = trimmed,
            categoryId = effectiveCategoryId,
            quantityText = quantityText.trim(),
            addedAtMs = System.currentTimeMillis(),
            expiresOnIsoDate = expires,
            dateKind = dateKind,
        )
        update { prev -> prev.copy(items = listOf(item) + prev.items) }
    }

    fun updateItemDate(itemId: String, expiresOnIsoDate: String?, dateKind: FridgeDateKind) {
        update { prev ->
            prev.copy(
                items = prev.items.map {
                    if (it.id == itemId) it.copy(expiresOnIsoDate = expiresOnIsoDate, dateKind = dateKind) else it
                },
            )
        }
    }

    fun removeItem(itemId: String) {
        update { prev -> prev.copy(items = prev.items.filterNot { it.id == itemId }) }
    }

    fun addCategory(name: String) {
        val trimmed = name.trim().take(20)
        if (trimmed.isEmpty()) return
        update { prev ->
            if (prev.categories.any { it.name == trimmed }) return@update prev
            val nextCat = FridgeCategory(
                id = "cat-${UUID.randomUUID().toString().take(8)}",
                name = trimmed,
                isDefault = false,
            )
            prev.copy(categories = prev.categories + nextCat)
        }
    }

    fun renameCategory(categoryId: String, newName: String) {
        val trimmed = newName.trim().take(20)
        if (trimmed.isEmpty()) return
        update { prev ->
            prev.copy(
                categories = prev.categories.map {
                    if (it.id == categoryId) it.copy(name = trimmed) else it
                },
            )
        }
    }

    fun deleteCategory(categoryId: String) {
        if (categoryId == FridgeBatchItem.DEFAULT_CATEGORY_ID) return
        update { prev ->
            val fallbackId = prev.categories.firstOrNull { it.id == FridgeBatchItem.DEFAULT_CATEGORY_ID }?.id
                ?: prev.categories.firstOrNull { it.id != categoryId }?.id
                ?: FridgeBatchItem.DEFAULT_CATEGORY_ID
            prev.copy(
                categories = prev.categories.filterNot { it.id == categoryId },
                items = prev.items.map {
                    if (it.categoryId == categoryId) it.copy(categoryId = fallbackId) else it
                },
            )
        }
    }

    fun dueSoonCount(today: LocalDate = LocalDate.now()): Int =
        _state.value.items.count { item ->
            val days = item.daysRemaining(today) ?: return@count false
            days in -1..2
        }

    fun formatLocalDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun update(transform: (FridgeDocument) -> FridgeDocument) {
        synchronized(this) {
            val next = transform(_state.value)
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
    }
}

@Serializable
data class RecipeTimerState(
    val durationSeconds: Int = 300,
    val running: Boolean = false,
    val deadlineEpochMs: Long? = null,
    val pausedRemainingSeconds: Int? = null,
) {
    fun remainingSeconds(nowEpochMs: Long = System.currentTimeMillis()): Int = when {
        running && deadlineEpochMs != null -> ((deadlineEpochMs - nowEpochMs + 999L) / 1000L).toInt().coerceAtLeast(0)
        pausedRemainingSeconds != null -> pausedRemainingSeconds.coerceAtLeast(0)
        else -> durationSeconds
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

    fun toggleIngredient(recipeId: Int, ingredientLine: String) {
        updateEntry(recipeId) { entry ->
            val next = if (ingredientLine in entry.checkedIngredients) {
                entry.checkedIngredients - ingredientLine
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
            if (presentation == null || presentation.allQuantities.isEmpty()) {
                return@updateEntry current.copy(servings = clamped)
            }
            val oldAmounts = RecipeAmounts(presentation.allQuantities, current.servings)
            val newAmounts = RecipeAmounts(presentation.allQuantities, clamped)
            val oldPlan = RecipeStepUsagePlan(presentation.steps, oldAmounts, presentation.amountAnnotations)
            val newPlan = RecipeStepUsagePlan(presentation.steps, newAmounts, presentation.amountAnnotations)
            val retainedIngredients = current.checkedIngredients.filter { key ->
                val qty = presentation.allQuantities.firstOrNull { "${it.name}=${it.amount}" == key }
                    ?: return@filter true
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

    fun resetRecipeChecks(recipeId: Int) {
        updateEntry(recipeId) { it.copy(checkedIngredients = emptySet(), checkedSteps = emptySet()) }
    }

    private fun updateEntry(recipeId: Int, transform: (RecipeWorkbenchEntry) -> RecipeWorkbenchEntry) {
        synchronized(this) {
            val prev = _state.value
            val current = prev.entries[recipeId] ?: RecipeWorkbenchEntry(recipeId = recipeId)
            val nextEntry = transform(current)
            val next = prev.copy(entries = prev.entries + (recipeId to nextEntry))
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
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
        val prev = _state.value
        val candidate = prev.toDocument().copy(
            items = if (prev.customEntries.any { it.id == entry.id }) {
                throw PersonalCatalogException.Duplicate()
            } else {
                prev.customEntries + entry
            },
        )
        candidate.validate()
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
                prev.hiddenEntries
            }
            prev.copy(hiddenEntries = next)
        }
    }

    fun exportJson(): String = _state.value.toDocument().export()

    fun importJson(rawText: String): PersonalCatalog {
        val incoming = PersonalCatalog.parse(rawText)
        synchronized(this) {
            val merged = _state.value.toDocument().merging(incoming)
            val next = PersonalCatalogState(
                customEntries = merged.items,
                hiddenEntries = merged.hidden,
            )
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
        }
        return incoming
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

    fun upsertRecipe(recipe: PrivateRecipe) {
        val validated = recipe.validated()
        update { prev ->
            val idx = prev.recipes.indexOfFirst { it.customId == validated.customId }
            val nextList = if (idx >= 0) {
                prev.recipes.toMutableList().apply { this[idx] = validated }
            } else {
                listOf(validated) + prev.recipes
            }
            prev.copy(recipes = nextList, deletedIds = prev.deletedIds - validated.customId)
        }
    }

    fun deleteRecipe(customId: String) {
        update { prev ->
            prev.copy(
                recipes = prev.recipes.filterNot { it.customId == customId },
                deletedIds = prev.deletedIds + customId,
            )
        }
    }

    fun exportPortableV1(): String =
        PrivateRecipeFile(recipes = _state.value.recipes).export()

    fun exportBackupV2(): String =
        PrivateRecipeNativeFile.export(recipes = _state.value.recipes, backup = true)

    fun previewImport(rawText: String): PrivateRecipeImport {
        val incoming = PrivateRecipeNativeFile.parse(rawText)
        return PrivateRecipeImport(
            current = _state.value.recipes,
            incoming = incoming,
            deletedIds = _state.value.deletedIds,
        )
    }

    fun commitImport(rawText: String, keepCopies: Boolean): Int {
        val incoming = PrivateRecipeNativeFile.parse(rawText)
        synchronized(this) {
            val prev = _state.value
            val plan = PrivateRecipeImport(
                current = prev.recipes,
                incoming = incoming,
                deletedIds = prev.deletedIds,
            )
            val inserts = plan.recordsToInsert(
                keepCopies = keepCopies,
                currentIds = prev.recipes.map { it.customId }.toSet(),
            )
            val nextRecipes = inserts + prev.recipes
            val next = prev.copy(
                recipes = nextRecipes,
                deletedIds = prev.deletedIds - inserts.map { it.customId }.toSet(),
                nativeV2Json = PrivateRecipeNativeFile.export(nextRecipes, backup = true),
            )
            AtomicJsonFiles.writeAtomic(file, next)
            _state.value = next
            return inserts.size
        }
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
