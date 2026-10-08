package com.emfitsolutions.gopreach.di

import android.content.Context
import androidx.work.WorkerParameters
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDao
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.test.verify.verify

class KoinGraphTest {
    /** Every constructor parameter of every registered class must itself be registered — the Hilt compile-time check, now at test time. */
    @OptIn(org.koin.core.annotation.KoinExperimentalAPI::class)
    @Test
    fun everyDependencyIsProvided() {
        appModule.verify(
            extraTypes = listOf(
                Context::class, WorkerParameters::class, Gson::class, CoroutineScope::class,
                CacheDao::class, SyncQueueDao::class, PsgcDao::class, com.emfitsolutions.gopreach.data.sync.SyncEngine::class, com.emfitsolutions.gopreach.data.sync.WriteQueuedListener::class, com.emfitsolutions.gopreach.data.sync.RemoteCollections::class, com.emfitsolutions.gopreach.data.location.LocationTracker::class, com.emfitsolutions.gopreach.data.repository.AuthService::class, com.emfitsolutions.gopreach.platform.KeyValueStores::class, com.emfitsolutions.gopreach.data.sync.NetworkStatus::class, com.emfitsolutions.gopreach.data.remote.RemoteFiles::class, com.emfitsolutions.gopreach.data.remote.SyncApi::class,
            ),
        )
    }
}
