package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ethran.notable.sync.SyncClock
import java.util.Date
import javax.inject.Inject

/**
 * A page's handwriting, as text.
 *
 * Derived data: it is regenerable from ink at any time, so nothing here is precious, and it
 * deliberately takes no part in the library's sync. It travels to the other device and to
 * Obsidian through a separate CouchDB database — see docs/recognized-text.md in the bopa repo,
 * which is the contract this table and its pusher implement.
 */
@Entity(
    tableName = "page_text",
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PageText(
    @PrimaryKey
    val pageId: String,

    val text: String,

    /** Which recognizer produced [text]; informational, never a freshness signal. */
    val engine: String,

    /** BCP-47, or null when the engine did not report one. */
    val language: String? = null,

    /**
     * The page's `updatedAt` at the moment the recognized strokes were read. The page having
     * moved past this is what makes the text stale — see [isStaleFor].
     */
    val recognizedClock: Long,

    val updatedAt: Date = SyncClock.nowDate(),

    /**
     * Whether this row still has to reach the server. Set on every local recognition, cleared
     * once the push lands, so text written while offline is not lost.
     */
    @ColumnInfo(defaultValue = "1")
    val pendingPush: Boolean = true,
)

/** True when [page] has been edited since this text was recognized from it. */
fun PageText.isStaleFor(page: Page): Boolean = page.updatedAt.time > recognizedClock

@Dao
interface PageTextDao {
    @Query("SELECT * FROM page_text WHERE pageId = :pageId")
    suspend fun get(pageId: String): PageText?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(text: PageText)

    @Query("DELETE FROM page_text WHERE pageId = :pageId")
    suspend fun delete(pageId: String)

    @Query("UPDATE page_text SET pendingPush = 0 WHERE pageId = :pageId AND updatedAt = :updatedAt")
    suspend fun clearPending(pageId: String, updatedAt: Date)

    @Query("SELECT * FROM page_text WHERE pendingPush != 0")
    suspend fun pending(): List<PageText>

    /**
     * Notebooks holding a page whose text contains [query]. LIKE with a leading wildcard cannot
     * use an index, which is fine at a personal library's scale; if that stops being true the
     * answer is an FTS table behind this same call.
     */
    @Query(
        """
        SELECT DISTINCT p.notebookId FROM page_text t
        JOIN page p ON p.id = t.pageId
        WHERE t.text LIKE '%' || :query || '%'
        """
    )
    suspend fun notebooksMatching(query: String): List<String>

    /** Pages whose text contains [query], newest first. */
    @Query(
        """
        SELECT t.pageId FROM page_text t
        JOIN page p ON p.id = t.pageId
        WHERE p.notebookId = :notebookId AND t.text LIKE '%' || :query || '%'
        ORDER BY t.updatedAt DESC
        """
    )
    suspend fun pagesMatching(notebookId: String, query: String): List<String>
}

class PageTextRepository @Inject constructor(
    private val db: PageTextDao,
) {
    suspend fun get(pageId: String): PageText? = db.get(pageId)

    suspend fun save(text: PageText) = db.upsert(text)

    suspend fun delete(pageId: String) = db.delete(pageId)

    /**
     * Marks [text] as pushed, but only if it has not been recognized again in the meantime —
     * matching on `updatedAt` keeps a slow push from marking a newer recognition as sent.
     */
    suspend fun markPushed(text: PageText) = db.clearPending(text.pageId, text.updatedAt)

    suspend fun pendingPush(): List<PageText> = db.pending()

    suspend fun notebooksMatching(query: String): List<String> =
        if (query.isBlank()) emptyList() else db.notebooksMatching(query.trim())

    suspend fun pagesMatching(notebookId: String, query: String): List<String> =
        if (query.isBlank()) emptyList() else db.pagesMatching(notebookId, query.trim())
}
