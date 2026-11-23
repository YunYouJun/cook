package cn.yunyoujun.cook.data.repository

import androidx.room.withTransaction
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.core.sync.UserDataExportFile
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.DeviceFavoriteEntity
import cn.yunyoujun.cook.data.local.DeviceFavoriteRemovalEntity
import cn.yunyoujun.cook.data.local.DeviceHistoryEntity
import cn.yunyoujun.cook.data.local.DeviceHistoryRemovalEntity
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserLibraryScope
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicLong

data class FavoriteRecord(
    val recipeId: Int,
    val addedAtMs: Double,
)

data class HistoryRecord(
    val snapshot: UserDataDocument.RecipeSnapshot,
    val visitedAtMs: Double,
)

internal data class UserLibraryEntries<T>(val scope: UserLibraryScope, val entries: List<T>)

@OptIn(ExperimentalCoroutinesApi::class)
class UserLibraryRepository(
    private val database: CookDatabase,
    initialSessionManager: YunlefunSessionManager? = null,
) {
    private val deviceDao = database.deviceLibraryDao()
    private val accountDao = database.accountLibraryDao()
    private val sessionRevision = AtomicLong(1L)
    private val sessionManagerFlow = MutableStateFlow(initialSessionManager)
    @Volatile
    internal var beforeImportCommitHook: (suspend () -> Unit)? = null

    fun bindSessionManager(manager: YunlefunSessionManager) {
        sessionManagerFlow.value = manager
    }

    fun currentRevision(): Long = sessionRevision.get()

    fun bumpSessionRevision(): Long = sessionRevision.incrementAndGet()

    internal fun observeFavorites(): Flow<UserLibraryEntries<FavoriteRecord>?> = observeLibraryEntries(
        device = deviceDao.observeFavorites().map { list ->
            list.map { FavoriteRecord(recipeId = it.recipeId, addedAtMs = it.addedAtMs) }
        },
        account = { document ->
            document.favorites
                .sortedWith(compareByDescending<UserDataDocument.Favorite> { it.time }.thenBy { it.id })
                .map { FavoriteRecord(it.id, it.time) }
        },
    )

    internal fun observeHistories(): Flow<UserLibraryEntries<HistoryRecord>?> = observeLibraryEntries(
        device = deviceDao.observeHistories().map { list ->
            list.mapNotNull { entity ->
                runCatching {
                    HistoryRecord(
                        snapshot = UserDataDocument.wireJson.decodeFromString<UserDataDocument.RecipeSnapshot>(entity.snapshotJson),
                        visitedAtMs = entity.visitedAtMs,
                    )
                }.getOrNull()
            }
        },
        account = { document ->
            document.histories
                .sortedWith(compareByDescending<UserDataDocument.Visit> { it.time }.thenBy { it.recipe.name })
                .map { HistoryRecord(it.recipe, it.time) }
        },
    )

    private fun <T> observeLibraryEntries(
        device: Flow<List<T>>,
        account: (UserDataDocument) -> List<T>,
    ): Flow<UserLibraryEntries<T>?> = sessionManagerFlow.flatMapLatest { manager ->
        val source = manager?.state?.map { state ->
            if (!state.isHydrated) null
            else state.libraryScope to state.accountDocument.takeIf { state.usesAccountLibrary }
        } ?: flowOf(UserLibraryScope() to null)
        source.distinctUntilChanged().flatMapLatest { selected ->
            if (selected == null) flowOf(null)
            else {
                val (scope, document) = selected
                if (document == null) device.map { UserLibraryEntries(scope, it) }
                else flowOf(UserLibraryEntries(scope, account(document)))
            }
        }
    }

    private suspend fun <T> useLibrary(
        expected: UserLibraryScope?,
        allowInitialHydration: Boolean = false,
        account: suspend (YunlefunSessionManager, UserLibraryScope) -> T,
        device: suspend () -> T,
    ): T {
        val manager = sessionManagerFlow.value
        manager?.awaitInitialHydration()
        val state = manager?.state?.value
        if (state?.isHydrated == false) throw UserDataSyncException.LibraryUnreadable()
        val scope = state?.libraryScope ?: UserLibraryScope()
        val target = if (allowInitialHydration && expected?.isHydrated == false) {
            // The saved consent becomes available after startup. Keep the session and consent revision pinned.
            expected.copy(usesAccountLibrary = scope.usesAccountLibrary, isHydrated = true)
        } else expected
        if (target != null && scope != target) throw UserDataSyncException.LibraryChanged()
        return if (manager != null && scope.usesAccountLibrary) account(manager, scope) else device()
    }

    internal suspend fun toggleFavorite(recipeId: Int, currentlyFavorite: Boolean, expected: UserLibraryScope? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                useLibrary(expected, account = { manager, scope ->
                    manager.toggleAccountFavorite(scope, recipeId, currentlyFavorite)
                }) {
                    database.withTransaction {
                        val nowMs = System.currentTimeMillis().toDouble()
                        val latest = deviceDao.latestFavoriteChangeTime(recipeId) ?: 0.0
                        if (currentlyFavorite) {
                            deviceDao.removeFavoriteTransactional(
                                recipeId = recipeId,
                                removedAtMs = maxOf(nowMs, latest),
                                maxRemovals = UserDataDocument.MAX_REMOVALS,
                            )
                        } else {
                            val existingFavorites = deviceDao.getAllFavorites()
                            if (existingFavorites.none { it.recipeId == recipeId } && existingFavorites.size >= UserDataDocument.MAX_FAVORITES) {
                                throw UserDataDocument.InvalidDocumentException("Favorites limit exceeded")
                            }
                            val time = maxOf(nowMs, latest + 1, Math.nextUp(latest))
                            if (!time.isFinite()) throw UserDataDocument.InvalidDocumentException("Favorite timestamp overflow")
                            deviceDao.addFavoriteTransactional(recipeId, addedAtMs = time)
                        }
                        !currentlyFavorite
                    }
                }
            }
        }

    suspend fun recordVisit(recipe: Recipe): Result<Unit> =
        recordVisit(recipe, sessionManagerFlow.value?.state?.value?.libraryScope ?: UserLibraryScope())

    internal suspend fun recordVisit(recipe: Recipe, expected: UserLibraryScope): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                useLibrary(expected, allowInitialHydration = true, account = { manager, scope ->
                    manager.recordAccountVisit(scope, recipe)
                }) {
                    val snapshotJson = UserDataDocument.wireJson.encodeToString(UserDataDocument.RecipeSnapshot(recipe))
                    database.withTransaction {
                        val latest = maxOf(
                            deviceHistoryClearedAt(),
                            deviceDao.latestVisitTime(recipe.name) ?: 0.0,
                            deviceDao.latestRemovedVisitTime(recipe.name) ?: 0.0,
                        )
                        val time = maxOf(System.currentTimeMillis().toDouble(), latest + 1, Math.nextUp(latest))
                        if (!time.isFinite()) throw UserDataDocument.InvalidDocumentException("Visit timestamp overflow")
                        deviceDao.recordVisitTransactional(
                            entity = DeviceHistoryEntity(recipe.name, time, snapshotJson),
                            maxHistories = UserDataDocument.MAX_HISTORIES,
                        )
                    }
                }
            }
        }

    internal suspend fun removeHistory(recipeName: String, visitedAtMs: Double, expected: UserLibraryScope? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                useLibrary(expected, account = { manager, scope ->
                    manager.removeAccountHistory(scope, recipeName, visitedAtMs)
                }) {
                    deviceDao.removeHistoryTransactional(recipeName, visitedAtMs)
                }
            }
        }

    internal suspend fun clearAllHistory(expected: UserLibraryScope? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                useLibrary(expected, account = { manager, scope ->
                    manager.clearAccountHistory(scope)
                }) {
                    database.withTransaction {
                        val nowMs = System.currentTimeMillis().toDouble()
                        val clearedAt = maxOf(nowMs, deviceHistoryClearedAt())
                        deviceDao.saveHistoryRemovalsAfter(clearedAt)
                        deviceDao.clearAllHistories()
                        deviceDao.clearHistoryRemovalsThrough(clearedAt)
                        accountDao.upsertSyncState(
                            AccountSyncStateEntity(
                                accountUid = DEVICE_META_UID,
                                syncEnabled = false,
                                historyClearedAtMs = clearedAt,
                                lastSyncedDocumentJson = "",
                                updatedAtMs = nowMs,
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun deviceHistoryClearedAt(): Double =
        accountDao.getSyncState(DEVICE_META_UID)?.historyClearedAtMs ?: 0.0

    private suspend fun buildDeviceDocument(requireReadableHistory: Boolean = false): UserDataDocument {
        val favs = deviceDao.getAllFavorites().map {
            UserDataDocument.Favorite(id = it.recipeId, time = it.addedAtMs)
        }
        val favRemovals = deviceDao.getAllFavoriteRemovals().map {
            UserDataDocument.Favorite(id = it.recipeId, time = it.removedAtMs)
        }.take(UserDataDocument.MAX_REMOVALS)
        val histories = deviceDao.getAllHistories().mapNotNull { entity ->
            runCatching {
                UserDataDocument.Visit(
                    recipe = UserDataDocument.wireJson.decodeFromString<UserDataDocument.RecipeSnapshot>(entity.snapshotJson),
                    time = entity.visitedAtMs,
                )
            }.getOrElse { error ->
                if (requireReadableHistory) throw error
                null
            }
        }.take(UserDataDocument.MAX_HISTORIES)
        val historyRemovals = deviceDao.getAllHistoryRemovals().map {
            UserDataDocument.Removal(name = it.recipeName, time = it.removedAtMs)
        }.take(UserDataDocument.MAX_REMOVALS)
        return UserDataDocument(
            version = UserDataDocument.SUPPORTED_VERSION,
            favorites = favs,
            favoriteRemovals = favRemovals,
            histories = histories,
            historyRemovals = historyRemovals,
            historyClearedAt = deviceHistoryClearedAt(),
        )
    }

    internal suspend fun snapshotDeviceLibrary(): UserDataDocument =
        database.withTransaction { buildDeviceDocument(requireReadableHistory = true).also { it.validate() } }

    internal suspend fun restoreDeviceLibrary(incoming: UserDataDocument): Pair<Int, Int> =
        mergeIntoDeviceLibrary(incoming, deviceBackup = true)

    internal suspend fun exportUserDataDocumentJson(expected: UserLibraryScope? = null): String =
        withContext(Dispatchers.IO) {
            val doc = useLibrary(expected, account = { manager, scope ->
                val current = manager.state.value
                if (current.libraryScope != scope) throw UserDataSyncException.LibraryChanged()
                current.accountDocument
            }) {
                database.withTransaction { buildDeviceDocument() }
            }
            UserDataExportFile.export(doc)
        }

    /**
     * 合并经过源文件和目标库删除记录筛选的收藏与历史。Room 事务保证本机导入原子提交。
     */
    internal suspend fun importUserDataDocumentJson(
        rawJson: String,
        expected: UserLibraryScope? = null,
    ): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val incoming = UserDataExportFile.parse(rawJson)
            useLibrary(expected, account = { manager, scope -> manager.importIntoAccount(scope, incoming) }) {
                mergeIntoDeviceLibrary(incoming, deviceBackup = false)
            }
        }
    }

    private suspend fun mergeIntoDeviceLibrary(incoming: UserDataDocument, deviceBackup: Boolean): Pair<Int, Int> =
        database.withTransaction {
            val currentDoc = buildDeviceDocument(requireReadableHistory = deviceBackup)
            val combined = when {
                !deviceBackup -> currentDoc.importMerging(incoming)
                currentDoc == UserDataDocument() -> incoming
                else -> currentDoc.restoreMerging(incoming)
            }
            if (deviceBackup) {
                require(combined.favorites.size <= UserDataDocument.MAX_FAVORITES && combined.histories.size <= UserDataDocument.MAX_HISTORIES) {
                    "合并后超出本机收藏或历史容量。"
                }
            }
            combined.validate()

            val favoriteEntities = combined.favorites.map {
                DeviceFavoriteEntity(recipeId = it.id, addedAtMs = it.time)
            }
            val favoriteRemovalEntities = combined.favoriteRemovals.map {
                DeviceFavoriteRemovalEntity(recipeId = it.id, removedAtMs = it.time)
            }
            val historyEntities = combined.histories.map { entry ->
                DeviceHistoryEntity(
                    recipeName = entry.recipe.name,
                    visitedAtMs = entry.time,
                    snapshotJson = UserDataDocument.wireJson.encodeToString(entry.recipe),
                )
            }
            val historyRemovalEntities = combined.historyRemovals.map {
                DeviceHistoryRemovalEntity(recipeName = it.name, removedAtMs = it.time)
            }

            deviceDao.replaceDeviceLibraryTransactional(
                favorites = favoriteEntities,
                favoriteRemovals = favoriteRemovalEntities,
                histories = historyEntities,
                historyRemovals = historyRemovalEntities,
            )
            if (combined.historyClearedAt > 0.0) {
                accountDao.upsertSyncState(
                    AccountSyncStateEntity(
                        accountUid = DEVICE_META_UID,
                        syncEnabled = false,
                        historyClearedAtMs = combined.historyClearedAt,
                        lastSyncedDocumentJson = "",
                        updatedAtMs = System.currentTimeMillis().toDouble(),
                    ),
                )
            }
            beforeImportCommitHook?.invoke()
            Pair(combined.favorites.size, combined.histories.size)
        }

    companion object {
        private const val DEVICE_META_UID = "__device_meta__"
    }
}
