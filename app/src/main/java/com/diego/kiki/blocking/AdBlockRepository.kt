package com.diego.kiki.blocking

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdBlockRepository(private val context: Context) {

    private val matcher = AdBlockMatcher()
    private val prefs = context.getSharedPreferences("kiki_adblock_prefs", Context.MODE_PRIVATE)

    private val _isGlobalAdBlockEnabled = MutableStateFlow(
        prefs.getBoolean("global_adblock_enabled", true)
    )
    val isGlobalAdBlockEnabled: StateFlow<Boolean> = _isGlobalAdBlockEnabled.asStateFlow()

    private val _bypassedSites = MutableStateFlow<Set<String>>(
        prefs.getStringSet("bypassed_sites", emptySet()) ?: emptySet()
    )
    val bypassedSites: StateFlow<Set<String>> = _bypassedSites.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            loadBundledRules()
        }
    }

    private suspend fun loadBundledRules() = withContext(Dispatchers.IO) {
        try {
            val lines = context.assets.open("easylist_snapshot.txt").bufferedReader().use { reader ->
                reader.readLines()
            }
            matcher.loadRules(lines)
            _isInitialized.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setGlobalAdBlockEnabled(enabled: Boolean) {
        _isGlobalAdBlockEnabled.value = enabled
        prefs.edit().putBoolean("global_adblock_enabled", enabled).apply()
    }

    fun toggleSiteBypass(domain: String) {
        val cleanDomain = domain.lowercase().trim()
        if (cleanDomain.isEmpty()) return

        val currentSet = _bypassedSites.value.toMutableSet()
        if (currentSet.contains(cleanDomain)) {
            currentSet.remove(cleanDomain)
        } else {
            currentSet.add(cleanDomain)
        }

        _bypassedSites.value = currentSet
        prefs.edit().putStringSet("bypassed_sites", currentSet).apply()
    }

    fun isSiteBypassed(domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        val cleanDomain = domain.lowercase().trim()
        return _bypassedSites.value.contains(cleanDomain)
    }

    fun shouldBlockRequest(requestUri: Uri, pageHost: String?): Boolean {
        if (!_isGlobalAdBlockEnabled.value) return false
        if (isSiteBypassed(pageHost)) return false
        if (!_isInitialized.value) return false

        return matcher.shouldBlock(requestUri, pageHost)
    }
}
