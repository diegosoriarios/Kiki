package com.diego.kiki.browser

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.diego.kiki.engine.EngineClient

class KikiWebChromeClient(
    private val tabId: String,
    private val engineClient: EngineClient
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        engineClient.onProgressChanged(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrEmpty()) {
            engineClient.onTitleReceived(tabId, title)
        }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        if (icon != null) {
            engineClient.onFaviconReceived(tabId, icon)
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        super.onShowCustomView(view, callback)
        if (view != null && callback != null) {
            engineClient.onShowCustomView(view, callback)
        }
    }

    override fun onHideCustomView() {
        super.onHideCustomView()
        engineClient.onHideCustomView()
    }
}
