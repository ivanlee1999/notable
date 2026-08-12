package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.APP_MIGRATIONS
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test(timeout = 10000)
    fun simpleTest() {
        assertTrue(true)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(), // Add AutoMigrationSpecs here if any
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate30To31_autoMigration() {
        val dbName = "migration-test"

        // 1. Create DB with version 30 schema
        val db = helper.createDatabase(dbName, 30)

// Insert required parent data first
        db.execSQL(
            """
    INSERT INTO Notebook (
        id,
        title,
        openPageId,
        pageIds,
        parentFolderId,
        defaultNativeTemplate,
        createdAt,
        updatedAt
    ) VALUES (
        'notebook1',
        'Test Notebook',
        NULL,
        '[]',
        NULL,
        'blank',
        1620000000000,
        1620000000000
    )
    """.trimIndent()
        )


        db.execSQL(
            """
    INSERT INTO Folder (id, title, createdAt, updatedAt)
    VALUES ('TEST_FOLDER_ID', 'Test Folder', 1620000000, 1620000000)
    """.trimIndent()
        )

        // Insert with column name from version 30: 'nativeTemplate'
        db.execSQL(
            """
    INSERT INTO Page (
        id,
        notebookId,
        nativeTemplate,
        parentFolderId,
        scroll,
        createdAt,
        updatedAt
    ) VALUES (
        'page1',
        'notebook1',
        'grid',
        'TEST_FOLDER_ID',
        0.0,
        1620000000,
        1620000000
    )
    """.trimIndent()
        )


        db.close()

        // 2. Reopen DB with version 31 (latest AppDatabase version) to trigger migration
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // The app's own list: reaching the current version needs every hand-written migration,
            // even for a test that is only interested in one earlier auto-migration.
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. Verify renamed column exists with expected data
        val cursor = migratedDb.query("SELECT background FROM Page WHERE id = 'page1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            val background = it.getString(it.getColumnIndexOrThrow("background"))
            assertEquals("grid", background)
        }

        roomDb.close()
    }

    /**
     * 35 -> 36 does two things: adds the `page_sync_state` table, and renames
     * `notebook_sync_state.localUpdatedAtAtSync` to `syncedLocalUpdatedAt`.
     *
     * The rename must **carry the value over** — Room recreates the table and copies rows, and a
     * silently dropped anchor would reset every notebook to "dirty" and re-upload the whole library
     * on the next sync. So this asserts the stored anchor, not just the column name.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate35To36_addsPageSyncStateAndRenamesSyncAnchor() {
        val dbName = "migration-test-36"

        // 1. Create the v35 schema (page_sync_state does not exist yet) and seed a sync-state row
        //    using the pre-rename column name.
        val oldDb = helper.createDatabase(dbName, 35)
        oldDb.execSQL(
            """
            INSERT INTO notebook_sync_state
                (notebookId, state, lastSyncedAt, localUpdatedAtAtSync, remoteEtag, remoteUpdatedAt, lastError)
            VALUES ('notebook1', 'SYNCED', 1620000000001, 1620000000000, '"nb-etag"', 1620000000002, NULL)
            """.trimIndent()
        )
        oldDb.close()

        // 2. Reopen at the latest version to trigger the 35 -> 36 auto-migration.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // The app's own list: reaching the current version needs every hand-written migration,
            // even for a test that is only interested in one earlier auto-migration.
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. The renamed anchor is readable under the new name, with its original value.
        migratedDb.query(
            "SELECT syncedLocalUpdatedAt, lastSyncedAt, remoteEtag FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1620000000000L, it.getLong(it.getColumnIndexOrThrow("syncedLocalUpdatedAt")))
            // lastSyncedAt is a separate column and must not be confused with the anchor.
            assertEquals(1620000000001L, it.getLong(it.getColumnIndexOrThrow("lastSyncedAt")))
            assertEquals("\"nb-etag\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
        }

        // 4. The new table exists and accepts a row with the expected columns.
        migratedDb.execSQL(
            """
            INSERT INTO page_sync_state (pageId, notebookId, remoteEtag, syncedLocalUpdatedAt, lastSyncedAt)
            VALUES ('page1', 'notebook1', '"etag-1"', 1620000000010, 1620000000011)
            """.trimIndent()
        )
        migratedDb.query(
            "SELECT remoteEtag, syncedLocalUpdatedAt FROM page_sync_state WHERE pageId = 'page1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"etag-1\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
            assertEquals(1620000000010L, it.getLong(it.getColumnIndexOrThrow("syncedLocalUpdatedAt")))
        }

        roomDb.close()
    }

    /**
     * 36 -> 37 adds the two nullable bulk-detection baseline columns
     * (`remoteDirEtag`, `remoteDirServerKey`) to `notebook_sync_state`. It is purely additive: existing
     * rows must be preserved untouched and the new columns must initialize to NULL, so a device that
     * upgrades mid-life keeps every notebook's sync anchor and simply has no directory baseline yet.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate36To37_addsDirBaselineColumnsAsNull() {
        val dbName = "migration-test-37"

        // 1. Create the v36 schema and seed a synced row (no baseline columns exist yet).
        val oldDb = helper.createDatabase(dbName, 36)
        oldDb.execSQL(
            """
            INSERT INTO notebook_sync_state
                (notebookId, state, lastSyncedAt, syncedLocalUpdatedAt, remoteEtag, remoteUpdatedAt, lastError)
            VALUES ('notebook1', 'SYNCED', 1620000000001, 1620000000000, '"nb-etag"', 1620000000002, NULL)
            """.trimIndent()
        )
        oldDb.close()

        // 2. Reopen at the latest version to trigger the 36 -> 37 auto-migration.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // The app's own list: reaching the current version needs every hand-written migration,
            // even for a test that is only interested in one earlier auto-migration.
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. The existing row survives and the two new columns are present and NULL.
        migratedDb.query(
            "SELECT remoteEtag, remoteDirEtag, remoteDirServerKey FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"nb-etag\"", it.getString(it.getColumnIndexOrThrow("remoteEtag")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("remoteDirEtag")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("remoteDirServerKey")))
        }

        // 4. The new columns are writable.
        migratedDb.execSQL(
            "UPDATE notebook_sync_state SET remoteDirEtag = '\"dir-etag\"', remoteDirServerKey = 'srv' WHERE notebookId = 'notebook1'"
        )
        migratedDb.query(
            "SELECT remoteDirEtag, remoteDirServerKey FROM notebook_sync_state WHERE notebookId = 'notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("\"dir-etag\"", it.getString(it.getColumnIndexOrThrow("remoteDirEtag")))
            assertEquals("srv", it.getString(it.getColumnIndexOrThrow("remoteDirServerKey")))
        }

        roomDb.close()
    }

    /**
     * 37 -> 38 adds the two tables CouchDB sync needs to express *absence*: `DeletedStroke`
     * (erasures, so a merge can tell "erased here" from "not synced here yet") and `couch_deletion`
     * (notebooks/folders deleted locally, so the tombstone still gets pushed after a restart).
     *
     * Hand-written, so this asserts what a destructive fallback would have hidden: the existing
     * notebook survives untouched, and both new tables are present and writable.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate37To38_addsCouchTombstoneTablesWithoutLosingData() {
        val dbName = "migration-test-38"

        val oldDb = helper.createDatabase(dbName, 37)
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, linkedExternalUri, createdAt, updatedAt)
            VALUES ('notebook1', 'Kept', NULL, '[]', NULL, 'blank', 'native', NULL,
                1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // Nothing was dropped on the way.
        migratedDb.query("SELECT title FROM Notebook WHERE id = 'notebook1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Kept", it.getString(it.getColumnIndexOrThrow("title")))
        }

        migratedDb.execSQL(
            "INSERT INTO DeletedStroke (strokeId, pageId, deletedAt) VALUES ('s1', 'p1', 1620000000010)"
        )
        migratedDb.query("SELECT pageId, deletedAt FROM DeletedStroke WHERE strokeId = 's1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("p1", it.getString(it.getColumnIndexOrThrow("pageId")))
            assertEquals(1620000000010L, it.getLong(it.getColumnIndexOrThrow("deletedAt")))
        }

        migratedDb.execSQL(
            "INSERT INTO couch_deletion (docId, deletedAt) VALUES ('notebook:nb1', '2026-02-02T00:00:00Z')"
        )
        migratedDb.query("SELECT deletedAt FROM couch_deletion WHERE docId = 'notebook:nb1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("2026-02-02T00:00:00Z", it.getString(it.getColumnIndexOrThrow("deletedAt")))
        }

        roomDb.close()
    }

    /**
     * 38 -> 39 adds the nullable `title` column to `page`, so quick pages can be named.
     *
     * The column is nullable rather than defaulted to a string, and every page that predates the
     * feature has to come out the other side as *unnamed* — the library shows the creation date in
     * that case, and a non-null default would make every old page look deliberately titled.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate38To39_addsNullablePageTitleWithoutLosingData() {
        val dbName = "migration-test-39"

        val oldDb = helper.createDatabase(dbName, 38)
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, linkedExternalUri, createdAt, updatedAt)
            VALUES ('notebook1', 'Kept', NULL, '[]', NULL, 'blank', 'native', NULL,
                1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType,
                parentFolderId, createdAt, updatedAt)
            VALUES ('page1', 42, 'notebook1', 'blank', 'native', NULL,
                1620000000000, 1620000000005)
            """.trimIndent()
        )
        oldDb.close()

        // 38 -> 39 is an auto-migration declared on @Database, but the hand-written ones after it
        // still have to be here: the database opens at the current version, not at 39.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // The page survives intact, and is unnamed rather than blank-named.
        migratedDb.query("SELECT scroll, title, updatedAt FROM Page WHERE id = 'page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(42, it.getInt(it.getColumnIndexOrThrow("scroll")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("title")))
            assertEquals(1620000000005L, it.getLong(it.getColumnIndexOrThrow("updatedAt")))
        }

        // And the new column actually accepts a name.
        migratedDb.execSQL("UPDATE Page SET title = 'Shopping list' WHERE id = 'page1'")
        migratedDb.query("SELECT title FROM Page WHERE id = 'page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Shopping list", it.getString(it.getColumnIndexOrThrow("title")))
        }

        roomDb.close()
    }

    /**
     * 41 -> 42 adds `couch_outbox`: the durable record that a document has changed and has not been
     * accepted by the server yet. It is what makes an edit made offline survive being killed.
     *
     * This is the migration this branch introduces, so it runs against databases that are already
     * full of users' notebooks — hence the assertion that the existing rows are still there, not
     * only that the new table exists. Room validates the whole schema against `schemas/42.json`
     * while opening, so a `CREATE TABLE` that disagreed with the entity would fail here too.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate41To42_addsCouchOutboxWithoutLosingData() {
        val dbName = "migration-test-42"

        val oldDb = helper.createDatabase(dbName, 41)
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                linkedExternalUri, createdAt, updatedAt)
            VALUES ('notebook1', 'Kept', NULL, '["page1"]', NULL, 'blank', 'native', NULL, NULL,
                NULL, 1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId,
                title, pageWidth, pageHeight, createdAt, updatedAt)
            VALUES ('page1', 42, 'notebook1', 'blank', 'native', NULL, NULL, NULL, NULL,
                1620000000000, 1620000000005)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // The library came through the upgrade intact.
        migratedDb.query("SELECT title FROM Notebook WHERE id = 'notebook1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Kept", it.getString(it.getColumnIndexOrThrow("title")))
        }
        migratedDb.query("SELECT scroll FROM Page WHERE id = 'page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(42, it.getInt(it.getColumnIndexOrThrow("scroll")))
        }

        // The outbox exists and holds what the repository puts in it.
        migratedDb.execSQL(
            "INSERT INTO couch_outbox (docId, queuedAt) VALUES ('page:page1', 1620000000010)"
        )
        migratedDb.query("SELECT queuedAt FROM couch_outbox WHERE docId = 'page:page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1620000000010L, it.getLong(it.getColumnIndexOrThrow("queuedAt")))
        }

        // `docId` is the primary key, so re-queueing a document already waiting must not add a
        // second row — the outbox is a set of documents, not a log of edits to them.
        migratedDb.execSQL(
            "INSERT OR REPLACE INTO couch_outbox (docId, queuedAt) VALUES ('page:page1', 1620000000020)"
        )
        migratedDb.query("SELECT COUNT(*) FROM couch_outbox WHERE docId = 'page:page1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        roomDb.close()
    }

    /**
     * 42 -> 43 adds `couch_deletion.pending`, which splits "this notebook is deleted" from "the
     * server has still to be told". A deletion recorded before the upgrade is one this device made
     * and may not have pushed yet, so every existing row has to come through as pending — if the
     * default landed the other way, an offline deletion made before the upgrade would silently
     * never be published, which is the exact failure `couch_deletion` exists to prevent.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate42To43_addsPendingAndLeavesExistingDeletionsOwed() {
        val dbName = "migration-test-43"

        val oldDb = helper.createDatabase(dbName, 42)
        oldDb.execSQL(
            """
            INSERT INTO couch_deletion (docId, deletedAt)
            VALUES ('notebook:notebook1', '2026-02-01T00:00:00Z')
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        migratedDb.query(
            "SELECT deletedAt, pending FROM couch_deletion WHERE docId = 'notebook:notebook1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "2026-02-01T00:00:00Z", it.getString(it.getColumnIndexOrThrow("deletedAt"))
            )
            assertEquals(
                "a deletion made before the upgrade must still be pushed",
                1, it.getInt(it.getColumnIndexOrThrow("pending")),
            )
        }

        // And a settled deletion — one the server already holds — is storable and does not read
        // back as owed.
        migratedDb.execSQL(
            """
            INSERT INTO couch_deletion (docId, deletedAt, pending)
            VALUES ('notebook:notebook2', '2026-02-01T00:00:01Z', 0)
            """.trimIndent()
        )
        migratedDb.query("SELECT docId FROM couch_deletion WHERE pending != 0").use {
            assertEquals(1, it.count)
        }

        roomDb.close()
    }
}
