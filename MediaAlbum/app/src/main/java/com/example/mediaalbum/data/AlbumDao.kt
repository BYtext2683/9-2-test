package com.example.mediaalbum.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun get(id: Long): Album?

    @Query("SELECT COUNT(*) FROM albums WHERE name = :name")
    suspend fun countByName(name: String): Int

    @Insert
    suspend fun insert(album: Album): Long

    @Update
    suspend fun update(album: Album)

    @Delete
    suspend fun delete(album: Album)
}
