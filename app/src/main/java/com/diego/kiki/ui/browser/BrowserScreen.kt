package com.diego.kiki.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diego.kiki.MainActivity
import com.diego.kiki.ui.KikiViewModel
import com.diego.kiki.ui.LockScreen
import com.diego.kiki.ui.bookmarks.BookmarksScreen
import com.diego.kiki.ui.downloads.BulkDownloadSheet
import com.diego.kiki.ui.downloads.DownloadPill
import com.diego.kiki.ui.downloads.DownloadsScreen
import com.diego.kiki.ui.downloads.MediaCandidatesSheet
import com.diego.kiki.ui.history.HistoryScreen
import com.diego.kiki.ui.settings.SettingsScreen
import com.diego.kiki.ui.tabs.TabSwitcherScreen

@Composable
fun BrowserScreen(
    viewModel: KikiViewModel,
    modifier: Modifier = Modifier
) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isTabSwitcherVisible by viewModel.isTabSwitcherVisible.collectAsState()
    val isIncognitoTabGroup by viewModel.isIncognitoTabGroup.collectAsState()
    val findInPageState by viewModel.findInPageState.collectAsState()
    val fullscreenVideoState by viewModel.fullscreenVideoState.collectAsState()
    val isGlobalAdBlockEnabled by viewModel.isGlobalAdBlockEnabled.collectAsState()
    val bypassedSites by viewModel.bypassedSites.collectAsState()
    val isShieldDialogVisible by viewModel.isShieldDialogVisible.collectAsState()
    val nightModeEnabled by viewModel.nightModeEnabled.collectAsState()
    val isSettingsVisible by viewModel.isSettingsVisible.collectAsState()
    val isDownloadsVisible by viewModel.isDownloadsVisible.collectAsState()
    val mediaCandidates by viewModel.mediaCandidates.collectAsState()
    val tabsWithSegments by viewModel.tabsWithSegments.collectAsState()
    val isMediaSheetVisible by viewModel.isMediaSheetVisible.collectAsState()
    val mediaContextMenu by viewModel.mediaContextMenu.collectAsState()
    val isLocked by viewModel.appLock.isLocked.collectAsState()
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val lockIncognitoOnly by viewModel.lockIncognitoOnly.collectAsState()
    val isHistoryVisible by viewModel.isHistoryVisible.collectAsState()
    val isBookmarksVisible by viewModel.isBookmarksVisible.collectAsState()
    val history by viewModel.history.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val historySuggestions by viewModel.historySuggestions.collectAsState()
    val isCurrentTabBookmarked by viewModel.isCurrentTabBookmarked.collectAsState()
    val saveHistoryEnabled by viewModel.saveHistoryEnabled.collectAsState()
    val searchEngineId by viewModel.searchEngineId.collectAsState()
    val customSearchTemplate by viewModel.customSearchTemplate.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val isAutoScrollPanelVisible by viewModel.isAutoScrollPanelVisible.collectAsState()
    val autoScrollState by viewModel.autoScroller.state.collectAsState()
    val isBulkSheetVisible by viewModel.isBulkSheetVisible.collectAsState()
    var showSiteSettingsDialog by remember { mutableStateOf(false) }
    val isScreenRecording by com.diego.kiki.screenrec.RecordingService.isRecording.collectAsState()

    val context = LocalContext.current
    val canUseBiometrics = remember { viewModel.appLock.isBiometricAvailable(context) }

    val currentHost = activeTab?.url?.let {
        try { android.net.Uri.parse(it).host } catch (_: Exception) { null }
    }
    val isCurrentSiteBypassed = currentHost?.let { bypassedSites.contains(it.lowercase()) } == true

    // Handle System Back Button
    BackHandler(
        enabled = fullscreenVideoState != null ||
                isSettingsVisible ||
                isDownloadsVisible ||
                isHistoryVisible ||
                isBookmarksVisible ||
                findInPageState.isVisible ||
                isTabSwitcherVisible ||
                activeTab?.canGoBack == true
    ) {
        when {
            fullscreenVideoState != null -> viewModel.exitFullscreenVideo()
            isSettingsVisible -> viewModel.hideSettings()
            isDownloadsVisible -> viewModel.hideDownloads()
            isHistoryVisible -> viewModel.hideHistory()
            isBookmarksVisible -> viewModel.hideBookmarks()
            findInPageState.isVisible -> viewModel.hideFindInPage()
            isTabSwitcherVisible -> viewModel.hideTabSwitcher()
            activeTab?.canGoBack == true -> viewModel.goBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val fullscreenState = fullscreenVideoState
        if (fullscreenState != null) {
            // HTML5 Fullscreen Video View
            FullscreenVideoLayout(fullscreenState = fullscreenState)
        } else if (isSettingsVisible) {
            // Settings Screen
            SettingsScreen(
                isGlobalAdBlockEnabled = isGlobalAdBlockEnabled,
                bypassedSites = bypassedSites,
                onToggleGlobalAdBlock = { enabled -> viewModel.toggleGlobalAdBlock(enabled) },
                onRemoveBypassedSite = { site -> viewModel.removeBypassedSite(site) },
                onClearBrowsingData = { history, cookies, cache, storage ->
                    viewModel.clearBrowsingData(history, cookies, cache, storage)
                },
                appLockEnabled = appLockEnabled,
                lockIncognitoOnly = lockIncognitoOnly,
                canUseBiometrics = canUseBiometrics,
                onToggleAppLock = { enabled -> viewModel.setAppLockEnabled(enabled) },
                onToggleLockIncognitoOnly = { enabled -> viewModel.setLockIncognitoOnly(enabled) },
                saveHistoryEnabled = saveHistoryEnabled,
                onToggleSaveHistory = { enabled -> viewModel.setSaveHistoryEnabled(enabled) },
                searchEngineId = searchEngineId,
                customSearchTemplate = customSearchTemplate,
                onSetSearchEngine = { id -> viewModel.setSearchEngine(id) },
                onSetCustomSearchTemplate = { template -> viewModel.setCustomSearchTemplate(template) },
                themeMode = themeMode,
                onSetThemeMode = { mode -> viewModel.setThemeMode(mode) },
                nightModeEnabled = nightModeEnabled,
                onSetNightMode = { enabled -> viewModel.setNightModeEnabled(enabled) },
                onBack = { viewModel.hideSettings() }
            )
        } else if (isDownloadsVisible) {
            // Downloads Screen
            DownloadsScreen(
                viewModel = viewModel,
                onBack = { viewModel.hideDownloads() }
            )
        } else if (isHistoryVisible) {
            // History Screen
            HistoryScreen(
                history = history,
                onDeleteEntry = { viewModel.deleteHistoryEntry(it) },
                onClearAll = { viewModel.clearAllHistory() },
                onOpenUrl = { viewModel.openFromList(it) },
                onBack = { viewModel.hideHistory() }
            )
        } else if (isBookmarksVisible) {
            // Bookmarks Screen
            BookmarksScreen(
                bookmarks = bookmarks,
                onRemove = { viewModel.removeBookmark(it) },
                onOpenUrl = { viewModel.openFromList(it) },
                onBack = { viewModel.hideBookmarks() }
            )
        } else if (isTabSwitcherVisible) {
            // Tab Switcher Screen
            TabSwitcherScreen(
                tabs = tabs,
                activeTabId = activeTabId,
                isIncognitoGroup = isIncognitoTabGroup,
                onSelectGroup = { isIncognito -> viewModel.selectIncognitoTabGroup(isIncognito) },
                onSelectTab = { tabId -> viewModel.selectTab(tabId) },
                onCloseTab = { tabId -> viewModel.closeTab(tabId) },
                onCloseAllTabs = { isIncognito -> viewModel.closeAllTabs(incognitoOnly = isIncognito) },
                onNewTab = { isIncognito -> viewModel.createNewTab(isIncognito = isIncognito) },
                onDone = { viewModel.hideTabSwitcher() }
            )
        } else {
            // Main Browser Screen
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        UrlBar(
                            tab = activeTab,
                            onGoToUrl = { url -> viewModel.loadUrl(url) },
                            onSearch = { query -> viewModel.searchAndGo(query) },
                            onQueryChanged = { query -> viewModel.updateHistoryQuery(query) },
                            historySuggestions = historySuggestions,
                            onOpenShield = { viewModel.showShieldDialog() }
                        )

                        AnimatedVisibility(visible = findInPageState.isVisible) {
                            FindInPageBar(
                                state = findInPageState,
                                onQueryChange = { query -> viewModel.updateFindQuery(query) },
                                onNext = { viewModel.findNext(forward = true) },
                                onPrevious = { viewModel.findNext(forward = false) },
                                onClose = { viewModel.hideFindInPage() }
                            )
                        }
                    }
                },
                bottomBar = {
                    BottomToolbar(
                        tab = activeTab,
                        totalTabsCount = tabs.size,
                        onGoBack = { viewModel.goBack() },
                        onGoForward = { viewModel.goForward() },
                        onRefreshOrStop = {
                            if (activeTab?.isLoading == true) {
                                viewModel.stopLoading()
                            } else {
                                viewModel.reload()
                            }
                        },
                        onOpenTabsScreen = { viewModel.toggleTabSwitcher() },
                        onNewTab = { isIncognito -> viewModel.createNewTab(isIncognito = isIncognito) },
                        onToggleDesktopMode = { viewModel.toggleDesktopMode() },
                        onFindInPage = { viewModel.showFindInPage() },
                        onOpenDownloads = { viewModel.showDownloads() },
                        onOpenSettings = { viewModel.showSettings() },
                        isBookmarked = isCurrentTabBookmarked,
                        onToggleBookmark = { viewModel.toggleBookmarkForActiveTab() },
                        onOpenBookmarks = { viewModel.showBookmarks() },
                        onOpenHistory = { viewModel.showHistory() },
                        onOpenAutoScroll = { viewModel.showAutoScrollPanel() },
                        onToggleScreenRecord = {
                            if (isScreenRecording) {
                                com.diego.kiki.screenrec.RecordingService.stop(context)
                            } else {
                                (context as? MainActivity)?.launchScreenRecordConsent()
                            }
                        },
                        isScreenRecording = isScreenRecording
                    )
                }
            ) { innerPadding ->
                val activeId = activeTabId
                val tabCandidates = mediaCandidates[activeId].orEmpty()
                val pillVisible = activeId.isNotEmpty() &&
                        (tabCandidates.isNotEmpty() || tabsWithSegments.contains(activeId))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val tabUrl = activeTab?.url
                    if (tabUrl.isNullOrEmpty() || tabUrl == "about:blank") {
                        // Homepage: search box + site tiles
                        Homepage(
                            onGoToUrl = { url -> viewModel.loadUrl(url) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        WebViewContainer(
                            activeTab = activeTab,
                            onRefresh = { viewModel.reload() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (pillVisible) {
                        DownloadPill(
                            count = tabCandidates.size,
                            onClick = { viewModel.showMediaSheet() },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        )
                    }
                    if (isScreenRecording) {
                        // Recording indicator + tap-to-stop, above everything
                        Surface(
                            onClick = {
                                com.diego.kiki.screenrec.RecordingService.stop(context)
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                text = "● RECORDING — tap to stop",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (isAutoScrollPanelVisible) {
                        AutoScrollPanel(
                            state = autoScrollState,
                            onSelectInterval = { viewModel.selectAutoScrollInterval(it) },
                            onBeginSetup = { viewModel.beginAutoScrollSetup() },
                            onStop = { viewModel.stopAutoScroll() },
                            onDismiss = { viewModel.hideAutoScrollPanel() },
                            mediaCount = viewModel.buildBulkItems().size,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                        )
                    }
                    if (autoScrollState.awaitingPins) {
                        GesturePinOverlay(
                            onStart = { pins -> viewModel.startAutoScroll(pins) },
                            onCancel = { viewModel.cancelAutoScrollSetup() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Shield Dialog Overlay
        if (isShieldDialogVisible) {
            ShieldDialog(
                tab = activeTab,
                isGlobalAdBlockEnabled = isGlobalAdBlockEnabled,
                isSiteBypassed = isCurrentSiteBypassed,
                onToggleGlobalAdBlock = { enabled -> viewModel.toggleGlobalAdBlock(enabled) },
                onToggleSiteBypass = { viewModel.toggleSiteBypassForActiveTab() },
                onOpenSiteSettings = { showSiteSettingsDialog = true },
                onOpenSettings = { viewModel.showSettings() },
                onDismiss = { viewModel.hideShieldDialog() }
            )
        }

        // Per-site settings dialog
        if (showSiteSettingsDialog) {
            val siteHost = viewModel.activeSiteHost()
            if (siteHost != null) {
                SiteSettingsDialog(
                    host = siteHost,
                    initial = viewModel.getSiteSettings(siteHost),
                    onSave = { record -> viewModel.saveSiteSettings(record) },
                    onReset = { host -> viewModel.resetSiteSettings(host) },
                    onDismiss = { showSiteSettingsDialog = false }
                )
            } else {
                showSiteSettingsDialog = false
            }
        }

        // Bulk download checklist (after auto-scroll or manual open)
        if (isBulkSheetVisible) {
            val bulkItems = remember(isBulkSheetVisible) { viewModel.buildBulkItems() }
            if (bulkItems.isEmpty()) {
                LaunchedEffect(Unit) { viewModel.hideBulkSheet() }
            } else {
                BulkDownloadSheet(
                    items = bulkItems,
                    onDownload = { items -> viewModel.bulkDownload(items) },
                    onDismiss = { viewModel.hideBulkSheet() }
                )
            }
        }

        // Media Candidates Sheet (sniffer + segment groundwork)
        if (isMediaSheetVisible) {
            MediaCandidatesSheet(
                candidates = mediaCandidates[activeTabId].orEmpty(),
                capture = viewModel.mediaCaptureForActiveTab(),                onDownload = { candidate -> viewModel.downloadMediaCandidate(candidate) },
                onDownloadHls = { url -> viewModel.downloadHlsStream(url) },
                onOpenBulk = {
                    viewModel.hideMediaSheet()
                    viewModel.showBulkSheet()
                },
                onDismiss = { viewModel.hideMediaSheet() }
            )
        }

        // Long-press media context dialog
        mediaContextMenu?.let { contextState ->
            val isVideo = remember(contextState.url) {
                Regex("\\.(mp4|webm|mkv|mov|avi|flv|mp3|m4a|ogg|wav)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(contextState.url)
            }
            val clipboard = LocalClipboardManager.current
            AlertDialog(
                onDismissRequest = { viewModel.dismissMediaContextMenu() },
                title = { Text(if (isVideo) "Download video?" else "Download image?") },
                text = {
                    Text(
                        text = contextState.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.downloadFromContextMenu() }) {
                        Text("Download")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(contextState.url))
                            viewModel.dismissMediaContextMenu()
                        }) {
                            Text("Copy URL")
                        }
                        TextButton(onClick = { viewModel.dismissMediaContextMenu() }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }

        // App lock gate — covers everything while locked
        if (isLocked) {
            LockScreen(
                onAuthenticate = { activity, onSuccess, onError ->
                    viewModel.authenticate(activity, onSuccess, onError)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
