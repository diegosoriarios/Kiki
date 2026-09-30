package com.diego.kiki.browser

import android.content.Context
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.SiteSettingsDao
import com.diego.kiki.data.SiteSettingsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-site overrides with a synchronous in-memory snapshot. WebViewClient
 * callbacks run on the main thread and cannot suspend, so lookups hit the
 * cache; writes go through Room first, then refresh the cache.
 */
class SiteSettingsRepository(context: Context) {

    private val dao: SiteSettingsDao = AppDatabase.getDatabase(context).siteSettingsDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = HashMap<String, SiteSettingsRecord>()

    init {
        scope.launch {
            val all = dao.getAll()
            synchronized(cache) {
                cache.clear()
                all.associateByTo(cache) { it.host }
            }
        }
    }

    fun get(host: String?): SiteSettingsRecord? {
        if (host.isNullOrBlank()) return null
        return synchronized(cache) { cache[host.lowercase()] }
    }

    fun hasOverrides(host: String?): Boolean {
        val record = get(host) ?: return false
        return record.desktopMode != SiteSettingsRecord.DEFAULT ||
                record.javaScript != SiteSettingsRecord.DEFAULT ||
                record.nightMode != SiteSettingsRecord.DEFAULT
    }

    fun save(record: SiteSettingsRecord) {
        val normalized = record.copy(host = record.host.lowercase())
        synchronized(cache) { cache[normalized.host] = normalized }
        scope.launch { dao.insert(normalized) }
    }

    /** Removes all overrides for the host (back to app defaults). */
    fun reset(host: String) {
        val key = host.lowercase()
        synchronized(cache) { cache.remove(key) }
        scope.launch { dao.deleteByHost(key) }
    }

    fun currentSnapshot(): Map<String, SiteSettingsRecord> =
        synchronized(cache) { cache.toMap() }
}
