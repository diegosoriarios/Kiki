package com.diego.kiki.browser

import android.graphics.Bitmap
import android.webkit.WebView
import com.diego.kiki.engine.EngineTab
import java.util.UUID

/**
 * Tab state representing a single browser tab.
 */
data class TabState(
    override val id: String = UUID.randomUUID().toString(),
    override val url: String = "https://www.google.com",
    val displayUrl: String = "https://www.google.com",
    override val title: String = "New Tab",
    override val favicon: Bitmap? = null,
    override val isIncognito: Boolean = false,
    override val isDesktopMode: Boolean = false,
    override val progress: Int = 0,
    override val isLoading: Boolean = false,
    override val canGoBack: Boolean = false,
    override val canGoForward: Boolean = false,
    val blockedAdsCount: Int = 0,
    val webView: WebView? = null
) : EngineTab
