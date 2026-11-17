package cn.yunyoujun.cook.data.repository

import androidx.room.withTransaction
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.DeviceFavoriteEntity
import cn.yunyoujun.cook.data.local.DeviceFavoriteRemovalEntity
import cn.yunyoujun.cook.data.local.DeviceHistoryEntity
import cn.yunyoujun.cook.data.local.DeviceHistoryRemovalEntity
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun observeDeviceFavoriteIds(): Flow<Set<Int>> =
        observeDeviceFavorites().map { list -> list.map { it.recipeId }.toSet() }

    fun observeDeviceFavorites(): Flow<List<FavoriteRecord>> =
        sessionManagerFlow.flatMapLatest { manager ->
            if (manager == null) {
                observeRawDeviceFavorites()
            } else {
                manager.state.flatMapLatest { state ->
                    if (state.credential != null && state.syncEnabled) {
                        flowOf(
                            state.accountDocument.favorites.map {
                                FavoriteRecord(recipeId = it.id, addedAtMs = it.time)
                            },
                        )
                    } else {
                        observeRawDeviceFavorites()
                    }
                }
            }
        }

    private fun observeRawDeviceFavorites(): Flow<List<FavoriteRecord>> =
        deviceDao.observeFavorites().map { list ->
            list.map { FavoriteRecord(recipeId = it.recipeId, addedAtMs = it.addedAtMs) }
        }

    fun observeDeviceHistories(): Flow<List<HistoryRecord>> =
        sessionManagerFlow.flatMapLatest { manager ->
            if (manager == null) {
                observeRawDeviceHistories()
            } else {
                manager.state.flatMapLatest { state ->
                    if (state.credential != null && state.syncEnabled) {
                        flowOf(
                            state.accountDocument.histories.map {
                                HistoryRecord(snapshot = it.recipe, visitedAtMs = it.time)
                            },
                        )
                    } else {
                        observeRawDeviceHistories()
                    }
                }
            }
        }

    private fun observeRawDeviceHistories(): Flow<List<HistoryRecord>> =
        deviceDao.observeHistories().map { list ->
            list.mapNotNull { entity ->
                runCatching {
                    HistoryRecord(
                        snapshot = UserDataDocument.wireJson.decodeFromString<UserDataDocument.RecipeSnapshot>(entity.snapshotJson),
                        visitedAtMs = entity.visitedAtMs,
                    )
                }.getOrNull()
            }
        }

    suspend fun toggleFavorite(recipeId: Int, currentlyFavorite: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = sessionManagerFlow.value
                manager?.awaitInitialHydration()
                if (manager != null && manager.isAccountLibraryActive) {
                    return@runCatching manager.toggleAccountFavorite(recipeId, currentlyFavorite)
                }
                val nowMs = System.currentTimeMillis().toDouble()
                if (currentlyFavorite) {
                    deviceDao.removeFavoriteTransactional(recipeId = recipeId, removedAtMs = nowMs)
                    false
                } else {
                    deviceDao.addFavoriteTransactional(recipeId = recipeId, addedAtMs = nowMs)
                    true
                }
            }
        }

    suspend fun recordVisit(recipe: Recipe): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = sessionManagerFlow.value
                manager?.awaitInitialHydration()
                if (manager != null && manager.isAccountLibraryActive) {
                    manager.recordAccountVisit(recipe)
                    return@runCatching
                }
                val nowMs = System.currentTimeMillis().toDouble()
                val snapshot = UserDataDocument.RecipeSnapshot(recipe)
                val encoded = UserDataDocument.wireJson.encodeToString(snapshot)
                deviceDao.recordVisitTransactional(
                    entity = DeviceHistoryEntity(
                        recipeName = recipe.name,
                        visitedAtMs = nowMs,
                        snapshotJson = encoded,
                    ),
                    maxHistories = UserDataDocument.MAX_HISTORIES,
                )
            }
        }

    suspend fun removeHistory(recipeName: String, visitedAtMs: Double): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = sessionManagerFlow.value
                manager?.awaitInitialHydration()
                if (manager != null && manager.isAccountLibraryActive) {
                    manager.removeAccountHistory(recipeName, visitedAtMs)
                    return@runCatching
                }
                // 墓碑时间必须保存被删除浏览记录自身的 visitedAtMs，才能在跨端合并中精确消除对应记录
                deviceDao.removeHistoryTransactional(recipeName, visitedAtMs)
            }
        }

    suspend fun clearAllHistory(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = sessionManagerFlow.value
                manager?.awaitInitialHydration()
                if (manager != null && manager.isAccountLibraryActive) {
                    manager.clearAccountHistory()
                    return@runCatching
                }
                val nowMs = System.currentTimeMillis().toDouble()
                database.withTransaction {
                    deviceDao.clearAllHistoryTransactional()
                    accountDao.upsertSyncState(
                        AccountSyncStateEntity(
                            accountUid = DEVICE_META_UID,
                            syncEnabled = false,
                            historyClearedAtMs = nowMs,
                            lastSyncedDocumentJson = "",
                            updatedAtMs = nowMs,
                        ),
                    )
                }
            }
        }

    private suspend fun buildDeviceDocument(): UserDataDocument {
        val favs = deviceDao.getAllFavorites().map {
            UserDataDocument.Favorite(id = it.recipeId, time = it.addedAtMs)
        }
        val favRemovals = deviceDao.getAllFavoriteRemovals().map {
            UserDataDocument.Favorite(id = it.recipeId, time = it.removedAtMs)
        }
        val histories = deviceDao.getAllHistories().mapNotNull { entity ->
            runCatching {
                UserDataDocument.Visit(
                    recipe = UserDataDocument.wireJson.decodeFromString<UserDataDocument.RecipeSnapshot>(entity.snapshotJson),
                    time = entity.visitedAtMs,
                )
            }.getOrNull()
        }.take(UserDataDocument.MAX_HISTORIES)
        val historyRemovals = deviceDao.getAllHistoryRemovals().map {
            UserDataDocument.Removal(name = it.recipeName, time = it.removedAtMs)
        }.take(UserDataDocument.MAX_REMOVALS)
        val clearedAt = accountDao.getSyncState(DEVICE_META_UID)?.historyClearedAtMs ?: 0.0
        return UserDataDocument(
            version = UserDataDocument.SUPPORTED_VERSION,
            favorites = favs,
            favoriteRemovals = favRemovals,
            histories = histories,
            historyRemovals = historyRemovals,
            historyClearedAt = clearedAt,
        )
    }

    suspend fun exportUserDataDocumentJson(): String =
        withContext(Dispatchers.IO) {
            val manager = sessionManagerFlow.value
            manager?.awaitInitialHydration()
            val doc = if (manager != null && manager.isAccountLibraryActive) {
                manager.state.value.accountDocument
            } else {
                buildDeviceDocument()
            }
            doc.validate()
            UserDataDocument.wireJson.encodeToString(doc)
        }

    /**
     * 导入 Web / iOS 导出的 UserDataDocument JSON：
     * 采用非破坏性增量迁移语义 `importMerging`（保留全部现有收藏/历史，绝不执行云端墓碑删除或 200 条截断），
     * 并通过单个 Room `withTransaction` 原子提交；任何步骤失败则回滚，原有记录保持不变。
     */
    suspend fun importUserDataDocumentJson(
        rawJson: String,
    ): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val incoming = UserDataDocument.decode(rawJson)
            val manager = sessionManagerFlow.value
            manager?.awaitInitialHydration()
            if (manager != null && manager.isAccountLibraryActive) {
                return@runCatching manager.importIntoAccount(incoming)
            }
            val currentDoc = buildDeviceDocument()
            val combined = currentDoc.importMerging(incoming)
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

            database.withTransaction {
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
            }
            Pair(combined.favorites.size, combined.histories.size)
        }
    }

    companion object {
        private const val DEVICE_META_UID = "__device_meta__"
    }
}
