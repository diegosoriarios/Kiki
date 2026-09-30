package com.diego.kiki.ui

import android.app.Application
import android.view.View
import android.webkit.WebChromeClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.diego.kiki.automation.AutoScroller
import com.diego.kiki.automation.GesturePins
import com.diego.kiki.browser.SearchEngines
import com.diego.kiki.browser.TabManager
import com.diego.kiki.browser.TabState
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.BookmarkRecord
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.HistoryRecord
import com.diego.kiki.data.SiteSettingsRecord
import com.diego.kiki.download.BulkItem
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadProgress
import com.diego.kiki.download.DownloadRequest
import com.diego.kiki.download.DownloadStorage
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.MediaSniffer
import com.diego.kiki.download.MediaTypes
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.security.AppLockManager
import com.diego.kiki.security.SecurePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FindInPageState(
    val isVisible: Boolean = false,
    val query: String = "",
    val activeMatchIndex: Int = 0,
    val numberOfMatches: Int = 0
)

data class FullscreenVideoState(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback
)

data class MediaContextMenuState(
    val url: String
)

class KikiViewModel(application: Application) : AndroidViewModel(application) {

    // Secure prefs declared first — the initial tab restores the last page
    private val securePrefs = SecurePrefs(application)

    val tabManager = TabManager(application, securePrefs.lastUrl)

    val tabs: StateFlow<List<TabState>> = tabManager.tabs
    val activeTabId: StateFlow<String> = tabManager.activeTabId

    val activeTab: StateFlow<TabState?> = combine(tabs, activeTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val isGlobalAdBlockEnabled: StateFlow<Boolean> = tabManager.adBlockRepository.isGlobalAdBlockEnabled
    val bypassedSites: StateFlow<Set<String>> = tabManager.adBlockRepository.bypassedSites

    private val _isShieldDialogVisible = MutableStateFlow(false)
    val isShieldDialogVisible: StateFlow<Boolean> = _isShieldDialogVisible.asStateFlow()

    private val _isSettingsVisible = MutableStateFlow(false)
    val isSettingsVisible: StateFlow<Boolean> = _isSettingsVisible.asStateFlow()

    private val _isDownloadsVisible = MutableStateFlow(false)
    val isDownloadsVisible: StateFlow<Boolean> = _isDownloadsVisible.asStateFlow()

    private val _isHistoryVisible = MutableStateFlow(false)
    val isHistoryVisible: StateFlow<Boolean> = _isHistoryVisible.asStateFlow()

    private val _isBookmarksVisible = MutableStateFlow(false)
    val isBookmarksVisible: StateFlow<Boolean> = _isBookmarksVisible.asStateFlow()

    private val downloadCoordinator = DownloadCoordinator.getInstance(application)

    val appLock = AppLockManager(securePrefs)

    init {
        tabManager.nightModeEnabled = { securePrefs.nightModeEnabled }
        tabManager.onPageCommitted = { url ->
            if (url != "about:blank") securePrefs.lastUrl = url
        }
    }

    // History & bookmarks ---------------------------------------------------------

    private val historyDao = AppDatabase.getDatabase(application).historyDao()
    private val bookmarkDao = AppDatabase.getDatabase(application).bookmarkDao()

    val history: StateFlow<List<HistoryRecord>> = historyDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkRecord>> = bookmarkDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val historyQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val historySuggestions: StateFlow<List<HistoryRecord>> = historyQuery
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList()) else historyDao.search(query, 5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), false)

    fun deleteHistoryEntry(record: HistoryRecord) {
        viewModelScope.launch { historyDao.deleteByUrl(record.url) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }

    fun removeBookmark(record: BookmarkRecord) {
        viewModelScope.launch { bookmarkDao.deleteByUrl(record.url) }
    }

    fun toggleBookmarkForActiveTab() {
        val tab = activeTab.value ?: return
        val url = tab.url
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        viewModelScope.launch {
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

    fun openFromList(url: String) {
        _isHistoryVisible.value = false
        _isBookmarksVisible.value = false
        loadUrl(url)
    }

    // Search engine ----------------------------------------------------------------

    val searchEngineId = MutableStateFlow(securePrefs.searchEngineId)
    val customSearchTemplate = MutableStateFlow(securePrefs.customSearchTemplate)

    fun setSearchEngine(id: String) {
        securePrefs.searchEngineId = id
        searchEngineId.value = id
    }

    fun setCustomSearchTemplate(template: String) {
        securePrefs.customSearchTemplate = template
        customSearchTemplate.value = template
    }

    fun buildSearchUrl(query: String): String =
        SearchEngines.buildUrl(SearchEngines.byId(searchEngineId.value), customSearchTemplate.value, query)

    fun searchAndGo(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        loadUrl(buildSearchUrl(trimmed))
    }

    // Theme --------------------------------------------------------------------------

    val themeMode = MutableStateFlow(securePrefs.themeMode)

    fun setThemeMode(mode: String) {
        securePrefs.themeMode = mode
        themeMode.value = mode
    }

    val saveHistoryEnabled = MutableStateFlow(securePrefs.saveHistoryEnabled)

    fun setSaveHistoryEnabled(enabled: Boolean) {
        securePrefs.saveHistoryEnabled = enabled
        saveHistoryEnabled.value = enabled
    }

    // Night mode ----------------------------------------------------------------------

    val nightModeEnabled = MutableStateFlow(securePrefs.nightModeEnabled)

    fun setNightModeEnabled(enabled: Boolean) {
        securePrefs.nightModeEnabled = enabled
        nightModeEnabled.value = enabled
        tabManager.applyNightModeToAllTabs()
    }

    // Per-site settings ---------------------------------------------------------------

    fun activeSiteHost(): String? =
        tabManager.getActiveTab()?.url?.let { android.net.Uri.parse(it).host?.lowercase() }

    fun getSiteSettings(host: String): SiteSettingsRecord =
        tabManager.siteSettings.get(host) ?: SiteSettingsRecord(host = host)

    fun saveSiteSettings(record: SiteSettingsRecord) {
        val previous = tabManager.siteSettings.get(record.host)
        val structuralChanged =
            previous?.desktopMode != record.desktopMode || previous?.javaScript != record.javaScript
        tabManager.siteSettings.save(record)
        if (structuralChanged) {
            reloadTabsForHost(record.host)
        } else {
            tabManager.applyNightModeToAllTabs()
        }
    }

    fun resetSiteSettings(host: String) {
        tabManager.siteSettings.reset(host)
        tabManager.applyNightModeToAllTabs()
        reloadTabsForHost(host)
    }

    private fun reloadTabsForHost(host: String) {
        val key = host.lowercase()
        tabManager.tabs.value
            .filter { android.net.Uri.parse(it.url).host?.lowercase() == key }
            .forEach { it.webView?.reload() }
    }

    val downloads = downloadCoordinator.allDownloads
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeDownloadProgress: StateFlow<Map<String, DownloadProgress>> =
        downloadCoordinator.activeProgress

    val mediaCandidates = tabManager.mediaCandidates
    val tabsWithSegments = tabManager.tabsWithSegments

    private val _isMediaSheetVisible = MutableStateFlow(false)
    val isMediaSheetVisible: StateFlow<Boolean> = _isMediaSheetVisible.asStateFlow()

    private val _isTabSwitcherVisible = MutableStateFlow(false)
    val isTabSwitcherVisible: StateFlow<Boolean> = _isTabSwitcherVisible.asStateFlow()

    private val _isIncognitoTabGroup = MutableStateFlow(false)
    val isIncognitoTabGroup: StateFlow<Boolean> = _isIncognitoTabGroup.asStateFlow()

    private val _findInPageState = MutableStateFlow(FindInPageState())
    val findInPageState: StateFlow<FindInPageState> = _findInPageState.asStateFlow()

    private val _fullscreenVideoState = MutableStateFlow<FullscreenVideoState?>(null)
    val fullscreenVideoState: StateFlow<FullscreenVideoState?> = _fullscreenVideoState.asStateFlow()

    private val _mediaContextMenu = MutableStateFlow<MediaContextMenuState?>(null)
    val mediaContextMenu: StateFlow<MediaContextMenuState?> = _mediaContextMenu.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(securePrefs.appLockEnabled)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _lockIncognitoOnly = MutableStateFlow(securePrefs.lockIncognitoOnly)
    val lockIncognitoOnly: StateFlow<Boolean> = _lockIncognitoOnly.asStateFlow()

    init {
        tabManager.onFullscreenShowListener = { view, callback ->
            _fullscreenVideoState.value = FullscreenVideoState(view, callback)
        }
        tabManager.onFullscreenHideListener = {
            exitFullscreenVideo()
        }
        tabManager.onMediaLongPressListener = { url ->
            _mediaContextMenu.value = MediaContextMenuState(url)
        }
        tabManager.onBlobEventListener = { tabId, event ->
            downloadCoordinator.handleBlobEvent(tabId, event)
        }
        tabManager.onHistoryPageVisited = { url, title ->
            if (securePrefs.saveHistoryEnabled) {
                viewModelScope.launch {
                    historyDao.insert(
                        HistoryRecord(url, title.ifBlank { url }, System.currentTimeMillis())
                    )
                }
            }
        }
        tabManager.onHistoryTitleUpdated = { url, title ->
            if (securePrefs.saveHistoryEnabled) {
                viewModelScope.launch {
                    historyDao.updateTitleAndTime(url, title, System.currentTimeMillis())
                }
            }
        }
        // If the last incognito tab disappears while locked in
        // incognito-only mode, the lock no longer applies
        viewModelScope.launch {
            tabs.collect { list ->
                if (appLock.isLocked.value &&
                    !appLock.shouldLock(list.any { it.isIncognito })
                ) {
                    appLock.unlock()
                }
            }
        }
    }

    // App lock actions -----------------------------------------------------------

    fun evaluateColdStartLock() {
        appLock.evaluateColdStart(hasIncognitoTabs())
    }

    fun onAppForeground() {
        appLock.onForeground(hasIncognitoTabs())
    }

    fun onAppBackground() {
        appLock.onBackground()
    }

    fun authenticate(activity: androidx.fragment.app.FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        appLock.showPrompt(activity, onSuccess, onError)
    }

    fun setAppLockEnabled(enabled: Boolean) {
        securePrefs.appLockEnabled = enabled
        _appLockEnabled.value = enabled
        if (enabled) {
            // Lock immediately so the user sees the gate works
            appLock.evaluateColdStart(hasIncognitoTabs())
        } else {
            appLock.unlock()
        }
    }

    fun setLockIncognitoOnly(incognitoOnly: Boolean) {
        securePrefs.lockIncognitoOnly = incognitoOnly
        _lockIncognitoOnly.value = incognitoOnly
        if (incognitoOnly && !hasIncognitoTabs()) {
            appLock.unlock()
        } else {
            appLock.evaluateColdStart(hasIncognitoTabs())
        }
    }

    private fun hasIncognitoTabs(): Boolean =
        tabs.value.any { it.isIncognito }

    fun loadUrl(url: String) {
        tabManager.loadUrlInActiveTab(url, buildSearchUrl(""))
    }

    fun goBack(): Boolean {
        return tabManager.goBackInActiveTab()
    }

    fun goForward(): Boolean {
        return tabManager.goForwardInActiveTab()
    }

    fun reload() {
        tabManager.reloadActiveTab()
    }

    fun stopLoading() {
        tabManager.stopActiveTabLoading()
    }

    fun toggleDesktopMode() {
        tabManager.toggleDesktopModeForActiveTab()
    }

    fun createNewTab(initialUrl: String = "about:blank", isIncognito: Boolean = false) {
        tabManager.createNewTab(initialUrl, isIncognito)
        _isTabSwitcherVisible.value = false
    }

    /** Share-target entry point: open the shared URL in a fresh tab. */
    fun openSharedUrl(url: String) {
        createNewTab()
        loadUrl(url)
    }

    fun selectTab(tabId: String) {
        tabManager.selectTab(tabId)
        _isTabSwitcherVisible.value = false
    }

    fun closeTab(tabId: String) {
        tabManager.closeTab(tabId)
    }

    fun closeAllTabs(incognitoOnly: Boolean) {
        tabManager.closeAllTabs(incognitoOnly)
    }

    fun toggleTabSwitcher() {
        val currentTab = activeTab.value
        if (!_isTabSwitcherVisible.value && currentTab != null) {
            _isIncognitoTabGroup.value = currentTab.isIncognito
        }
        _isTabSwitcherVisible.value = !_isTabSwitcherVisible.value
    }

    fun hideTabSwitcher() {
        _isTabSwitcherVisible.value = false
    }

    fun selectIncognitoTabGroup(isIncognito: Boolean) {
        _isIncognitoTabGroup.value = isIncognito
    }

    // Find in Page
    fun showFindInPage() {
        _findInPageState.value = FindInPageState(isVisible = true)
    }

    fun updateFindQuery(query: String) {
        _findInPageState.value = _findInPageState.value.copy(query = query)
        tabManager.findInPage(query) { matchIndex, totalMatches ->
            _findInPageState.value = _findInPageState.value.copy(
                activeMatchIndex = if (totalMatches > 0) matchIndex + 1 else 0,
                numberOfMatches = totalMatches
            )
        }
    }

    fun findNext(forward: Boolean) {
        tabManager.findNext(forward)
    }

    fun hideFindInPage() {
        tabManager.clearMatches()
        _findInPageState.value = FindInPageState(isVisible = false)
    }

    // Shield & Settings Actions
    fun showShieldDialog() {
        _isShieldDialogVisible.value = true
    }

    fun hideShieldDialog() {
        _isShieldDialogVisible.value = false
    }

    fun toggleGlobalAdBlock(enabled: Boolean) {
        tabManager.adBlockRepository.setGlobalAdBlockEnabled(enabled)
    }

    fun toggleSiteBypassForActiveTab() {
        tabManager.toggleSiteBypassForActiveTab()
    }

    fun removeBypassedSite(domain: String) {
        tabManager.adBlockRepository.toggleSiteBypass(domain)
    }

    fun showSettings() {
        _isSettingsVisible.value = true
    }

    fun hideSettings() {
        _isSettingsVisible.value = false
    }

    // Downloads actions
    fun showDownloads() {
        _isDownloadsVisible.value = true
    }

    fun hideDownloads() {
        _isDownloadsVisible.value = false
    }

    // History & bookmarks screens
    fun showHistory() {
        _isHistoryVisible.value = true
    }

    fun hideHistory() {
        _isHistoryVisible.value = false
    }

    fun showBookmarks() {
        _isBookmarksVisible.value = true
    }

    fun hideBookmarks() {
        _isBookmarksVisible.value = false
    }

    fun pauseDownload(id: String) = downloadCoordinator.pause(id)

    fun resumeDownload(id: String) {
        downloadCoordinator.resume(id)
        // Blob transfers need the fetch script re-injected into the page
        val blobTabId = downloadCoordinator.blobJobTabId(id)
        if (blobTabId != null) {
            val record = downloads.value.find { it.id == id }
            if (record != null) {
                injectBlobFetch(id, record.url, blobTabId)
            }
        }
    }

    fun cancelDownload(id: String) = downloadCoordinator.cancel(id)

    fun deleteDownload(record: DownloadRecord) = downloadCoordinator.delete(record)

    fun clearAllDownloads() = downloadCoordinator.clearAll()

    fun exportDownload(record: DownloadRecord) {
        viewModelScope.launch {
            val file = java.io.File(record.filePath)
            if (!file.exists()) {
                android.widget.Toast.makeText(
                    getApplication(), "File not found", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val ok = com.diego.kiki.download.FileExporter.exportToDownloads(
                getApplication(), file, record.mimeType
            ) != null
            android.widget.Toast.makeText(
                getApplication(),
                if (ok) "Exported to Downloads" else "Export failed",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Media sniffer actions
    fun showMediaSheet() {
        // Re-run the DOM scan in case content appeared after PageFinished
        tabManager.resniffActiveTab()
        _isMediaSheetVisible.value = true
    }

    fun hideMediaSheet() {
        _isMediaSheetVisible.value = false
    }

    fun mediaCaptureForActiveTab(): SegmentCandidateRegistry.TabMediaCapture {
        return tabManager.segmentRegistry.captureFor(activeTabId.value)
    }

    fun downloadMediaCandidate(candidate: MediaCandidate) {
        if (candidate.type == MediaTypes.BLOB) {
            downloadBlob(candidate.url)
            return
        }
        if (candidate.type == MediaTypes.HLS) {
            downloadHlsStream(candidate.url)
            return
        }
        startMediaDownload(candidate.url)
        hideMediaSheet()
    }

    fun downloadHlsStream(playlistUrl: String) {
        startHlsDownload(playlistUrl, showToast = true)
        hideMediaSheet()
    }

    private fun startHlsDownload(playlistUrl: String, showToast: Boolean) {
        val tab = activeTab.value
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = playlistUrl,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        val raw = DownloadStorage.fileNameFromUrl(playlistUrl)
        val base = raw.substringBeforeLast('.', missingDelimiterValue = raw)
            .ifBlank { "stream" }
        val fileName = DownloadStorage.sanitizeFileName(base) + ".mp4"
        downloadCoordinator.enqueueHls(playlistUrl, headers, fileName)
        if (showToast) {
            android.widget.Toast.makeText(
                getApplication(), "Stream download started", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Downloads a blob: URL by streaming it out of the page in chunks.
     * The source page must stay open until the transfer finishes.
     */
    fun downloadBlob(blobUrl: String) {
        startBlobDownload(blobUrl)
        hideMediaSheet()
        android.widget.Toast.makeText(
            getApplication(), "Download started — keep this page open", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun startBlobDownload(blobUrl: String): String? {
        val tab = activeTab.value
        val tabId = tab?.id ?: return null
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = tab?.url ?: blobUrl
        )
        val host = try {
            android.net.Uri.parse(tab?.url).host
        } catch (_: Exception) {
            null
        } ?: "video"
        val fileName = DownloadStorage.sanitizeFileName("video-$host-${System.currentTimeMillis() / 1000}") + ".mp4"
        val id = downloadCoordinator.enqueueBlob(
            blobUrl = blobUrl,
            tabId = tabId,
            headers = headers,
            fileName = fileName
        )
        injectBlobFetch(id, blobUrl, tabId)
        return id
    }

    private fun injectBlobFetch(jobKey: String, blobUrl: String, tabId: String) {
        val webView = tabManager.tabs.value.find { it.id == tabId }?.webView
            ?: tabManager.getActiveTab()?.webView
            ?: return
        webView.evaluateJavascript(MediaSniffer.fetchBlobScript(jobKey, blobUrl), null)
    }

    fun dismissMediaContextMenu() {
        _mediaContextMenu.value = null
    }

    fun downloadFromContextMenu() {
        val url = _mediaContextMenu.value?.url ?: return
        _mediaContextMenu.value = null
        startMediaDownload(url)
    }

    private fun startMediaDownload(url: String, showToast: Boolean = true) {
        val tab = activeTab.value
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = url,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        val fileName = DownloadStorage.fileNameFromHeaders(url, null, null)
        downloadCoordinator.enqueue(
            DownloadRequest(
                url = url,
                headers = headers,
                fileName = fileName,
                mimeType = null
            )
        )
        if (showToast) {
            android.widget.Toast.makeText(
                getApplication(), "Download started", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearStorage: Boolean
    ) {
        tabManager.clearBrowsingData(clearHistory, clearCookies, clearCache, clearStorage)
    }

    // Auto-scroll & bulk download ---------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val autoScroller = AutoScroller(viewModelScope) { tabId ->
        tabManager.tabs.value.find { it.id == tabId }
    }

    private val _isAutoScrollPanelVisible = MutableStateFlow(false)
    val isAutoScrollPanelVisible: StateFlow<Boolean> = _isAutoScrollPanelVisible.asStateFlow()

    private val _isBulkSheetVisible = MutableStateFlow(false)
    val isBulkSheetVisible: StateFlow<Boolean> = _isBulkSheetVisible.asStateFlow()

    /** Swipes in a row without a single new capture before we stop. */
    private val smartStopAfterSwipes = 5

    private var lastCaptureCount = 0

    init {
        autoScroller.onSwipe = { tabId -> onAutoScrollSwipe(tabId) }
        autoScroller.onFinished = { _, reason -> onAutoScrollFinished(reason) }
    }

    fun showAutoScrollPanel() {
        _isAutoScrollPanelVisible.value = true
    }

    fun hideAutoScrollPanel() {
        autoScroller.cancelSetup()
        _isAutoScrollPanelVisible.value = false
    }

    fun selectAutoScrollInterval(intervalMs: Long) {
        autoScroller.setInterval(intervalMs)
    }

    fun beginAutoScrollSetup() {
        val tabId = activeTabId.value
        if (tabId.isEmpty()) return
        autoScroller.beginSetup(tabId, autoScroller.state.value.intervalMs)
    }

    fun cancelAutoScrollSetup() {
        autoScroller.cancelSetup()
    }

    fun startAutoScroll(pins: GesturePins) {
        val tabId = activeTabId.value
        if (tabId.isEmpty()) return
        autoScroller.start(tabId, autoScroller.state.value.intervalMs, pins)
    }

    fun stopAutoScroll() {
        autoScroller.stop(AutoScroller.StopReason.MANUAL)
    }

    fun showBulkSheet() {
        _isBulkSheetVisible.value = true
    }

    fun hideBulkSheet() {
        _isBulkSheetVisible.value = false
    }

    /**
     * Everything currently captured for the active tab as bulk-download rows,
     * deduped by URL. DASH is excluded (no pipeline), images skipped.
     */
    fun buildBulkItems(): List<BulkItem> {
        val tabId = activeTabId.value
        val out = LinkedHashMap<String, BulkItem>()
        val capture = tabManager.segmentRegistry.captureFor(tabId)
        // One bulk entry per stream (its best playlist), not per variant
        capture.streamGroups.forEach { group ->
            if (group.kind == MediaTypes.HLS) {
                out.putIfAbsent(group.best.url, BulkItem(group.best.url, group.best.url, MediaTypes.HLS))
            }
        }
        val streamUrls = capture.streamGroups.flatMap { group -> group.variants.map { it.url } }.toSet()
        capture.mediaGroups.forEach { g ->
            out.putIfAbsent(g.sampleUrl, BulkItem(g.sampleUrl, g.sampleUrl, g.kind))
        }
        tabManager.mediaCandidates.value[tabId]?.forEach { c ->
            if (c.type == MediaTypes.IMAGE || c.type == MediaTypes.DASH) return@forEach
            if (c.url in streamUrls) return@forEach
            out.putIfAbsent(c.url, BulkItem(c.url, c.url, c.type))
        }
        return out.values.toList()
    }

    fun bulkDownload(items: List<BulkItem>) {
        if (items.isEmpty()) return
        var started = 0
        var blobs = 0
        items.forEach { item ->
            when (item.type) {
                MediaTypes.HLS -> {
                    startHlsDownload(item.url, showToast = false)
                    started++
                }

                MediaTypes.BLOB -> {
                    if (startBlobDownload(item.url) != null) {
                        blobs++
                        started++
                    }
                }

                MediaTypes.DASH -> Unit // unsupported pipeline

                else -> {
                    startMediaDownload(item.url, showToast = false)
                    started++
                }
            }
        }
        _isBulkSheetVisible.value = false
        android.widget.Toast.makeText(
            getApplication(),
            if (blobs > 0) {
                "$started downloads started — keep this page open until blob videos finish"
            } else {
                "$started downloads started"
            },
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun captureCountFor(tabId: String): Int {
        val domCount = tabManager.mediaCandidates.value[tabId]?.size ?: 0
        val capture = tabManager.segmentRegistry.captureFor(tabId)
        return domCount + capture.playlists.size + capture.mediaGroups.size + capture.segmentGroups.size
    }

    private fun onAutoScrollSwipe(tabId: String) {
        // Re-scan the DOM each swipe so the current slide's video is
        // captured while it is still mounted (blob URLs get revoked later)
        tabManager.tabs.value.find { it.id == tabId }?.webView
            ?.evaluateJavascript(MediaSniffer.INJECT_JS, null)

        val count = captureCountFor(tabId)
        if (count <= lastCaptureCount) {
            val stalled = autoScroller.state.value.stalledSwipes + 1
            autoScroller.setStalled(stalled)
            if (stalled >= smartStopAfterSwipes) {
                autoScroller.stop(AutoScroller.StopReason.SMART_STOP)
            }
        } else {
            autoScroller.setStalled(0)
        }
        lastCaptureCount = count
    }

    private fun onAutoScrollFinished(reason: AutoScroller.StopReason) {
        lastCaptureCount = 0
        when (reason) {
            AutoScroller.StopReason.SMART_STOP -> {
                _isAutoScrollPanelVisible.value = false
                if (buildBulkItems().isNotEmpty()) {
                    _isBulkSheetVisible.value = true
                } else {
                    android.widget.Toast.makeText(
                        getApplication(), "Scroll finished — no videos found",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            AutoScroller.StopReason.TAB_GONE -> {
                _isAutoScrollPanelVisible.value = false
            }

            AutoScroller.StopReason.MANUAL -> Unit // panel stays open
        }
    }


    fun exitFullscreenVideo() {
        _fullscreenVideoState.value?.callback?.onCustomViewHidden()
        _fullscreenVideoState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        tabManager.closeAllTabs(incognitoOnly = false)
    }
}
