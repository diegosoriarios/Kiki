package com.diego.kiki.engine

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient

/**
 * Interface for engine callbacks.
 */
interface EngineClient {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleReceived(tabId: String, title: String)
    fun onFaviconReceived(tabId: String, icon: Bitmap)
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onAdBlocked(tabId: String)
}
