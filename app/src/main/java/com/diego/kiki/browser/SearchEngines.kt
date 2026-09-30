package com.diego.kiki.browser

import android.net.Uri

object SearchEngines {

    data class Engine(
        val id: String,
        val name: String,
        val template: String
    )

    val GOOGLE = Engine("google", "Google", "https://www.google.com/search?q=")
    val DUCKDUCKGO = Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=")
    val BING = Engine("bing", "Bing", "https://www.bing.com/search?q=")
    val STARTPAGE = Engine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=")
    val CUSTOM = Engine("custom", "Custom", "")

    val ALL = listOf(GOOGLE, DUCKDUCKGO, BING, STARTPAGE, CUSTOM)

    fun byId(id: String): Engine = ALL.find { it.id == id } ?: GOOGLE

    /**
     * Builds the search URL for [query]. Templates may use a %s placeholder;
     * otherwise the encoded query is appended.
     */
    fun buildUrl(engine: Engine, customTemplate: String, query: String): String {
        val template = if (engine.id == CUSTOM.id) customTemplate else engine.template
        val encoded = Uri.encode(query)
        return when {
            template.isBlank() -> GOOGLE.template + encoded
            template.contains("%s") -> String.format(template, encoded)
            else -> template + encoded
        }
    }
}
