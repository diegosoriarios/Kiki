package com.diego.kiki.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.diego.kiki.blocking.AdBlockRepository
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.engine.EngineClient
import java.io.ByteArrayInputStream

class KikiWebViewClient(
    private val context: Context,
    private val tabId: String,
    private val engineClient: EngineClient,
    private val adBlockRepository: AdBlockRepository,
    private val segmentRegistry: SegmentCandidateRegistry
) : WebViewClient() {

    /**
     * Main-frame URL as of the last onPageStarted. shouldInterceptRequest runs on a
     * background thread where WebView methods (e.g. view.url) must not be called.
     */
    @Volatile
    private var currentPageUrl: String? = null

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request != null) {
            val requestUri = request.url
            val pageHost = currentPageUrl?.let { Uri.parse(it).host }
            if (adBlockRepository.shouldBlockRequest(requestUri, pageHost)) {
                engineClient.onAdBlocked(tabId)
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            segmentRegistry.record(tabId, requestUri.toString())
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleExternalScheme(url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        return handleExternalScheme(url)
    }

    private fun handleExternalScheme(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false

        // Keep HTTP and HTTPS inside the WebView
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "file") {
            return false
        }

        // Try opening external schemes (e.g. mailto:, tel:, intent:, youtube:, etc.) with external app
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            true // Consume the request even if no app is available to open external scheme
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentPageUrl = url
        if (url != null) {
            engineClient.onPageStarted(tabId, url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url,)
        if (url != null) {
            engineClient.onPageFinished(tabId, url)
        }
    }
}
