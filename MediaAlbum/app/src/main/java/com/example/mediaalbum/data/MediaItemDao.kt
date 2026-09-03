package com.example.mediaalbum.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    /** albumId == null -> 全部；否则只取该分类 */
    @Query(
        "SELECT * FROM media_items " +
            "WHERE (:albumId IS NULL) OR albumId = :albumId " +
            "ORDER BY addedAt DESC"
    )
    fun observeByAlbum(albumId: Long?): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE albumId IS NULL ORDER BY addedAt DESC")
    fun observeUncategorized(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun get(id: Long): MediaItem?

    @Query("SELECT COUNT(*) FROM media_items WHERE originalName = :originalName AND fileSize = :fileSize")
    suspend fun countByOriginalNameAndSize(originalName: String, fileSize: Long): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM media_items")
    suspend fun maxPosition(): Int

    @Query("UPDATE media_items SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Insert
    suspend fun insert(item: MediaItem): Long

    @Update
    suspend fun update(item: MediaItem)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE media_items SET albumId = :albumId WHERE id = :id")
    suspend fun setAlbum(id: Long, albumId: Long?)
}
