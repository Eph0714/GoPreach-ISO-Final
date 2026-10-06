package com.emfitsolutions.gopreach.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao

@Database(
    entities = [CachedDocumentEntity::class, PendingSyncOperationEntity::class],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val DATABASE_NAME = "gopreach.db"

        /** Adds [PendingSyncOperationEntity.isPermanentFailure] (sync-recovery fix). A real migration, not a destructive
         * one, because wiping `pending_sync_operations` would delete every publisher's still-unsynced offline changes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE pending_sync_operations ADD COLUMN isPermanentFailure INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}

// Room generates the actual implementation for every target.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
