package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     * 38 -> 39 adds the nullable `title` column to `page`, so a page can be named.
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
     * 43 -> 44 adds `folder.deletedAt` and `notebook.deletedAt`: the Trash.
     *
     * Nullable, so Room adds the columns itself and every row that existed before the upgrade
     * reads as "not in the Trash" — which is the only safe default, since the alternative would
     * hide someone's whole library behind a screen they have never seen.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate43To44_addsTheTrashColumnsAndLeavesEverythingVisible() {
        val dbName = "migration-test-44"

        val oldDb = helper.createDatabase(dbName, 43)
        oldDb.execSQL(
            """
            INSERT INTO folder (id, title, parentFolderId, createdAt, updatedAt)
            VALUES ('folder1', 'Research', NULL, 1000, 1000)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                linkedExternalUri, createdAt, updatedAt)
            VALUES ('notebook1', 'Field Notes', NULL, '[]', NULL, 'blank', 'native', NULL, NULL,
                NULL, 1000, 1000)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        migratedDb.query("SELECT deletedAt FROM folder WHERE id = 'folder1'").use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "a folder that existed before the Trash did is not in it",
                it.isNull(it.getColumnIndexOrThrow("deletedAt")),
            )
        }
        migratedDb.query("SELECT deletedAt FROM notebook WHERE id = 'notebook1'").use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "a notebook that existed before the Trash did is not in it",
                it.isNull(it.getColumnIndexOrThrow("deletedAt")),
            )
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

    /**
     * 45 -> 46 rescues the trashings that were never published. Before 0.13.0 the Trash was local
     * bookkeeping — `deletedAt` was written without stamping `updatedAt` or queueing the outbox —
     * so an item thrown away here stayed in the peer's library, and shipping the fix alone would
     * not have moved it: the document is already in the engine's `revs`, which is exactly what
     * `neverSent` cannot see, and a hidden notebook cannot be edited back into the outbox.
     *
     * Both halves matter. Queueing without stamping would push a `deletedAt` carrying its original
     * `updatedAt`, which ties with or loses to the peer's live copy under §5.5 and leaves the
     * notebook in the library it was supposed to leave.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate45To46_queuesAndStampsTrashedItemsSoTheTrashingIsPublished() {
        val dbName = "migration-test-46"
        val before = System.currentTimeMillis()

        val oldDb = helper.createDatabase(dbName, 45)
        // One trashed folder and one trashed notebook, both stamped long ago and neither queued —
        // the state a device is left in by trashing on 0.11.0 or 0.12.0.
        oldDb.execSQL(
            """
            INSERT INTO folder (id, title, parentFolderId, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('folder1', 'Research', NULL, 1000, 1000, 2000, 'boox')
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                linkedExternalUri, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('notebook1', 'Field Notes', NULL, '[]', NULL, 'blank', 'native', NULL, NULL,
                NULL, 1000, 1000, 2000, 'boox')
            """.trimIndent()
        )
        // And one of each still in the library, which must be left completely alone.
        oldDb.execSQL(
            """
            INSERT INTO folder (id, title, parentFolderId, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('folder2', 'Live', NULL, 1000, 1000, NULL, 'boox')
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                linkedExternalUri, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('notebook2', 'Live Notes', NULL, '[]', NULL, 'blank', 'native', NULL, NULL,
                NULL, 1000, 1000, NULL, 'boox')
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // Queued, so the flush has something to send.
        migratedDb.query("SELECT docId FROM couch_outbox ORDER BY docId").use {
            assertEquals(2, it.count)
            assertTrue(it.moveToFirst())
            assertEquals("folder:folder1", it.getString(0))
            assertTrue(it.moveToNext())
            assertEquals("notebook:notebook1", it.getString(0))
        }

        // Stamped, so the trashing outranks the peer's live copy instead of tying with it.
        for (table in listOf("folder", "notebook")) {
            migratedDb.query(
                "SELECT updatedAt, updatedBy, deletedAt FROM $table WHERE id = '${table}1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertTrue(
                    "a trashing that was never published must be re-stamped to win §5.5",
                    it.getLong(it.getColumnIndexOrThrow("updatedAt")) >= before,
                )
                assertTrue(
                    "the re-stamp happened on this device, so it owns the write",
                    it.isNull(it.getColumnIndexOrThrow("updatedBy")),
                )
                assertEquals(
                    "the item stays in the Trash — this republishes it, it does not restore it",
                    2000L, it.getLong(it.getColumnIndexOrThrow("deletedAt")),
                )
            }

            // The library is untouched: no stamp, no queue, no lost authorship.
            migratedDb.query(
                "SELECT updatedAt, updatedBy FROM $table WHERE id = '${table}2'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "a live item is not republished by an upgrade",
                    1000L, it.getLong(it.getColumnIndexOrThrow("updatedAt")),
                )
                assertEquals("boox", it.getString(it.getColumnIndexOrThrow("updatedBy")))
            }
        }

        roomDb.close()
    }

    /**
     * 47 adds `notebook.bookmarks` and `notebook.outline`: starred pages and the table of contents.
     *
     * A notebook that predates both reads as having neither — an empty list, not null. The columns
     * are NOT NULL with a `'[]'` default precisely so that every existing row decodes; a null would
     * throw in the type converter the first time the library was opened after upgrading.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate46To47_addsEmptyBookmarksAndOutlineToEveryNotebook() {
        val dbName = "migration-test-47"

        val oldDb = helper.createDatabase(dbName, 46)
        oldDb.execSQL(
            """
            INSERT INTO notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                linkedExternalUri, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('notebook1', 'Field Notes', NULL, '[]', NULL, 'blank', 'native', NULL, NULL,
                NULL, 1000, 1000, NULL, NULL)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        migratedDb.query("SELECT bookmarks, outline FROM notebook WHERE id = 'notebook1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("[]", it.getString(it.getColumnIndexOrThrow("bookmarks")))
            assertEquals("[]", it.getString(it.getColumnIndexOrThrow("outline")))
        }

        roomDb.close()
    }

    /**
     * 47 -> 48 gives every loose page a notebook and takes away the column that let it go without
     * one — see [com.ethran.notable.data.db.MIGRATION_47_48].
     *
     * The three cases that matter are all seeded here, because they diverge:
     *  - a **named** quick page keeps its own name as the notebook title;
     *  - an **unnamed** one is titled with its creation date, which is what the library already
     *    printed under its thumbnail, so the shelf reads afterwards much as the strip read before;
     *  - a page that **already had a notebook** must come through completely untouched — the
     *    migration rebuilds the whole table, so "did the other 200 pages survive" is the question
     *    this half is really asking.
     *
     * The outbox assertions are the point of the migration, not a detail. These documents have
     * never been offered to the server even once, so nothing else will ever queue them; unqueued,
     * a migrated note would sit in the library looking ordinary and silently never travel.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate47To48_givesEveryLoosePageANotebook() {
        val dbName = "migration-test-48"
        // 12 Aug 2026 12:00 UTC — the createdAt the untitled page's notebook is named after.
        val createdAt = 1786276800000L

        val oldDb = helper.createDatabase(dbName, 47)
        oldDb.execSQL(
            """
            INSERT INTO Folder (id, title, parentFolderId, createdAt, updatedAt)
            VALUES ('folder1', 'Work', NULL, 1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, defaultPageWidth, defaultPageHeight,
                bookmarks, outline, linkedExternalUri, createdAt, updatedAt, deletedAt, updatedBy)
            VALUES ('notebook1', 'Kept', NULL, '["page1"]', NULL, 'blank', 'native', NULL, NULL,
                '[]', '[]', NULL, 1620000000000, 1620000000000, NULL, NULL)
            """.trimIndent()
        )
        // A page that already belongs to a notebook: must survive the table rebuild verbatim.
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId,
                title, pageWidth, pageHeight, createdAt, updatedAt, updatedBy)
            VALUES ('page1', 42, 'notebook1', 'grid', 'native', NULL, 'Chapter 1', 1240, 1754,
                1620000000000, 1620000000005, 'ipad')
            """.trimIndent()
        )
        // A named quick page, parked in a folder.
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId,
                title, pageWidth, pageHeight, createdAt, updatedAt, updatedBy)
            VALUES ('loose1', 7, NULL, 'dotted', 'native', 'folder1', 'Groceries', 1240, 1754,
                1620000000000, 1620000000000, NULL)
            """.trimIndent()
        )
        // An unnamed quick page at the library root.
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId,
                title, pageWidth, pageHeight, createdAt, updatedAt, updatedBy)
            VALUES ('loose2', 0, NULL, 'blank', 'native', NULL, NULL, NULL, NULL,
                $createdAt, $createdAt, NULL)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // The notebook page came through the rebuild with every column intact.
        migratedDb.query(
            "SELECT scroll, notebookId, background, title, pageWidth, updatedAt, updatedBy " +
                "FROM Page WHERE id = 'page1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(42, it.getInt(it.getColumnIndexOrThrow("scroll")))
            assertEquals("notebook1", it.getString(it.getColumnIndexOrThrow("notebookId")))
            assertEquals("grid", it.getString(it.getColumnIndexOrThrow("background")))
            assertEquals("Chapter 1", it.getString(it.getColumnIndexOrThrow("title")))
            assertEquals(1240, it.getInt(it.getColumnIndexOrThrow("pageWidth")))
            assertEquals(1620000000005L, it.getLong(it.getColumnIndexOrThrow("updatedAt")))
            assertEquals("ipad", it.getString(it.getColumnIndexOrThrow("updatedBy")))
        }

        // Nothing is loose any more — the column cannot even express it.
        migratedDb.query("SELECT COUNT(*) FROM Page WHERE notebookId IS NULL").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM pragma_table_info('Page') WHERE name = 'parentFolderId'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }

        // The named one kept its name and its folder, and its notebook inherited its sheet and
        // template so a second page added to it will match the first.
        val named = notebookOf(migratedDb, "loose1")
        assertEquals("Groceries", named.title)
        assertEquals("folder1", named.parentFolderId)
        assertEquals("dotted", named.defaultBackground)
        assertEquals(1240, named.defaultPageWidth)
        assertEquals("[\"loose1\"]", named.pageIds)
        assertEquals("loose1", named.openPageId)

        // The unnamed one is titled with its creation date, in the device's locale, exactly as
        // the library used to caption it.
        val unnamed = notebookOf(migratedDb, "loose2")
        assertEquals(
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(createdAt)),
            unnamed.title,
        )
        assertEquals(null, unnamed.parentFolderId)

        // Both notebooks and both pages are queued: this is the moment they join sync.
        val queued = mutableSetOf<String>()
        migratedDb.query("SELECT docId FROM couch_outbox").use {
            while (it.moveToNext()) queued += it.getString(0)
        }
        assertEquals(
            setOf(
                "notebook:${named.id}", "page:loose1",
                "notebook:${unnamed.id}", "page:loose2",
            ),
            queued,
        )

        roomDb.close()
    }

    private data class MigratedNotebook(
        val id: String,
        val title: String,
        val parentFolderId: String?,
        val defaultBackground: String,
        val defaultPageWidth: Int?,
        val pageIds: String,
        val openPageId: String?,
    )

    @Test
    fun migrate48To49_addsBlockTablesWithoutLosingData() {
        val dbName = "migration-test-49"

        val oldDb = helper.createDatabase(dbName, 48)
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId,
                defaultBackground, defaultBackgroundType, linkedExternalUri, createdAt, updatedAt)
            VALUES ('notebook1', 'Kept', NULL, '["page1"]', NULL, 'blank', 'native', NULL,
                1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, createdAt,
                updatedAt)
            VALUES ('page1', 0, 'notebook1', 'blank', 'native', 1620000000000, 1620000000000)
            """.trimIndent()
        )
        oldDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*APP_MIGRATIONS)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // Nothing was dropped on the way. 48 -> 49 adds tables and touches no existing row, so a
        // page that had no blocks before is a page with no blocks now.
        migratedDb.query("SELECT title FROM Notebook WHERE id = 'notebook1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Kept", it.getString(it.getColumnIndexOrThrow("title")))
        }
        migratedDb.query("SELECT COUNT(*) FROM Block").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }

        // Inserting and reading back is what actually proves the column set, the NOT NULL-ness and
        // the defaults — a COUNT on an empty table would pass against almost any schema.
        migratedDb.execSQL(
            """
            INSERT INTO Block (id, pageId, kind, orderKey, text, payload, createdAt, updatedAt,
                deviceId)
            VALUES ('b1', 'page1', 'md', 'a0', '## Groceries', '{}', 1620000000010,
                1620000000010, 'boox')
            """.trimIndent()
        )
        migratedDb.query(
            "SELECT pageId, kind, orderKey, text, payload FROM Block WHERE id = 'b1'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("page1", it.getString(it.getColumnIndexOrThrow("pageId")))
            assertEquals("md", it.getString(it.getColumnIndexOrThrow("kind")))
            assertEquals("a0", it.getString(it.getColumnIndexOrThrow("orderKey")))
            assertEquals("## Groceries", it.getString(it.getColumnIndexOrThrow("text")))
            assertEquals("{}", it.getString(it.getColumnIndexOrThrow("payload")))
        }

        // A tombstone deliberately has no foreign key, so it outlives the page it names — that is
        // the whole point of it, and a cascade here would erase the record of the deletion.
        migratedDb.execSQL(
            "INSERT INTO DeletedBlock (blockId, pageId, deletedAt) VALUES ('b2', 'page1', 1620000000020)"
        )
        migratedDb.execSQL("PRAGMA foreign_keys = ON")
        migratedDb.execSQL("DELETE FROM Page WHERE id = 'page1'")

        // The block cascaded with its page...
        migratedDb.query("SELECT COUNT(*) FROM Block WHERE id = 'b1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        // ...and the tombstone did not.
        migratedDb.query("SELECT COUNT(*) FROM DeletedBlock WHERE blockId = 'b2'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        roomDb.close()
    }

    /** The notebook 47 -> 48 built around [pageId]. */
    private fun notebookOf(db: SupportSQLiteDatabase, pageId: String): MigratedNotebook {
        val notebookId = db.query("SELECT notebookId FROM Page WHERE id = '$pageId'").use {
            assertTrue("no row for page $pageId", it.moveToFirst())
            it.getString(0)
        }
        return db.query(
            "SELECT id, title, parentFolderId, defaultBackground, defaultPageWidth, pageIds, " +
                "openPageId FROM Notebook WHERE id = '$notebookId'"
        ).use {
            assertTrue("no notebook for page $pageId", it.moveToFirst())
            MigratedNotebook(
                id = it.getString(0),
                title = it.getString(1),
                parentFolderId = if (it.isNull(2)) null else it.getString(2),
                defaultBackground = it.getString(3),
                defaultPageWidth = if (it.isNull(4)) null else it.getInt(4),
                pageIds = it.getString(5),
                openPageId = if (it.isNull(6)) null else it.getString(6),
            )
        }
    }
}
