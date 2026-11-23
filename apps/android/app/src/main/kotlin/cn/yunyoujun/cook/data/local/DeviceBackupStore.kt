package cn.yunyoujun.cook.data.local

import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.sync.PersonalCatalog
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.repository.UserLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

private const val MAX_TIMESTAMP = 253_402_300_799_999L

internal enum class DeviceBackupSection(val title: String) {
    LIBRARY("本机收藏和浏览历史"),
    PREFERENCES("做菜偏好"),
    FRIDGE("冰箱和分类"),
    PRIVATE_RECIPES("私房菜"),
    PERSONAL_CATALOG("个人食材与厨具词典"),
    JOURNAL("做菜足迹"),
}

@Serializable
internal data class BackupPreferences(
    val pantry: List<String>,
    val pantryInitialized: Boolean,
    val avoid: List<String>,
    val noSpicy: Boolean,
    val mode: RecommendationMode,
    val dietaryGoal: DietaryGoal?,
    val completedDietaryIntroduction: Boolean,
    val themeMode: AppThemeMode,
    val cloudKitchen: Boolean,
    val cloudKitchenRotation: Boolean,
) {
    constructor(snapshot: DevicePreferencesSnapshot) : this(
        snapshot.pantry, snapshot.pantryInitialized, snapshot.avoid, snapshot.noSpicy,
        snapshot.mode, snapshot.rememberedDietaryGoal, snapshot.hasCompletedDietaryIntroduction,
        snapshot.themeMode, snapshot.cloudKitchen, snapshot.cloudKitchenRotation,
    )

    fun applyingTo(current: DevicePreferencesSnapshot): DevicePreferencesSnapshot =
        current.copy(
            pantry = pantry,
            pantryInitialized = pantryInitialized,
            avoid = avoid,
            noSpicy = noSpicy,
            mode = mode,
            rememberedDietaryGoal = dietaryGoal,
            hasCompletedDietaryIntroduction = completedDietaryIntroduction,
            themeMode = themeMode,
            cloudKitchen = cloudKitchen,
            cloudKitchenRotation = cloudKitchenRotation,
        )

    fun validate() {
        require(pantry.all { it.isNotBlank() } && pantry.toSet().size == pantry.size)
        require(avoid.all { it.isNotBlank() } && avoid.toSet().size == avoid.size)
        require(pantryInitialized || pantry.isEmpty())
    }
}

@Serializable
internal data class BackupPrivateRecipes(val recipes: List<PrivateRecipe>, val deletedIds: Set<String>) {
    fun validate() {
        val activeIds = recipes.mapTo(HashSet()) { it.customId }
        require(recipes.size <= PrivateRecipeFile.RECIPE_LIMIT && activeIds.size == recipes.size)
        require(deletedIds.all(PrivateRecipeFile::validId) && deletedIds.none { it in activeIds })
        recipes.forEach { it.validated() }
    }
}

@Serializable
internal data class DeviceBackupDocument(
    val format: String,
    val version: Int,
    val exportedAtMs: Long,
    val library: UserDataDocument,
    val preferences: BackupPreferences,
    val fridge: FridgeDocument,
    val privateRecipes: BackupPrivateRecipes,
    val personalCatalog: PersonalCatalog,
    val journal: CookingJournal,
) {
    fun detail(section: DeviceBackupSection): String = when (section) {
        DeviceBackupSection.LIBRARY -> "${library.favorites.size} 条收藏 · ${library.histories.size} 条历史"
        DeviceBackupSection.PREFERENCES -> "常备、忌口、饮食目标、外观和云厨房设置"
        DeviceBackupSection.FRIDGE -> "${fridge.items.size} 个批次 · ${fridge.categories.size} 个分类"
        DeviceBackupSection.PRIVATE_RECIPES -> "${privateRecipes.recipes.size} 道菜谱"
        DeviceBackupSection.PERSONAL_CATALOG -> "${personalCatalog.items.size} 项自定义 · ${personalCatalog.hidden.size} 项隐藏"
        DeviceBackupSection.JOURNAL -> "${journal.cooked.size} 条做过 · ${journal.learned.size} 道学会"
    }

    internal fun validate() {
        require(format == FORMAT && version == 1 && exportedAtMs in 0..MAX_TIMESTAMP)
        library.validate()
        preferences.validate()
        privateRecipes.validate()
        personalCatalog.validate()

        val categories = fridge.categories.mapTo(HashSet()) { it.id }
        require(FridgeBatchItem.DEFAULT_CATEGORY_ID in categories && categories.size == fridge.categories.size)
        require(fridge.categories.map { it.name }.toSet().size == fridge.categories.size)
        require(fridge.categories.all { PrivateRecipeFile.validId(it.id) && it.name.isNotBlank() && it.name.length <= 20 })
        require(fridge.items.map { it.id }.toSet().size == fridge.items.size)
        require(fridge.items.all {
            PrivateRecipeFile.validId(it.id) && it.ingredientName.isNotBlank() && it.categoryId in categories &&
                it.addedAtMs in 0..MAX_TIMESTAMP && (it.consumedAtMs == null || it.consumedAtMs in 0..MAX_TIMESTAMP) &&
                validDate(it.expiresOnIsoDate) && validDate(it.productionIsoDate) && validDate(it.storageStart?.isoDate)
        })

        val records = journal.cooked + journal.learned
        require(records.map { it.id }.toSet().size == records.size)
        require(journal.learned.map { it.recipeId }.toSet().size == journal.learned.size)
        require(records.all {
            PrivateRecipeFile.validId(it.id) && it.recipeName.isNotBlank() && it.dateMs in 0..MAX_TIMESTAMP &&
                when (val recipe = it.recipeId) {
                    is JournalRecipeId.Catalog -> recipe.id >= 0
                    is JournalRecipeId.Personal -> PrivateRecipeFile.validId(recipe.customId)
                }
        })
    }

    private fun validDate(value: String?): Boolean =
        value == null || runCatching { LocalDate.parse(value).let { it.year in 1..9999 && it.toString() == value } }
            .getOrDefault(false)

    companion object {
        const val FORMAT = "cook.android.device-backup"
    }
}

@Serializable
internal data class DeviceBackupPreview(
    val document: DeviceBackupDocument,
    val videos: Map<String, PrivateRecipeVideo> = emptyMap(),
) {
    fun detail(section: DeviceBackupSection): String =
        if (section == DeviceBackupSection.PRIVATE_RECIPES && videos.isNotEmpty()) {
            "${document.detail(section)} · ${attachments().size} 个视频"
        } else document.detail(section)

    internal fun attachments(): List<PrivateRecipeVideo> =
        videos.values.distinctBy { it.fileName }.sortedBy { it.fileName }

    internal fun validate() {
        document.validate()
        val recipeIds = document.privateRecipes.recipes.mapTo(HashSet()) { it.customId }
        require(recipeIds.containsAll(videos.keys))
        require(videos.values.all { it.validated().location == PrivateRecipeVideo.Location.LOCAL })
        require(videos.values.distinct().size == attachments().size)
    }
}

internal class DeviceBackupStore(
    private val library: UserLibraryRepository,
    private val preferences: DevicePreferencesStore,
    private val fridge: FridgeStore,
    private val privateRecipes: PrivateRecipesStore,
    private val personalCatalog: PersonalCatalogStore,
    private val journal: CookingJournalStore,
    private val videos: PrivateRecipeVideoStore,
) {
    suspend fun export(): ByteArray = withContext(Dispatchers.IO) {
        encode(json.encodeToString(capture(includeVideos = false).document))
    }

    suspend fun writeArchive(output: OutputStream) = withContext(Dispatchers.IO) {
        val preview = capture(includeVideos = true)
        val manifest = encode(json.encodeToString(preview))
        ZipOutputStream(output).use { archive ->
            archive.setLevel(Deflater.NO_COMPRESSION)
            archive.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            archive.write(manifest)
            archive.closeEntry()
            for (video in preview.attachments()) {
                archive.putNextEntry(ZipEntry(attachmentEntry(video)))
                videos.writeVideo(video, archive)
                archive.closeEntry()
            }
        }
    }

    private suspend fun capture(includeVideos: Boolean): DeviceBackupPreview {
        check(!journal.loadFailed) { "做菜足迹暂时无法读取。" }
        val customRecipes = privateRecipes.state.value
        val document = DeviceBackupDocument(
            format = DeviceBackupDocument.FORMAT,
            version = 1,
            exportedAtMs = System.currentTimeMillis(),
            library = library.snapshotDeviceLibrary(),
            preferences = BackupPreferences(preferences.current),
            fridge = fridge.state.value,
            privateRecipes = BackupPrivateRecipes(customRecipes.recipes.map { it.copy(video = null) }, customRecipes.deletedIds),
            personalCatalog = personalCatalog.state.value.toDocument(),
            journal = journal.state.value,
        )
        val attachments = if (includeVideos) {
            buildMap {
                for (recipe in customRecipes.recipes) {
                    val video = recipe.video ?: continue
                    if (video.location == PrivateRecipeVideo.Location.LOCAL && video.deviceID == videos.deviceId) {
                        put(recipe.customId, video)
                    }
                }
            }
        } else emptyMap()
        return DeviceBackupPreview(document, attachments).also { it.validate() }
    }

    fun readPreview(input: InputStream): DeviceBackupPreview {
        val buffered = input.buffered()
        buffered.mark(4)
        val isZip = buffered.read() == 0x50 && buffered.read() == 0x4b &&
            buffered.read() == 3 && buffered.read() == 4
        buffered.reset()
        return if (isZip) {
            ZipInputStream(buffered).use(::readManifest)
        } else {
            DeviceBackupPreview(read(buffered))
        }
    }

    fun read(input: InputStream): DeviceBackupDocument =
        json.decodeFromString<DeviceBackupDocument>(readText(input)).also { it.validate() }

    private fun readManifest(archive: ZipInputStream): DeviceBackupPreview {
        require(archive.nextEntry?.name == MANIFEST_ENTRY)
        val preview = json.decodeFromString<DeviceBackupPreview>(readText(archive)).also { it.validate() }
        archive.closeEntry()
        return preview
    }

    suspend fun restorePrivateRecipesWithVideos(preview: DeviceBackupPreview, open: () -> InputStream) =
        withContext(Dispatchers.IO) {
            val drafts = mutableMapOf<String, PrivateVideoDraft>()
            val installed = mutableMapOf<String, PrivateRecipeVideo>()
            try {
                ZipInputStream(open().buffered()).use { archive ->
                    require(readManifest(archive) == preview)
                    for (video in preview.attachments()) {
                        require(archive.nextEntry?.name == attachmentEntry(video))
                        drafts[video.fileName] = videos.stageFromBackup(archive, video)
                        archive.closeEntry()
                    }
                    require(archive.nextEntry == null)
                }
                for ((name, draft) in drafts) {
                    coroutineContext.ensureActive()
                    installed[name] = videos.install(draft)
                }
                val recipes = preview.document.privateRecipes
                val restored = recipes.recipes.map { recipe ->
                    val video = preview.videos[recipe.customId]?.let { installed.getValue(it.fileName) }
                    recipe.copy(video = video)
                }
                coroutineContext.ensureActive()
                privateRecipes.restoreBackup(recipes.copy(recipes = restored))
            } finally {
                drafts.values.forEach(videos::discard)
                installed.values.forEach { privateRecipes.removeUnreferencedVideo(it, videos) }
            }
        }

    private fun attachmentEntry(video: PrivateRecipeVideo): String = "videos/${video.fileName}"

    private fun readText(input: InputStream): String {
        val bytes = BoundedIoStreams.readBytesBounded(input, BYTE_LIMIT)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun encode(text: String): ByteArray = text.encodeToByteArray().also {
        if (it.size > BYTE_LIMIT) throw BoundedIoStreams.TooLargeException(BYTE_LIMIT)
    }

    suspend fun restore(document: DeviceBackupDocument, section: DeviceBackupSection) = withContext(Dispatchers.IO) {
        when (section) {
            DeviceBackupSection.LIBRARY -> library.restoreDeviceLibrary(document.library)
            DeviceBackupSection.PREFERENCES -> preferences.restoreBackup(document.preferences)
            DeviceBackupSection.FRIDGE -> fridge.restoreBackup(document.fridge)
            DeviceBackupSection.PRIVATE_RECIPES -> privateRecipes.restoreBackup(document.privateRecipes)
            DeviceBackupSection.PERSONAL_CATALOG -> personalCatalog.restoreBackup(document.personalCatalog)
            DeviceBackupSection.JOURNAL -> journal.restoreBackup(document.journal)
        }
        Unit
    }

    companion object {
        private const val MANIFEST_ENTRY = "cook-device-backup.json"
        private const val BYTE_LIMIT = 12 * 1024 * 1024
        private val json = Json { encodeDefaults = true }
    }
}
