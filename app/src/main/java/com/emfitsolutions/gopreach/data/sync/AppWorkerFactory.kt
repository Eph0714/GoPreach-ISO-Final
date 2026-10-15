package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koin.core.Koin

/**
 * Builds GoPreach's workers with their dependencies taken from Koin, and hands the [WorkerParameters] over directly.
 *
 * Koin's own WorkManager factory passes the parameters through Koin's parameter lookup, which in the minified release build could not find
 * them ("No definition found for type 'androidx.work.WorkerParameters'"): the first sync after signing in then crashed the app. Here the
 * parameters never go through Koin, so nothing depends on how R8 treats that lookup. Any other worker class returns null, which makes
 * WorkManager use its default factory.
 */
class AppWorkerFactory(private val koin: Koin) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, koin.get(), koin.get(), koin.get())
        ReminderWorker::class.java.name -> ReminderWorker(appContext, workerParameters, koin.get(), koin.get(), koin.get(), koin.get())
        else -> null
    }
}
