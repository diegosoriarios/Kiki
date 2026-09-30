package com.diego.kiki.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import com.diego.kiki.blocking.AdBlockRepository
import com.diego.kiki.data.SiteSettingsRecord
import com.diego.kiki.download.BlobEvent
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadRequest
import com.diego.kiki.download.DownloadStorage
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.MediaSniffer
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.engine.EngineClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TabManager(
    private val context: Context,
    initialTabUrl: String = "about:blank"
) : EngineClient {

    val adBlockRepository = AdBlockRepository(context)
    val downloadCoordinator = DownloadCoordinator.getInstance(context)
    val segmentRegistry = SegmentCandidateRegistry()
    val siteSettings = SiteSettingsRepository(context)

    /** Current global night-mode flag — wired by the ViewModel from SecurePrefs. */
    var nightModeEnabled: () -> Boolean = { false }

    private val _mediaCandidates =
        MutableStateFlow<Map<String, List<MediaCandidate>>>(emptyMap())
    val mediaCandidates: StateFlow<Map<String, List<MediaCandidate>>> =
        _mediaCandidates.asStateFlow()

    private val _tabsWithSegments = MutableStateFlow<Set<String>>(emptySet())
    val tabsWithSegments: StateFlow<Set<String>> = _tabsWithSegments.asStateFlow()

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private var defaultUserAgent: String = ""

    // Callback for video fullscreen events from WebChromeClient
    var onFullscreenShowListener: ((View, android.webkit.WebChromeClient.CustomViewCallback) -> Unit)? = null
    var onFullscreenHideListener: (() -> Unit)? = null

    // Callback for long-press on a media element (image / direct media URL)
    var onMediaLongPressListener: ((url: String) -> Unit)? = null

    // Callback for blob events (found / transfer chunks) from the JS bridge
    var onBlobEventListener: ((tabId: String, event: BlobEvent) -> Unit)? = null

    // History hooks — invoked only for non-incognito tabs
    var onHistoryPageVisited: ((url: String, title: String) -> Unit)? = null
    var onHistoryTitleUpdated: ((url: String, title: String) -> Unit)? = null

    /** Committed page URL, non-incognito tabs only (last-page restore). */
    var onPageCommitted: ((url: String) -> Unit)? = null

    init {
        segmentRegistry.onGroupsChanged = { tabId, hasGroups ->
            _tabsWithSegments.update { current ->
                if (hasGroups) current + tabId else current - tabId
            }
        }
        // Create an initial tab (homepage renders for about:blank)
        createNewTab(initialUrl = initialTabUrl, isIncognito = false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createNewTab(initialUrl: String = "https://www.google.com", isIncognito: Boolean = false): String {
        val tabId = java.util.UUID.randomUUID().toString()
        val webView = WebView(context).apply {
            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            if (defaultUserAgent.isEmpty()) {
                defaultUserAgent = settings.userAgentString
            }

            if (isIncognito) {
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            } else {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            webViewClient = KikiWebViewClient(context, tabId, this@TabManager, adBlockRepository, segmentRegistry)
            webChromeClient = KikiWebChromeClient(tabId, this@TabManager)
            addJavascriptInterface(
                MediaSniffer(
                    tabId,
                    onCandidates = { id, candidates -> mergeMediaCandidates(id, candidates) },
                    onPlaylist = { id, url, kind, isMaster ->
                        val role = if (isMaster) {
                            com.diego.kiki.download.SegmentCandidateRegistry.PlaylistCapture.ROLE_MASTER
                        } else {
                            com.diego.kiki.download.SegmentCandidateRegistry.PlaylistCapture.ROLE_MEDIA
                        }
                        segmentRegistry.addPlaylist(id, url, kind, role)
                    },
                    onBlobEvent = { id, event -> onBlobEventListener?.invoke(id, event) }
                ).Bridge(),
                MediaSniffer.BRIDGE_NAME
            )
            setOnLongClickListener {
                val mediaUrl = extractLongPressMediaUrl(hitTestResult)
                if (mediaUrl != null) {
                    onMediaLongPressListener?.invoke(mediaUrl)
                    true
                } else {
                    false
                }
            }
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val request = DownloadRequest(
                    url = url,
                    headers = DownloadRequest.pageHeaders(
                        pageUrl = this@apply.url,
                        targetUrl = url,
                        userAgent = settings.userAgentString
                    ),
                    fileName = DownloadStorage.fileNameFromHeaders(
                        url, contentDisposition, mimeType.takeIf { it.isNotBlank() }
                    ),
                    mimeType = mimeType.takeIf { it.isNotBlank() }
                )
                downloadCoordinator.enqueue(request)
            }
        }

        val formattedUrl = UrlUtils.formatOrSearchUrl(initialUrl)
        val newTab = TabState(
            id = tabId,
            url = formattedUrl,
            displayUrl = formattedUrl,
            title = if (isIncognito) "Incognito Tab" else "New Tab",
            isIncognito = isIncognito,
            isDesktopMode = false,
            webView = webView
        )

        webView.loadUrl(formattedUrl)

        _tabs.update { currentTabs -> currentTabs + newTab }
        _activeTabId.value = tabId

        return tabId
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun closeTab(tabId: String) {
        val currentTabs = _tabs.value
        val tabToClose = currentTabs.find { it.id == tabId } ?: return

        // Destroy webview
        tabToClose.webView?.let { webView ->
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }

        val updatedTabs = currentTabs.filter { it.id != tabId }
        _tabs.value = updatedTabs
        _mediaCandidates.update { it - tabId }
        segmentRegistry.clear(tabId)

        if (updatedTabs.isEmpty()) {
            createNewTab(initialUrl = "https://www.google.com", isIncognito = tabToClose.isIncognito)
        } else if (_activeTabId.value == tabId) {
            _activeTabId.value = updatedTabs.last().id
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean = false) {
        val currentTabs = _tabs.value
        val tabsToRemove = if (incognitoOnly) {
            currentTabs.filter { it.isIncognito }
        } else {
            currentTabs
        }

        tabsToRemove.forEach { tab ->
            tab.webView?.let { webView ->
                webView.stopLoading()
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        }

        val remainingTabs = if (incognitoOnly) {
            currentTabs.filter { !it.isIncognito }
        } else {
            emptyList()
        }

        _tabs.value = remainingTabs

        if (!incognitoOnly) {
            _mediaCandidates.value = emptyMap()
            segmentRegistry.clearAll()
            _tabsWithSegments.value = emptySet()
        } else {
            currentTabs.filter { it.isIncognito }.forEach { tab ->
                _mediaCandidates.update { it - tab.id }
                segmentRegistry.clear(tab.id)
            }
            _tabsWithSegments.update { current ->
                current.filter { id -> remainingTabs.any { it.id == id } }.toSet()
            }
        }

        if (remainingTabs.isEmpty()) {
            createNewTab(initialUrl = "https://www.google.com", isIncognito = false)
        } else {
            _activeTabId.value = remainingTabs.last().id
        }
    }

    fun loadUrlInActiveTab(
        inputUrl: String,
        searchEngineUrl: String = UrlUtils.DEFAULT_SEARCH_ENGINE_URL
    ) {
        val activeId = _activeTabId.value
        val tab = _tabs.value.find { it.id == activeId } ?: return
        val formattedUrl = UrlUtils.formatOrSearchUrl(inputUrl, searchEngineUrl)
        tab.webView?.loadUrl(formattedUrl)
        updateTab(activeId) {
            it.copy(url = formattedUrl, displayUrl = formattedUrl)
        }
    }

    fun toggleDesktopModeForActiveTab() {
        val activeId = _activeTabId.value
        val tab = _tabs.value.find { it.id == activeId } ?: return
        val newDesktopState = !tab.isDesktopMode

        tab.webView?.let { webView ->
            val settings = webView.settings
            settings.useWideViewPort = newDesktopState
            settings.loadWithOverviewMode = newDesktopState
            settings.userAgentString = if (newDesktopState) {
                UrlUtils.DESKTOP_USER_AGENT
            } else {
                if (defaultUserAgent.isNotEmpty()) defaultUserAgent else settings.userAgentString
            }
            webView.reload()
        }

        updateTab(activeId) {
            it.copy(isDesktopMode = newDesktopState)
        }
    }

    /**
     * Applies per-site overrides (JavaScript / desktop UA) before the page
     * renders. If a setting changes, the page reloads once; the next
     * PageStarted finds the config satisfied and stops.
     */
    private fun applySiteConfig(tab: TabState?) {
        val webView = tab?.webView ?: return
        val host = hostOf(tab.url) ?: return
        val record = siteSettings.get(host) ?: return

        if (record.javaScript != SiteSettingsRecord.DEFAULT) {
            val desired = record.javaScript == SiteSettingsRecord.ON
            if (webView.settings.javaScriptEnabled != desired) {
                webView.settings.javaScriptEnabled = desired
                webView.reload()
                return
            }
        }

        if (record.desktopMode != SiteSettingsRecord.DEFAULT) {
            val wantDesktop = record.desktopMode == SiteSettingsRecord.ON
            if (wantDesktop != tab.isDesktopMode) {
                webView.settings.useWideViewPort = wantDesktop
                webView.settings.loadWithOverviewMode = wantDesktop
                webView.settings.userAgentString = if (wantDesktop) {
                    UrlUtils.DESKTOP_USER_AGENT
                } else {
                    if (defaultUserAgent.isNotEmpty()) defaultUserAgent else webView.settings.userAgentString
                }
                updateTab(tab.id) { it.copy(isDesktopMode = wantDesktop) }
                webView.reload()
            }
        }
    }

    private fun applyNightMode(tab: TabState?, url: String?) {
        val webView = tab?.webView ?: return
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
        val override = siteSettings.get(hostOf(url))?.nightMode ?: SiteSettingsRecord.DEFAULT
        val js = if (NightMode.isNightActive(nightModeEnabled(), override)) {
            NightMode.INJECT_JS
        } else {
            NightMode.CLEAR_JS
        }
        webView.evaluateJavascript(js, null)
    }

    /** Re-evaluates night mode across every open tab (global toggle changed). */
    fun applyNightModeToAllTabs() {
        _tabs.value.forEach { tab -> applyNightMode(tab, tab.url) }
    }

    private fun hostOf(url: String): String? =
        android.net.Uri.parse(url).host?.lowercase()

    fun goBackInActiveTab(): Boolean {
        val tab = getActiveTab() ?: return false
        return if (tab.webView?.canGoBack() == true) {
            tab.webView.goBack()
            true
        } else {
            false
        }
    }

    fun goForwardInActiveTab(): Boolean {
        val tab = getActiveTab() ?: return false
        return if (tab.webView?.canGoForward() == true) {
            tab.webView.goForward()
            true
        } else {
            false
        }
    }

    fun reloadActiveTab() {
        getActiveTab()?.webView?.reload()
    }

    fun stopActiveTabLoading() {
        getActiveTab()?.webView?.stopLoading()
    }

    fun findInPage(query: String, onFindResult: (activeMatchOrdinal: Int, numberOfMatches: Int) -> Unit) {
        val webView = getActiveTab()?.webView ?: return
        if (query.isEmpty()) {
            webView.clearMatches()
            onFindResult(0, 0)
            return
        }
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                onFindResult(activeMatchOrdinal, numberOfMatches)
            }
        }
        webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) {
        getActiveTab()?.webView?.findNext(forward)
    }

    fun clearMatches() {
        getActiveTab()?.webView?.clearMatches()
    }

    fun toggleSiteBypassForActiveTab() {
        val activeTab = getActiveTab() ?: return
        val host = android.net.Uri.parse(activeTab.url).host ?: return
        adBlockRepository.toggleSiteBypass(host)
        activeTab.webView?.reload()
    }

    fun clearBrowsingData(
        clearHistory: Boolean = true,
        clearCookies: Boolean = true,
        clearCache: Boolean = true,
        clearWebStorage: Boolean = true
    ) {
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        if (clearWebStorage) {
            WebStorage.getInstance().deleteAllData()
        }
        _tabs.value.forEach { tab ->
            tab.webView?.let { webView ->
                if (clearHistory) webView.clearHistory()
                if (clearCache) webView.clearCache(true)
            }
        }
    }

    fun getActiveTab(): TabState? {
        val activeId = _activeTabId.value
        return _tabs.value.find { it.id == activeId }
    }

    private fun updateTab(tabId: String, transform: (TabState) -> TabState) {
        _tabs.update { list ->
            list.map { tab ->
                if (tab.id == tabId) transform(tab) else tab
            }
        }
    }

    private fun mergeMediaCandidates(tabId: String, incoming: List<MediaCandidate>) {
        _mediaCandidates.update { current ->
            val existing = current[tabId] ?: emptyList()
            val seen = existing.mapTo(mutableSetOf()) { it.url }
            val merged = existing + incoming.filter { it.url !in seen }
            current + (tabId to merged)
        }
    }

    private fun extractLongPressMediaUrl(hit: WebView.HitTestResult): String? {
        val extra = hit.extra ?: return null
        if (!extra.startsWith("http")) return null
        val isImageType = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val looksLikeMedia = MEDIA_LONG_PRESS_REGEX.containsMatchIn(extra)
        return if (isImageType || looksLikeMedia) extra else null
    }

    // EngineClient implementation callbacks
    override fun onPageStarted(tabId: String, url: String) {
        _mediaCandidates.update { it - tabId }
        segmentRegistry.clear(tabId)
        val tab = _tabs.value.find { it.id == tabId }
        val canGoBack = tab?.webView?.canGoBack() == true
        val canGoForward = tab?.webView?.canGoForward() == true

        updateTab(tabId) {
            it.copy(
                url = url,
                displayUrl = url,
                isLoading = true,
                progress = 10,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                blockedAdsCount = 0
            )
        }

        // Install page hooks (playlist/blob detection) as early as possible
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            tab?.webView?.evaluateJavascript(MediaSniffer.HOOKS_JS, null)
        }

        // Per-site overrides + night mode (site config may trigger one reload)
        if (tab != null) {
            applySiteConfig(tab)
            applyNightMode(tab, url)
        }
    }

    override fun onPageFinished(tabId: String, url: String) {
        val tab = _tabs.value.find { it.id == tabId }
        val canGoBack = tab?.webView?.canGoBack() == true
        val canGoForward = tab?.webView?.canGoForward() == true
        val pageTitle = tab?.webView?.title ?: tab?.title ?: "New Tab"

        updateTab(tabId) {
            it.copy(
                url = url,
                displayUrl = url,
                title = if (pageTitle.isNotEmpty()) pageTitle else it.title,
                isLoading = false,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )
        }

        // Inject the media sniffer after the page has settled; hooks are
        // re-injected too (idempotent) in case PageStarted ran too early
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            tab?.webView?.evaluateJavascript(MediaSniffer.INJECT_JS, null)
            tab?.webView?.evaluateJavascript(MediaSniffer.HOOKS_JS, null)
            applyNightMode(tab, url)
            if (tab?.isIncognito == false) {
                onHistoryPageVisited?.invoke(url, pageTitle)
                onPageCommitted?.invoke(url)
            }
        }
    }

    /** Re-runs the DOM sniffer + hooks in the active tab (sheet open / resniff). */
    fun resniffActiveTab() {
        val tab = getActiveTab() ?: return
        val url = tab.url
        if (url.startsWith("http://") || url.startsWith("https://")) {
            tab.webView?.evaluateJavascript(MediaSniffer.INJECT_JS, null)
            tab.webView?.evaluateJavascript(MediaSniffer.HOOKS_JS, null)
        }
    }

    override fun onProgressChanged(tabId: String, progress: Int) {
        updateTab(tabId) {
            it.copy(
                progress = progress,
                isLoading = progress < 100
            )
        }
    }

    override fun onTitleReceived(tabId: String, title: String) {
        updateTab(tabId) {
            it.copy(title = title)
        }
        val tab = _tabs.value.find { it.id == tabId }
        val url = tab?.url
        if (tab?.isIncognito == false && !url.isNullOrBlank() &&
            title.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
        ) {
            onHistoryTitleUpdated?.invoke(url, title)
        }
    }

    override fun onFaviconReceived(tabId: String, icon: Bitmap) {
        updateTab(tabId) {
            it.copy(favicon = icon)
        }
    }

    override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
        onFullscreenShowListener?.invoke(view, callback)
    }

    override fun onHideCustomView() {
        onFullscreenHideListener?.invoke()
    }

    override fun onAdBlocked(tabId: String) {
        updateTab(tabId) {
            it.copy(blockedAdsCount = it.blockedAdsCount + 1)
        }
    }

    companion object {
        private val MEDIA_LONG_PRESS_REGEX =
            Regex("\\.(mp4|webm|mkv|mov|avi|flv|mp3|m4a|ogg|wav|jpe?g|png|gif|webp|bmp)([?#].*)?$", RegexOption.IGNORE_CASE)
    }
}
