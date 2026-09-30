package com.diego.kiki.engine

import android.graphics.Bitmap

/**
 * Abstract interface representing a browser tab, allowing engine swapping if needed in the future.
 */
interface EngineTab {
    val id: String
    val url: String
    val title: String
    val favicon: Bitmap?
    val isIncognito: Boolean
    val isDesktopMode: Boolean
    val progress: Int
    val isLoading: Boolean
    val canGoBack: Boolean
    val canGoForward: Boolean
}
