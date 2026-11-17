package cn.yunyoujun.cook.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "device_favorites")
data class DeviceFavoriteEntity(
    @PrimaryKey val recipeId: Int,
    val addedAtMs: Double,
)

@Entity(tableName = "device_favorite_removals")
data class DeviceFavoriteRemovalEntity(
    @PrimaryKey val recipeId: Int,
    val removedAtMs: Double,
)

@Entity(
    tableName = "device_histories",
    primaryKeys = ["recipeName", "visitedAtMs"],
)
data class DeviceHistoryEntity(
    val recipeName: String,
    val visitedAtMs: Double,
    val snapshotJson: String,
)

@Entity(
    tableName = "device_history_removals",
    primaryKeys = ["recipeName", "removedAtMs"],
)
data class DeviceHistoryRemovalEntity(
    val recipeName: String,
    val removedAtMs: Double,
)

@Entity(
    tableName = "account_favorites",
    primaryKeys = ["accountUid", "recipeId"],
    indices = [Index(value = ["accountUid"])],
)
data class AccountFavoriteEntity(
    val accountUid: String,
    val recipeId: Int,
    val addedAtMs: Double,
)

@Entity(tableName = "account_sync_state")
data class AccountSyncStateEntity(
    @PrimaryKey val accountUid: String,
    val syncEnabled: Boolean,
    val historyClearedAtMs: Double,
    val lastSyncedDocumentJson: String?,
    val updatedAtMs: Double,
)

@Dao
interface DeviceLibraryDao {
    @Query("SELECT * FROM device_favorites ORDER BY addedAtMs DESC, recipeId ASC")
    fun observeFavorites(): Flow<List<DeviceFavoriteEntity>>

    @Query("SELECT * FROM device_favorites ORDER BY addedAtMs DESC, recipeId ASC")
    suspend fun getAllFavorites(): List<DeviceFavoriteEntity>

    @Query("SELECT * FROM device_favorite_removals ORDER BY removedAtMs DESC")
    suspend fun getAllFavoriteRemovals(): List<DeviceFavoriteRemovalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: DeviceFavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavoriteRemoval(entity: DeviceFavoriteRemovalEntity)

    @Query("DELETE FROM device_favorites WHERE recipeId = :recipeId")
    suspend fun deleteFavorite(recipeId: Int)

    @Query("DELETE FROM device_favorite_removals WHERE recipeId = :recipeId")
    suspend fun deleteFavoriteRemoval(recipeId: Int)

    @Transaction
    suspend fun addFavoriteTransactional(recipeId: Int, addedAtMs: Double) {
        deleteFavoriteRemoval(recipeId)
        upsertFavorite(DeviceFavoriteEntity(recipeId = recipeId, addedAtMs = addedAtMs))
    }

    @Transaction
    suspend fun removeFavoriteTransactional(recipeId: Int, removedAtMs: Double) {
        deleteFavorite(recipeId)
        upsertFavoriteRemoval(DeviceFavoriteRemovalEntity(recipeId = recipeId, removedAtMs = removedAtMs))
    }

    @Query("SELECT * FROM device_histories ORDER BY visitedAtMs DESC, recipeName ASC")
    fun observeHistories(): Flow<List<DeviceHistoryEntity>>

    @Query("SELECT * FROM device_histories ORDER BY visitedAtMs DESC, recipeName ASC")
    suspend fun getAllHistories(): List<DeviceHistoryEntity>

    @Query("SELECT * FROM device_history_removals ORDER BY removedAtMs DESC")
    suspend fun getAllHistoryRemovals(): List<DeviceHistoryRemovalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: DeviceHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryRemoval(entity: DeviceHistoryRemovalEntity)

    @Query("DELETE FROM device_histories WHERE recipeName = :recipeName AND visitedAtMs = :visitedAtMs")
    suspend fun deleteHistory(recipeName: String, visitedAtMs: Double)

    @Query("DELETE FROM device_favorites")
    suspend fun clearAllFavorites()

    @Query("DELETE FROM device_favorite_removals")
    suspend fun clearAllFavoriteRemovals()

    @Query("DELETE FROM device_histories")
    suspend fun clearAllHistories()

    @Query("DELETE FROM device_history_removals")
    suspend fun clearAllHistoryRemovals()

    @Transaction
    suspend fun recordVisitTransactional(entity: DeviceHistoryEntity, maxHistories: Int) {
        upsertHistory(entity)
        val all = getAllHistories()
        if (all.size > maxHistories) {
            for (overflow in all.drop(maxHistories)) {
                deleteHistory(overflow.recipeName, overflow.visitedAtMs)
            }
        }
    }

    @Transaction
    suspend fun removeHistoryTransactional(recipeName: String, visitedAtMs: Double) {
        deleteHistory(recipeName, visitedAtMs)
        upsertHistoryRemoval(
            DeviceHistoryRemovalEntity(
                recipeName = recipeName,
                removedAtMs = visitedAtMs,
            ),
        )
    }

    @Transaction
    suspend fun clearAllHistoryTransactional() {
        clearAllHistories()
        clearAllHistoryRemovals()
    }

    @Transaction
    suspend fun replaceDeviceLibraryTransactional(
        favorites: List<DeviceFavoriteEntity>,
        favoriteRemovals: List<DeviceFavoriteRemovalEntity>,
        histories: List<DeviceHistoryEntity>,
        historyRemovals: List<DeviceHistoryRemovalEntity>,
    ) {
        clearAllFavorites()
        clearAllFavoriteRemovals()
        clearAllHistories()
        clearAllHistoryRemovals()
        for (fav in favorites) {
            upsertFavorite(fav)
        }
        for (rem in favoriteRemovals) {
            upsertFavoriteRemoval(rem)
        }
        for (hist in histories) {
            upsertHistory(hist)
        }
        for (rem in historyRemovals) {
            upsertHistoryRemoval(rem)
        }
    }
}

@Dao
interface AccountLibraryDao {
    @Query("SELECT * FROM account_favorites WHERE accountUid = :uid ORDER BY addedAtMs DESC, recipeId ASC")
    fun observeFavorites(uid: String): Flow<List<AccountFavoriteEntity>>

    @Query("SELECT * FROM account_favorites WHERE accountUid = :uid ORDER BY addedAtMs DESC, recipeId ASC")
    suspend fun getFavorites(uid: String): List<AccountFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorites(entities: List<AccountFavoriteEntity>)

    @Query("DELETE FROM account_favorites WHERE accountUid = :uid AND recipeId = :recipeId")
    suspend fun deleteFavorite(uid: String, recipeId: Int)

    @Query("DELETE FROM account_favorites WHERE accountUid = :uid")
    suspend fun clearFavorites(uid: String)

    @Query("SELECT * FROM account_sync_state WHERE accountUid = :uid")
    suspend fun getSyncState(uid: String): AccountSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: AccountSyncStateEntity)

    @Transaction
    suspend fun persistAccountSnapshotTransactional(
        state: AccountSyncStateEntity,
        favorites: List<AccountFavoriteEntity>,
    ) {
        upsertSyncState(state)
        clearFavorites(state.accountUid)
        if (favorites.isNotEmpty()) {
            upsertFavorites(favorites)
        }
    }
}

@Database(
    entities = [
        DeviceFavoriteEntity::class,
        DeviceFavoriteRemovalEntity::class,
        DeviceHistoryEntity::class,
        DeviceHistoryRemovalEntity::class,
        AccountFavoriteEntity::class,
        AccountSyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CookDatabase : RoomDatabase() {
    abstract fun deviceLibraryDao(): DeviceLibraryDao
    abstract fun accountLibraryDao(): AccountLibraryDao

    companion object {
        const val DATABASE_NAME = "cook-user-data.db"

        fun create(context: Context): CookDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CookDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
