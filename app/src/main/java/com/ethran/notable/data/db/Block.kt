package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.ethran.notable.sync.SyncClock
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * A block of page content that is not ink: typed markdown, a placed picture, a recording, or a
 * grouping of strokes. The storage half of the couch protocol's `CouchBlock` (§3.3.1).
 *
 * A block with neither [x] nor [y] joins the page's linear flow, and the flowing blocks in
 * `(orderKey, id)` order are a markdown document. One that declares both sits at that point on the
 * canvas, like a placed [Image].
 *
 * **Typed columns for what SQL sorts and filters on; JSON for the rest.** [orderKey], [kind] and
 * the geometry are real columns because the flow query orders and filters by them, and [text]
 * is one because it is the thing an export reads and a search will one day match. Everything
 * kind-specific — the picture's asset, a recording's segments, an ink grouping's stroke ids — goes
 * in [payload], decoded leniently. That is the same division [Notebook.bookmarks] and
 * [Notebook.outline] already make, and for the same reason: a column per kind-specific field would
 * make every future block kind a schema bump on a fourteen-entity database plus a mandatory
 * migration test, and nothing queries those fields.
 */
@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [androidx.room.Index(value = ["pageId", "orderKey"])],
)
data class Block(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val pageId: String,

    /** `md` | `image` | `audio` | `ink`, carried verbatim even when this build does not know it. */
    @ColumnInfo(defaultValue = "md") val kind: String = "md",

    /**
     * The fractional index deciding where this block sits in the flow. Compared bytewise by the
     * merge; empty is legal and sorts first. See `CouchBlock.orderKey` for why order lives on the
     * block rather than in a list on the page.
     */
    @ColumnInfo(defaultValue = "") val orderKey: String = "",

    /** Markdown source, for `kind == "md"`. */
    val text: String? = null,

    /** Kind-specific fields as JSON — see the class comment. `{}` when there are none. */
    @ColumnInfo(defaultValue = "{}") val payload: String = "{}",

    /** Page units, top-left. Both null means flowing; see the class comment. */
    val x: Int? = null,
    val y: Int? = null,
    val width: Int? = null,
    val height: Int? = null,

    /** When a recording started, for `kind == "audio"`; the anchor ink replay measures from. */
    val startedAt: Date? = null,

    val createdAt: Date = SyncClock.nowDate(),
    val updatedAt: Date = SyncClock.nowDate(),
    @ColumnInfo(defaultValue = "") val deviceId: String = "",
)

@Dao
interface BlockDao {
    /** A page's blocks in flow order. `orderKey` then `id`, matching the merge's sort. */
    @Query("SELECT * FROM Block WHERE pageId = :pageId ORDER BY orderKey, id")
    suspend fun getByPage(pageId: String): List<Block>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<Block>)

    @Update
    suspend fun updateAll(blocks: List<Block>)

    @Query("DELETE FROM Block WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Every distinct asset a block anywhere in the library names, as raw payload JSON.
     *
     * Read by the sync layer to work out which bytes are still missing. It returns the payloads
     * rather than the ids because the ids live inside the JSON, and teaching SQLite to reach into
     * it would be a second, worse implementation of the decoder that already exists.
     */
    @Query("SELECT payload FROM Block WHERE payload != '{}'")
    suspend fun getPayloads(): List<String>
}

/**
 * A block removed from a page here — the typed-content counterpart of [DeletedImage].
 *
 * Same shape and the same reasoning: a page's blocks merge as a union keyed by id, so a block that
 * is merely absent is indistinguishable from one that has not arrived yet. Sound for a block for
 * the same reason it is sound for a stroke — retyping a deleted paragraph mints a new id, so
 * "remove wins" can never suppress later work.
 *
 * Deliberately has **no** foreign key to [Page], and is pruned by age.
 */
@Entity
data class DeletedBlock(
    @PrimaryKey val blockId: String,
    @ColumnInfo(index = true) val pageId: String,
    val deletedAt: Date,
)

@Dao
interface DeletedBlockDao {
    /**
     * Records removals. **Ignores** ids already tombstoned: protocol §6.6 requires a tombstone to
     * keep its original `deletedAt`, because re-stamping it on every save would let an arbitrarily
     * later timestamp win a delete-vs-edit comparison it should lose.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DeletedBlock>)

    /**
     * Writes tombstones that arrived from a merge, whose `deletedAt` is already the earliest of the
     * two sides — so here the incoming value must *replace* whatever this device recorded.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DeletedBlock>)

    @Query("SELECT * FROM DeletedBlock WHERE pageId = :pageId ORDER BY blockId")
    suspend fun getByPage(pageId: String): List<DeletedBlock>

    @Query("DELETE FROM DeletedBlock WHERE blockId IN (:blockIds)")
    suspend fun deleteByIds(blockIds: List<String>)

    /** Prunes tombstones older than [cutoff] (epoch millis). */
    @Query("DELETE FROM DeletedBlock WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

class BlockRepository @Inject constructor(
    private val db: BlockDao
) {
    suspend fun getByPage(pageId: String): List<Block> = db.getByPage(pageId)

    suspend fun upsertAll(blocks: List<Block>) {
        if (blocks.isEmpty()) return
        blocks.chunked(900).forEach { db.upsertAll(it) }
    }

    suspend fun deleteByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(900).forEach { db.deleteByIds(it) }
    }

    suspend fun getPayloads(): List<String> = db.getPayloads()
}

class DeletedBlockRepository @Inject constructor(
    private val db: DeletedBlockDao
) {
    suspend fun record(pageId: String, blockIds: List<String>, deletedAt: Date = SyncClock.nowDate()) {
        if (pageId.isEmpty() || blockIds.isEmpty()) return
        blockIds.chunked(900).forEach { batch ->
            db.insertAll(batch.map {
                DeletedBlock(blockId = it, pageId = pageId, deletedAt = deletedAt)
            })
        }
    }

    suspend fun upsertAll(rows: List<DeletedBlock>) {
        if (rows.isEmpty()) return
        rows.chunked(900).forEach { db.upsertAll(it) }
    }

    suspend fun getByPage(pageId: String): List<DeletedBlock> = db.getByPage(pageId)

    suspend fun deleteByIds(blockIds: List<String>) {
        if (blockIds.isEmpty()) return
        blockIds.chunked(900).forEach { db.deleteByIds(it) }
    }

    suspend fun deleteOlderThan(cutoffMillis: Long) = db.deleteOlderThan(cutoffMillis)
}
