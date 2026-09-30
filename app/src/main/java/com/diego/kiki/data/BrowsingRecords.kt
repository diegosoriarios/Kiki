package com.diego.kiki.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryRecord(
    @PrimaryKey val url: String,
    val title: String,
    val visitedAt: Long
)

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryRecord)

    @Query("UPDATE history SET title = :title, visitedAt = :visitedAt WHERE url = :url")
    suspend fun updateTitleAndTime(url: String, title: String, visitedAt: Long)

    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun getAll(): Flow<List<HistoryRecord>>

    @Query(
        "SELECT * FROM history WHERE " +
                "url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
                "ORDER BY visitedAt DESC LIMIT :limit"
    )
    fun search(query: String, limit: Int): Flow<List<HistoryRecord>>

    @Query("SELECT * FROM history WHERE url LIKE :prefix || '%' ORDER BY visitedAt DESC LIMIT :limit")
    fun searchPrefix(prefix: String, limit: Int): Flow<List<HistoryRecord>>

    @Query("DELETE FROM history WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Entity(tableName = "bookmarks")
data class BookmarkRecord(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long
)

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BookmarkRecord)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BookmarkRecord>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeBookmarked(url: String): Flow<Boolean>

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}
