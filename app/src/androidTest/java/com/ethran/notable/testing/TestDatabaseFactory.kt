package com.ethran.notable.testing

import android.content.Context
import androidx.room.Room
import com.ethran.notable.data.TrashRepository
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.PageRepository

/**
 * The deletion path, wired from a test database. Every `AppRepository` a test builds needs one,
 * and assembling it by hand at four call sites is four chances to wire it differently.
 */
fun trashRepositoryFor(db: AppDatabase) = TrashRepository(
    folderRepository = FolderRepository(db.folderDao()),
    bookRepository = BookRepository(db.notebookDao(), db.pageDao()),
    pageRepository = PageRepository(db.pageDao()),
    couchDeletionRepository = CouchDeletionRepository(db.couchDeletionDao()),
    db = db,
)

/**
 * Helper for instrumentation tests.
 *
 * Two modes of operation:
 * 1) In-memory DB + seeding in test (fast, deterministic, no files).
 * 2) DB seeded from a real database ("real notebooks") – see [createFromAsset].
 */
object TestDatabaseFactory {

    fun createInMemory(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            // Tests often seed data on the test thread.
            .allowMainThreadQueries()
            .build()

    /**
     * Use this when you want to run tests on "real notebooks".
     *
     * How to prepare the asset:
     * 1) Export the application database file from the device/emulator (Room: app_database)
     * 2) Place it in: app/src/androidTest/assets/<assetPath>
     * 3) Call this method with the assetPath, e.g., "seed/real_app_database".
     */
    fun createFromAsset(
        context: Context,
        assetPath: String,
        dbName: String = "test_app_database"
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
        .createFromAsset(assetPath)
        .allowMainThreadQueries()
        .build()
}

