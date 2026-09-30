package com.diego.kiki.browser

import android.util.Patterns
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlUtils {
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    const val DEFAULT_SEARCH_ENGINE_URL = "https://www.google.com/search?q="

    /**
     * Heuristic to determine if input string is a valid URL or a search query.
     */
    fun formatOrSearchUrl(
        input: String,
        searchEngineUrl: String = DEFAULT_SEARCH_ENGINE_URL
    ): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://www.google.com"

        // Check if explicit scheme is present
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return trimmed
        }

        // Check for domain or IP address patterns without spaces
        val hasNoSpaces = !trimmed.contains(" ")
        val matchesUrlPattern = Patterns.WEB_URL.matcher(trimmed).matches()
        val isLocalhost = trimmed.startsWith("localhost", ignoreCase = true)

        if (hasNoSpaces && (matchesUrlPattern || isLocalhost || (trimmed.contains(".") && trimmed.length > 3))) {
            return "https://$trimmed"
        }

        // Fall back to search engine query
        val encodedQuery = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return "$searchEngineUrl$encodedQuery"
    }

    /**
     * Format display URL for URL bar (strip unnecessary prefix or query params if needed).
     */
    fun getDisplayHost(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host
            if (!host.isNullOrEmpty()) host else url
        } catch (_: Exception) {
            url
        }
    }
}
