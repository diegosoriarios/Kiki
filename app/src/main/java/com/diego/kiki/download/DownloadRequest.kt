package com.diego.kiki.download

import android.webkit.CookieManager

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String,
    val mimeType: String? = null
) {
    companion object {
        /**
         * Standard headers for a media download tied to a page: Referer for
         * hotlink protection, User-Agent, and session cookies (many media CDNs
         * return 403 without them).
         */
        fun pageHeaders(pageUrl: String?, targetUrl: String, userAgent: String? = null): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            if (!pageUrl.isNullOrBlank()) headers["Referer"] = pageUrl
            userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
            try {
                CookieManager.getInstance().getCookie(targetUrl)?.let { cookie ->
                    headers["Cookie"] = cookie
                }
            } catch (_: Exception) {
                // Cookie manager unavailable (e.g. JVM tests)
            }
            return headers
        }
    }
}
