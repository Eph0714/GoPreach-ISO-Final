package com.emfitsolutions.gopreach.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao

@Database(
    entities = [CachedDocumentEntity::class, PendingSyncOperationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val DATABASE_NAME = "gopreach.db"

        /** Adds [PendingSyncOperationEntity.isPermanentFailure] (sync-recovery
         * fix — see that column's own doc comment). A real migration, not
         * [com.emfitsolutions.gopreach.di.DatabaseModule]'s dev-only
         * `fallbackToDestructiveMigration()`, specifically *because* this is
         * the `pending_sync_operations` table: destructively wiping it on
         * upgrade would delete every publisher's still-unsynced offline
         * changes the moment they update the app — exactly what this whole
         * fix exists to stop happening. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_sync_operations ADD COLUMN isPermanentFailure INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
