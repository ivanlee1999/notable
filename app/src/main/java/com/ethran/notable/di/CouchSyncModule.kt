package com.ethran.notable.di

import com.ethran.notable.sync.couch.CouchSyncBackend
import com.ethran.notable.sync.couch.CouchSyncClock
import com.ethran.notable.sync.couch.CouchSyncHost
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the real CouchDB stack behind [CouchSyncBackend].
 *
 * The seam is the point: `CouchSyncControllerTest` drives the same controller over a fake backend
 * and a fake clock, so the debounce and change-feed pacing are tested without a server.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CouchSyncBindingsModule {
    @Binds
    @Singleton
    abstract fun bindCouchSyncBackend(host: CouchSyncHost): CouchSyncBackend
}

@Module
@InstallIn(SingletonComponent::class)
object CouchSyncModule {
    /** Production timings; every value is documented on [CouchSyncClock]. */
    @Provides
    @Singleton
    fun provideCouchSyncClock(): CouchSyncClock = CouchSyncClock()
}
