package com.ethran.notable.data.db

import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
)