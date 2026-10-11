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
import com.diego.kiki.data.BookmarkRecord
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.HistoryRecord
import com.diego.kiki.data.SiteSettingsRecord
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadProgress
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.security.AppLockManager
import com.diego.kiki.security.SecurePrefs
import com.diego.kiki.ui.controllers.AutoScrollController
import com.diego.kiki.ui.controllers.HistoryBookmarksController
import com.diego.kiki.ui.controllers.MediaDownloadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/**
 * Thin orchestration root: owns the tab manager, app-level UI visibility
 * state and preferences; feature logic lives in the controllers
 * ([MediaDownloadController], [HistoryBookmarksController],
 * [AutoScrollController]) and in [DownloadCoordinator].
 */
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

    private val _isMediaSheetVisible = MutableStateFlow(false)
    val isMediaSheetVisible: StateFlow<Boolean> = _isMediaSheetVisible.asStateFlow()

    private val _isTabSwitcherVisible = MutableStateFlow(false)
    val isTabSwitcherVisible: StateFlow<Boolean> = _isTabSwitcherVisible.asStateFlow()

    private val _isIncognitoTabGroup = MutableStateFlow(false)
    val isIncognitoTabGroup: StateFlow<Boolean> = _isIncognitoTabGroup.asStateFlow()

    private val _isAutoScrollPanelVisible = MutableStateFlow(false)
    val isAutoScrollPanelVisible: StateFlow<Boolean> = _isAutoScrollPanelVisible.asStateFlow()

    private val _isBulkSheetVisible = MutableStateFlow(false)
    val isBulkSheetVisible: StateFlow<Boolean> = _isBulkSheetVisible.asStateFlow()

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

    private val downloadCoordinator = DownloadCoordinator.getInstance(application)

    val appLock = AppLockManager(securePrefs)

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

    // Controllers ---------------------------------------------------------------------

    private val historyBookmarks = HistoryBookmarksController(
        scope = viewModelScope,
        context = application,
        securePrefs = securePrefs,
        tabManager = tabManager,
        activeTab = activeTab
    )

    val history get() = historyBookmarks.history
    val bookmarks get() = historyBookmarks.bookmarks
    val historySuggestions get() = historyBookmarks.historySuggestions
    val isCurrentTabBookmarked get() = historyBookmarks.isCurrentTabBookmarked

    private val mediaController = MediaDownloadController(
        context = application,
        scope = viewModelScope,
        tabManager = tabManager,
        coordinator = downloadCoordinator,
        activeTab = activeTab,
        activeTabId = activeTabId,
        downloads = downloads
    ) {
        _isMediaSheetVisible.value = false
    }

    val variantQuality get() = mediaController.variantQuality

    val autoScrollController = AutoScrollController(viewModelScope, tabManager, activeTabId)

    val autoScroller get() = autoScrollController.autoScroller

    init {
        tabManager.nightModeEnabled = { securePrefs.nightModeEnabled }
        tabManager.onPageCommitted = { url ->
            if (url != "about:blank") securePrefs.lastUrl = url
        }
    }

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
        historyBookmarks.observePageVisits()
        autoScrollController.autoScroller.onSwipe = { tabId ->
            autoScrollController.onSwipe(tabId)
        }
        autoScrollController.autoScroller.onFinished = { _, reason ->
            onAutoScrollFinished(reason)
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

    // History & bookmarks ---------------------------------------------------------

    fun updateHistoryQuery(query: String) = historyBookmarks.updateHistoryQuery(query)

    fun deleteHistoryEntry(record: HistoryRecord) = historyBookmarks.deleteHistoryEntry(record)

    fun clearAllHistory() = historyBookmarks.clearAllHistory()

    fun removeBookmark(record: BookmarkRecord) = historyBookmarks.removeBookmark(record)

    fun toggleBookmarkForActiveTab() = historyBookmarks.toggleBookmarkForActiveTab()

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

    fun resumeDownload(id: String) = mediaController.resumeDownload(id)

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

    fun backfillThumbnail(record: DownloadRecord) = mediaController.backfillThumbnail(record)

    // Media sniffer actions
    fun showMediaSheet() {
        // Re-run the DOM scan in case content appeared after PageFinished
        tabManager.resniffActiveTab()
        mediaController.refreshStreamQualities()
        _isMediaSheetVisible.value = true
    }

    fun hideMediaSheet() {
        _isMediaSheetVisible.value = false
    }

    fun mediaCaptureForActiveTab(): SegmentCandidateRegistry.TabMediaCapture {
        return tabManager.segmentRegistry.captureFor(activeTabId.value)
    }

    fun downloadMediaCandidate(candidate: MediaCandidate) = mediaController.downloadMediaCandidate(candidate)

    fun downloadHlsStream(
        playlistUrl: String,
        qualityLabel: String? = null,
        thumbnailUrl: String? = null
    ) = mediaController.downloadHlsStream(playlistUrl, qualityLabel, thumbnailUrl)

    fun dismissMediaContextMenu() {
        _mediaContextMenu.value = null
    }

    fun downloadFromContextMenu() {
        val url = _mediaContextMenu.value?.url ?: return
        _mediaContextMenu.value = null
        mediaController.downloadUrl(url)
    }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearStorage: Boolean
    ) {
        tabManager.clearBrowsingData(clearHistory, clearCookies, clearCache, clearStorage)
    }

    // Page recording ----------------------------------------------------------------

    /**
     * Starts capturing the active tab's WebView (page content only — no system
     * consent dialog). False when no page is laid out yet.
     */
    fun startScreenRecording(): Boolean =
        com.diego.kiki.screenrec.ScreenRecorder.start(getApplication()) {
            tabManager.getActiveTab()?.webView
        }

    fun stopScreenRecording() = com.diego.kiki.screenrec.ScreenRecorder.stop()

    // Auto-scroll & bulk download ---------------------------------------------------

    fun showAutoScrollPanel() {
        _isAutoScrollPanelVisible.value = true
    }

    fun hideAutoScrollPanel() {
        autoScrollController.cancelSetup()
        _isAutoScrollPanelVisible.value = false
    }

    fun selectAutoScrollInterval(intervalMs: Long) = autoScrollController.setInterval(intervalMs)

    fun beginAutoScrollSetup() = autoScrollController.beginSetup()

    fun cancelAutoScrollSetup() = autoScrollController.cancelSetup()

    fun startAutoScroll(pins: GesturePins) = autoScrollController.start(pins)

    fun stopAutoScroll() = autoScrollController.stop()

    fun showBulkSheet() {
        _isBulkSheetVisible.value = true
    }

    fun hideBulkSheet() {
        _isBulkSheetVisible.value = false
    }

    fun buildBulkItems() = mediaController.buildBulkItems()

    fun bulkDownload(items: List<com.diego.kiki.download.BulkItem>) {
        mediaController.bulkDownload(items)
        _isBulkSheetVisible.value = false
    }

    private fun onAutoScrollFinished(reason: AutoScroller.StopReason) {
        autoScrollController.resetCaptureTracking()
        when (reason) {
            AutoScroller.StopReason.SMART_STOP -> {
                _isAutoScrollPanelVisible.value = false
                if (mediaController.buildBulkItems().isNotEmpty()) {
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
