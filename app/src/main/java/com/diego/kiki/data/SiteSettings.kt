package com.diego.kiki.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Per-site overrides keyed by host. Tri-state ints: -1 = default (app-wide
 * behavior), 0 = explicitly off, 1 = explicitly on.
 */
@Entity(tableName = "site_settings")
data class SiteSettingsRecord(
    @PrimaryKey val host: String,
    val desktopMode: Int = DEFAULT,
    val javaScript: Int = DEFAULT,
    val nightMode: Int = DEFAULT
) {
    companion object {
        const val DEFAULT = -1
        const val OFF = 0
        const val ON = 1

        fun triValue(value: Int?): Int = value ?: DEFAULT
    }
}

@Dao
interface SiteSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SiteSettingsRecord)

    @Query("SELECT * FROM site_settings")
    suspend fun getAll(): List<SiteSettingsRecord>

    @Query("SELECT * FROM site_settings")
    fun observeAll(): Flow<List<SiteSettingsRecord>>

    @Query("SELECT * FROM site_settings WHERE host = :host")
    suspend fun getByHost(host: String): SiteSettingsRecord?

    @Query("DELETE FROM site_settings WHERE host = :host")
    suspend fun deleteByHost(host: String)
}
