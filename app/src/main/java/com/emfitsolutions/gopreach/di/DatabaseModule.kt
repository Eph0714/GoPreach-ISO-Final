package com.emfitsolutions.gopreach.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import com.emfitsolutions.gopreach.data.local.AppDatabase
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDao
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDatabase
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration() // dev-only default for any future gap; real migrations land before release
            .build()

    @Provides
    fun provideCacheDao(db: AppDatabase): CacheDao = db.cacheDao()

    @Provides
    fun provideSyncQueueDao(db: AppDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    // "Add a dropdown for City, Municipalities, Town Barangay" — see
    // PsgcDatabase's own doc comment for why this is a separate, read-only
    // Room database rather than folded into AppDatabase above.
    @Provides
    @Singleton
    fun providePsgcDatabase(@ApplicationContext context: Context): PsgcDatabase {
        discardPsgcDatabaseIfCorrupt(context)
        return Room.databaseBuilder(context, PsgcDatabase::class.java, PsgcDatabase.DATABASE_NAME)
            .createFromAsset(PsgcDatabase.ASSET_PATH)
            .build()
    }

    /**
     * Self-heal for the exact "dropdowns are empty" failure mode:
     * `createFromAsset` only copies the bundled asset in when the on-device
     * file doesn't exist yet — an on-device copy left empty/partial by an
     * interrupted first copy (low storage, a killed process mid-copy, ...)
     * would otherwise stay broken forever, surviving every future app
     * update. Opens the existing file directly (bypassing Room, which would
     * just accept whatever's there) and deletes it — plus its `-wal`/`-shm`/
     * `-journal` siblings — the moment it can't read a real row out of
     * `province`, so the `Room.databaseBuilder` call right after this always
     * gets a genuine fresh copy of the current asset instead of quietly
     * reusing a broken file. A brand-new device (no file yet) is a no-op.
     */
    private fun discardPsgcDatabaseIfCorrupt(context: Context) {
        val dbFile = context.getDatabasePath(PsgcDatabase.DATABASE_NAME)
        if (!dbFile.exists()) {
            Log.d("PsgcDatabase", "No existing $dbFile — createFromAsset will copy a fresh one.")
            return
        }
        val healthy = runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.compileStatement("SELECT COUNT(*) FROM province").simpleQueryForLong() > 0
            }
        }.getOrElse { error ->
            Log.e("PsgcDatabase", "Existing $dbFile failed integrity check, discarding and re-copying from asset.", error)
            false
        }
        if (!healthy) {
            listOf(dbFile.path, "${dbFile.path}-wal", "${dbFile.path}-shm", "${dbFile.path}-journal").forEach { path ->
                runCatching { java.io.File(path).delete() }
            }
        } else {
            Log.d("PsgcDatabase", "Existing $dbFile passed integrity check (${dbFile.length()} bytes) — keeping it.")
        }
    }

    @Provides
    fun providePsgcDao(db: PsgcDatabase): PsgcDao = db.psgcDao()
}
