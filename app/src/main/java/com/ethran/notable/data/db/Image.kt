package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.Date
import java.util.UUID
import javax.inject.Inject


// Entity class for images
@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Image(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    var x: Int = 0,
    var y: Int = 0,
    val height: Int,
    val width: Int,

    // use uri instead of bytearray
    //val bitmap: ByteArray,
    val uri: String? = null,

    @ColumnInfo(index = true)
    val pageId: String,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO for image operations
@Dao
interface ImageDao {
    @Insert
    suspend fun create(image: Image): Long

    @Insert
    suspend fun create(images: List<Image>)

    @Update
    suspend fun update(image: Image)

    @Update
    suspend fun update(images: List<Image>)

    @Query("DELETE FROM Image WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM Image WHERE id = :imageId")
    suspend fun getById(imageId: String): Image

    /** URIs only, for a page — used by sync GC to enumerate referenced media without loading
     *  the page's strokes (see NotebookSyncService.garbageCollectRemote). */
    @Query("SELECT uri FROM Image WHERE pageId = :pageId")
    suspend fun getUrisForPage(pageId: String): List<String?>

    /** Every distinct image referenced anywhere — what CouchDB sync checks for missing blobs. */
    @Query("SELECT DISTINCT uri FROM Image WHERE uri IS NOT NULL")
    suspend fun getAllUris(): List<String>

    /** Where this page's lowest-placed image sits — the image half of [StrokeDao.maxTop]. */
    @Query("SELECT MAX(y) FROM Image WHERE pageId = :pageId")
    suspend fun maxY(pageId: String): Int?

    /** Which of these ids exist at all, on any page — the image half of [StrokeDao.existingIds]. */
    @Query("SELECT id FROM Image WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>
}

// Repository for image operations
class ImageRepository @Inject constructor(
    private val db: ImageDao
) {

    suspend fun create(image: Image): Long {
        return db.create(image)
    }

    suspend fun create(
        imageUri: String,
        //position on canvas
        x: Int,
        y: Int,
        pageId: String,
        //size on canvas
        width: Int,
        height: Int
    ): Long {
        // Prepare the Image object with specified placement
        val imageToSave = Image(
            x = x,
            y = y,
            width = width,
            height = height,
            uri = imageUri,
            pageId = pageId
        )

        // Save the image to the database
        return db.create(imageToSave)
    }

    suspend fun create(images: List<Image>) {
        db.create(images)
    }

    suspend fun update(image: Image) {
        db.update(image)
    }

    suspend fun update(images: List<Image>) {
        db.update(images)
    }

    suspend fun deleteAll(ids: List<String>) {
        db.deleteAll(ids)
    }

    suspend fun getUrisForPage(pageId: String): List<String?> {
        return db.getUrisForPage(pageId)
    }

    suspend fun getAllUris(): List<String> {
        return db.getAllUris()
    }

    /** The y of the lowest-placed image on the page, or null when it has none. */
    suspend fun maxY(pageId: String): Int? {
        return db.maxY(pageId)
    }

    /** The subset of [ids] that already exist, on whatever page. */
    suspend fun existingIds(ids: List<String>): Set<String> {
        return ids.chunked(900).flatMap { db.existingIds(it) }.toSet()
    }

    suspend fun getImageWithPointsById(imageId: String): Image {
        return db.getById(imageId)
    }
}