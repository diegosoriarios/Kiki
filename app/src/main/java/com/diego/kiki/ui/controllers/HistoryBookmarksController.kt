package com.diego.kiki.ui.controllers

import android.content.Context
import com.diego.kiki.browser.TabManager
import com.diego.kiki.browser.TabState
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.BookmarkRecord
import com.diego.kiki.data.HistoryRecord
import com.diego.kiki.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History and bookmark storage, URL search suggestions and per-tab bookmark state. */
class HistoryBookmarksController(
    private val scope: CoroutineScope,
    context: Context,
    private val securePrefs: SecurePrefs,
    private val tabManager: TabManager,
    private val activeTab: StateFlow<TabState?>
) {
    private val historyDao = AppDatabase.getDatabase(context).historyDao()
    private val bookmarkDao = AppDatabase.getDatabase(context).bookmarkDao()

    val history: StateFlow<List<HistoryRecord>> = historyDao.getAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkRecord>> = bookmarkDao.getAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val historyQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val historySuggestions: StateFlow<List<HistoryRecord>> = historyQuery
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList()) else historyDao.search(query, 5)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(500), emptyList())

    fun updateHistoryQuery(query: String) {
        historyQuery.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentTabBookmarked: StateFlow<Boolean> = activeTab
        .flatMapLatest { tab ->
            val url = tab?.url
            if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                flowOf(false)
            } else {
                bookmarkDao.observeBookmarked(url)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(500), false)

    fun deleteHistoryEntry(record: HistoryRecord) {
        scope.launch { historyDao.deleteByUrl(record.url) }
    }

    fun clearAllHistory() {
        scope.launch { historyDao.clearAll() }
    }

    fun removeBookmark(record: BookmarkRecord) {
        scope.launch { bookmarkDao.deleteByUrl(record.url) }
    }

    fun toggleBookmarkForActiveTab() {
        val tab = activeTab.value ?: return
        val url = tab.url
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        scope.launch {
            if (bookmarkDao.observeBookmarked(url).first()) {
                bookmarkDao.deleteByUrl(url)
            } else {
                bookmarkDao.insert(
                    BookmarkRecord(
                        url = url,
                        title = tab.title.ifBlank { url },
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** Wires the page-visit history writers. Call once from the ViewModel init. */
    fun observePageVisits() {
        tabManager.onHistoryPageVisited = { url, title ->
            if (securePrefs.saveHistoryEnabled) {
                scope.launch {
                    historyDao.insert(
                        HistoryRecord(url, title.ifBlank { url }, System.currentTimeMillis())
                    )
                }
            }
        }
        tabManager.onHistoryTitleUpdated = { url, title ->
            if (securePrefs.saveHistoryEnabled) {
                scope.launch {
                    historyDao.updateTitleAndTime(url, title, System.currentTimeMillis())
                }
            }
        }
    }
}
