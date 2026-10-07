package com.emfitsolutions.gopreach.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** The offline cache + outbox database, in the app's Documents folder (same tables and schema version as Android). */
@OptIn(ExperimentalForeignApi::class)
fun buildIosAppDatabase(): AppDatabase {
    val documents = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val path = requireNotNull(documents?.path) { "No Documents directory" } + "/" + AppDatabase.DATABASE_NAME
    return Room.databaseBuilder<AppDatabase>(name = path, factory = { AppDatabaseConstructor.initialize() })
        .setDriver(BundledSQLiteDriver())
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}
