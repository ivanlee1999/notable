package com.ethran.notable.data.db

import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Page ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Page ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE Stroke ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Stroke ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE Notebook ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Notebook ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Page ADD COLUMN nativeTemplate TEXT NOT NULL DEFAULT 'blank'")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM Page " +
                    "WHERE notebookId IS NOT NULL " +
                    "AND notebookId NOT IN (SELECT id FROM Notebook);"
        )
    }
}

@RenameColumn.Entries(
    RenameColumn(
        tableName = "Page",
        fromColumnName = "nativeTemplate",
        toColumnName = "background"
    )
)
class AutoMigration30to31 : AutoMigrationSpec

@RenameColumn.Entries(
    RenameColumn(
        tableName = "Notebook",
        fromColumnName = "defaultNativeTemplate",
        toColumnName = "defaultBackground"
    )
)
class AutoMigration31to32 : AutoMigrationSpec

// `localUpdatedAtAtSync` read as a typo. Only `notebook_sync_state` is renamed: `page_sync_state` is
// created by this same migration, so it is simply created with the right name. Room recreates the
// table and copies the rows, so the stored anchors survive and no notebook is re-uploaded because
// its anchor went missing.
@RenameColumn.Entries(
    RenameColumn(
        tableName = "notebook_sync_state",
        fromColumnName = "localUpdatedAtAtSync",
        toColumnName = "syncedLocalUpdatedAt"
    )
)
class AutoMigration35to36 : AutoMigrationSpec



// Migration 32 -> 33:
// 1. Rename original Stroke table to stroke_old
// 2. Drop any carried indexes from old table (index_Stroke_pageId)
// 3. Create new Stroke table (with points as BLOB and color default)
// 4. Recreate required index on the NEW table
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL("ALTER TABLE stroke RENAME TO stroke_old")

        // IMPORTANT: drop the old index that now belongs to stroke_old
        // (otherwise we can't recreate it for the new table)
        db.execSQL("DROP INDEX IF EXISTS `index_Stroke_pageId`")

        // Create new table with correct name/case matching the entity @Entity(tableName = "Stroke") (default)
        db.execSQL(
            """
            CREATE TABLE `Stroke` (
                `id` TEXT NOT NULL,
                `size` REAL NOT NULL,
                `pen` TEXT NOT NULL,
                `color` INTEGER NOT NULL DEFAULT 0xFF000000,
                `maxPressure` INTEGER NOT NULL DEFAULT 4096,
                `top` REAL NOT NULL,
                `bottom` REAL NOT NULL,
                `left` REAL NOT NULL,
                `right` REAL NOT NULL,
                `points` BLOB NOT NULL,
                `pageId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`pageId`) REFERENCES `Page`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Recreate the expected index for the NEW table
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Stroke_pageId` ON `Stroke` (`pageId`)")
    }
}

// Migration 37 -> 38: the two tables CouchDB sync needs in order to express *absence*.
//
// Hand-written rather than an AutoMigration because both tables are new and purely additive, and a
// spelled-out CREATE is the thing a reviewer can compare against `schemas/38.json` directly. It is
// emphatically not `fallbackToDestructiveMigration` territory: these tables arrive in the same
// release as the sync feature, so the databases that hit this path are full of users' notebooks.
//
// - `DeletedStroke` records erasures, so a merge can tell "erased here" from "not synced here yet".
// - `couch_deletion` records notebooks/folders deleted locally, so the tombstone still gets pushed
//   after a restart (deleting while offline).
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `DeletedStroke` (
                `strokeId` TEXT NOT NULL,
                `pageId` TEXT NOT NULL,
                `deletedAt` INTEGER NOT NULL,
                PRIMARY KEY(`strokeId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_DeletedStroke_pageId` ON `DeletedStroke` (`pageId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `couch_deletion` (
                `docId` TEXT NOT NULL,
                `deletedAt` TEXT NOT NULL,
                PRIMARY KEY(`docId`)
            )
            """.trimIndent()
        )
    }
}

// Migration 41 -> 42: `couch_outbox`, the third thing CouchDB sync needs Room to remember.
//
// `couch_deletion` (above) says a document is *gone*; this one says a document *changed* and has
// not been accepted yet. Both facts are invisible in the data itself once the write has landed,
// which is why they need tables of their own rather than being inferred.
//
// Hand-written for the same reason MIGRATION_37_38 is: the table is new and purely additive, a
// spelled-out CREATE is what a reviewer can compare against `schemas/42.json`, and the databases
// reaching this path are full of users' notebooks.
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `couch_outbox` (
                `docId` TEXT NOT NULL,
                `queuedAt` INTEGER NOT NULL,
                PRIMARY KEY(`docId`)
            )
            """.trimIndent()
        )
    }
}

// Records which *peer* last wrote a page, notebook or folder, so a scalar tie-break can be decided
// on the real author rather than on this device's own id — see [Page.updatedBy] for what that was
// costing.
//
// Hand-written rather than an AutoMigration because `schemas/44.json` was never exported, and Room
// generates an auto-migration only from a schema file it can read. Three nullable columns with no
// backfill: null already means "written here", which is the correct reading of every row that
// predates this column, since a device that has never synced authored everything it holds.
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `Page` ADD COLUMN `updatedBy` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `Notebook` ADD COLUMN `updatedBy` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `Folder` ADD COLUMN `updatedBy` TEXT DEFAULT NULL")
    }
}

/**
 * Republishes the trashings that were only ever local, so the two libraries can agree again.
 *
 * Until 0.13.0 the Trash was this device's own bookkeeping: `trashNotebook`/`trashFolder` wrote
 * `deletedAt` with `setDeletedAt`, which stamps no `updatedAt` and queues no outbox row. Nothing
 * was published, so a notebook thrown away here stayed in the iPad's library.
 *
 * Shipping the fix does not rescue those rows. The document has been through the server already,
 * so it is in the engine's `revs` map and `CouchSyncEngine.neverSent` — the scan that catches a
 * mutation site which forgot to queue — deliberately cannot see it. And nothing else will queue
 * it either: a trashed notebook is hidden from the library, so there is no way to edit it into
 * the outbox. Without this the item stays hidden here and visible there, permanently, with no
 * in-app way back short of restoring it and throwing it away a second time.
 *
 * So each trashed row is queued, and its `updatedAt` stamped to the moment of the upgrade. The
 * stamp is the point, not the queueing: `deletedAt` merges as an ordinary scalar (§5.5), decided
 * by `updatedAt`, and the original trashing never advanced it. Left alone it would tie with — or
 * lose to — the peer's live copy, and the notebook would come straight back out of the Trash.
 *
 * `updatedBy` is set to NULL for the same reason [BookRepository.update] does: this write happened
 * on this device, whatever the last peer to touch the row was.
 *
 * Stamping *now* rather than replaying `deletedAt` is deliberate. The instant the user threw the
 * item away is long past, and a peer that has been editing the notebook ever since — because it
 * never left its library — would win every comparison against it, which is precisely the state
 * this migration exists to end. The user's deletion is the intent being honoured, and the Trash is
 * recoverable from either device, so the cost of being wrong is one restore rather than lost ink.
 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        db.execSQL("UPDATE `Notebook` SET `updatedAt` = ?, `updatedBy` = NULL WHERE `deletedAt` IS NOT NULL", arrayOf<Any>(now))
        db.execSQL("UPDATE `Folder` SET `updatedAt` = ?, `updatedBy` = NULL WHERE `deletedAt` IS NOT NULL", arrayOf<Any>(now))

        // INSERT OR IGNORE, and `queuedAt` left alone on a row that already exists: the outbox
        // measures how long the *oldest* unsent change has waited, and an item trashed on a build
        // that already published is legitimately queued already.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `couch_outbox` (`docId`, `queuedAt`)
            SELECT 'notebook:' || `id`, ? FROM `Notebook` WHERE `deletedAt` IS NOT NULL
            """.trimIndent(),
            arrayOf<Any>(now),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `couch_outbox` (`docId`, `queuedAt`)
            SELECT 'folder:' || `id`, ? FROM `Folder` WHERE `deletedAt` IS NOT NULL
            """.trimIndent(),
            arrayOf<Any>(now),
        )
    }
}

/**
 * Gives every loose page a notebook, and takes away the column that let it go without one.
 *
 * Before this migration a page whose `notebookId` was NULL was a "quick note": one sheet parked
 * directly in a library folder through `page.parentFolderId`, created by a button that skipped the
 * new-notebook dialog. The speed was the point, and the speed is kept — but the second kind of thing is not, and
 * this migration is where it ends.
 *
 * It ends because a loose page was never *anywhere*. The protocol gives pages no lifecycle of their
 * own (§6.4 — they live and die inside a notebook's `pageIds`), so `RoomCouchStore.allDocumentIds`
 * enumerated pages through their notebook's manifest and a page with no notebook was never offered
 * to the server. Nothing the user wrote on one has ever reached their iPad, and nothing ever would
 * have: bopa stores pages as files *inside* the notebook directory, so it has no shape to put one
 * in. On top of that a quick page matched no search, could not be starred, and was hard-deleted
 * rather than going to the Trash.
 *
 * **Phase A** gives each one a notebook of its own — reMarkable's "Convert to Notebook", applied
 * once to everything. One notebook per page rather than one shared "Quick notes" book: these pages
 * were written as separate thoughts and merging them would invent a document the user never made.
 * The title is the page's own name if it has one, and otherwise the creation date in the same
 * `dd MMM yyyy` the library already printed under the thumbnail — so the shelf reads afterwards
 * much as the strip read before. The notebook inherits the page's own background and sheet, so a
 * second page added later matches the first, and takes over its `parentFolderId` so the note stays
 * in the folder the user left it in.
 *
 * The outbox rows are the whole point of doing this here rather than lazily. These documents have
 * never been offered to the server even once, so no repair scan will ever notice them —
 * `CouchSyncEngine.neverSent` looks for a mutation site that forgot to queue, and nothing here ever
 * forgot: it was never eligible. Unqueued, a migrated note would sit in the library looking like
 * every other notebook and silently never travel. This is the moment they join sync.
 *
 * `updatedAt` is stamped to now and `updatedBy` cleared for the reason [MIGRATION_45_46] spells
 * out: §5.5 decides a scalar by `updatedAt`, and a stamp left at the original edit would lose to
 * any peer that has touched something since. Here it is close to free — the peer has no copy of
 * these documents at all, so there is nothing to lose against — but the stamp is what makes the
 * first push unambiguous rather than a tie.
 *
 * **Phase B** then rebuilds `page` with `notebookId` NOT NULL and no `parentFolderId` at all.
 * SQLite cannot tighten a column in place, so this is the standard create-copy-drop-rename; it has
 * to run after Phase A, or the copy would fault on the rows Phase A exists to fix. Dropping the
 * column matters as much as tightening the other: `parentFolderId` on a page had no meaning except
 * "this page is filed somewhere a notebook isn't", and a column that can still express the concept
 * is a concept that grows back.
 *
 * A page that somehow reaches this point with no notebook *and* no row to build one from cannot
 * happen — Phase A's cursor covers exactly `notebookId IS NULL` — but if the copy in Phase B were
 * ever to fault, it faults loudly inside the transaction and the upgrade rolls back, which is the
 * right failure: no ink is destroyed, and the database still opens on the old build.
 */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        // Locale-default, matching what the library printed under an unnamed quick page.
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        // ---- Phase A: every loose page gets a notebook of its own ----

        // Read the whole set up front rather than stepping the cursor while writing to the same
        // table: the UPDATE below would otherwise be mutating what is still being iterated.
        val loose = mutableListOf<Array<Any?>>()
        db.query(
            """
            SELECT `id`, `title`, `parentFolderId`, `background`, `backgroundType`,
                   `pageWidth`, `pageHeight`, `createdAt`
            FROM `Page` WHERE `notebookId` IS NULL
            """.trimIndent()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                loose += arrayOf(
                    cursor.getString(0),
                    if (cursor.isNull(1)) null else cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    if (cursor.isNull(5)) null else cursor.getInt(5),
                    if (cursor.isNull(6)) null else cursor.getInt(6),
                    cursor.getLong(7),
                )
            }
        }

        for (row in loose) {
            val pageId = row[0] as String
            val title = row[1] as String?
            val parentFolderId = row[2] as String?
            val background = row[3] as String
            val backgroundType = row[4] as String
            val pageWidth = row[5] as Int?
            val pageHeight = row[6] as Int?
            val createdAt = row[7] as Long

            val notebookId = UUID.randomUUID().toString()
            val notebookTitle = title?.takeIf { it.isNotBlank() }
                ?: dateFormat.format(java.util.Date(createdAt))

            db.execSQL(
                """
                INSERT INTO `Notebook` (
                    `id`, `title`, `openPageId`, `pageIds`, `parentFolderId`,
                    `defaultBackground`, `defaultBackgroundType`,
                    `defaultPageWidth`, `defaultPageHeight`,
                    `bookmarks`, `outline`, `linkedExternalUri`,
                    `createdAt`, `updatedAt`, `deletedAt`, `updatedBy`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', '[]', NULL, ?, ?, NULL, NULL)
                """.trimIndent(),
                arrayOf<Any?>(
                    notebookId,
                    notebookTitle,
                    pageId,
                    // The JSON shape `Converters.fromListString` reads back.
                    "[\"$pageId\"]",
                    parentFolderId,
                    background,
                    backgroundType,
                    pageWidth,
                    pageHeight,
                    createdAt,
                    now,
                ),
            )

            db.execSQL(
                "UPDATE `Page` SET `notebookId` = ?, `updatedAt` = ?, `updatedBy` = NULL WHERE `id` = ?",
                arrayOf<Any?>(notebookId, now, pageId),
            )

            // INSERT OR IGNORE and `queuedAt` left alone on an existing row, as MIGRATION_45_46
            // does: the outbox measures how long the *oldest* unsent change has waited.
            db.execSQL(
                "INSERT OR IGNORE INTO `couch_outbox` (`docId`, `queuedAt`) VALUES (?, ?), (?, ?)",
                arrayOf<Any?>("notebook:$notebookId", now, "page:$pageId", now),
            )
        }

        // ---- Phase B: rebuild `page` without the nullability that made loose pages expressible ----

        db.execSQL(
            """
            CREATE TABLE `Page_new` (
                `id` TEXT NOT NULL,
                `scroll` INTEGER NOT NULL,
                `notebookId` TEXT NOT NULL,
                `background` TEXT NOT NULL DEFAULT 'blank',
                `backgroundType` TEXT NOT NULL DEFAULT 'native',
                `title` TEXT,
                `pageWidth` INTEGER,
                `pageHeight` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `updatedBy` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`notebookId`) REFERENCES `Notebook`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `Page_new` (
                `id`, `scroll`, `notebookId`, `background`, `backgroundType`,
                `title`, `pageWidth`, `pageHeight`, `createdAt`, `updatedAt`, `updatedBy`
            )
            SELECT `id`, `scroll`, `notebookId`, `background`, `backgroundType`,
                   `title`, `pageWidth`, `pageHeight`, `createdAt`, `updatedAt`, `updatedBy`
            FROM `Page`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `Page`")
        db.execSQL("ALTER TABLE `Page_new` RENAME TO `Page`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Page_notebookId` ON `Page` (`notebookId`)")
    }
}

/**
 * Every hand-written migration, in one list, because opening the database at the current version
 * needs *all* of them — the auto-migrations Room generates only cover the gaps between these.
 *
 * One list rather than one per caller: `MigrationTest` reaches the current version too, and it
 * spelled out its own list. That list stopped at [MIGRATION_37_38], so once the schema reached 42
 * every migration test failed with "a migration from N to 42 was required but not found" — the
 * tests had not been run, so nothing said so. A shared list cannot fall behind a schema bump,
 * because the bump that forgets it does not compile against a database that still opens.
 */
val APP_MIGRATIONS = arrayOf(
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_22_23,
    MIGRATION_32_33,
    MIGRATION_37_38,
    MIGRATION_41_42,
    MIGRATION_44_45,
    MIGRATION_45_46,
    MIGRATION_47_48,
)