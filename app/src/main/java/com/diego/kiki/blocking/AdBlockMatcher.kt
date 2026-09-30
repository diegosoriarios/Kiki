package com.diego.kiki.blocking

import android.net.Uri

class AdBlockMatcher {

    private val domainSet = HashSet<String>()
    private val exceptionSet = HashSet<String>()
    private val substringRules = ArrayList<String>()

    fun loadRules(lines: List<String>) {
        domainSet.clear()
        exceptionSet.clear()
        substringRules.clear()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
                continue
            }

            if (line.startsWith("@@")) {
                // Exception rule
                val cleanRule = line.removePrefix("@@").removePrefix("||").removeSuffix("^")
                if (cleanRule.isNotEmpty()) {
                    exceptionSet.add(cleanRule.lowercase())
                }
            } else if (line.startsWith("||")) {
                // Domain rule: e.g. ||doubleclick.net^
                var domain = line.substring(2)
                val caretIndex = domain.indexOf('^')
                if (caretIndex != -1) {
                    domain = domain.substring(0, caretIndex)
                }
                val slashIndex = domain.indexOf('/')
                if (slashIndex != -1) {
                    domain = domain.substring(0, slashIndex)
                }
                if (domain.isNotEmpty()) {
                    domainSet.add(domain.lowercase())
                }
            } else if (line.contains("/") || line.contains("=") || line.contains("&")) {
                // Substring / path rule
                val cleanRule = line.replace("*", "").removeSuffix("^")
                if (cleanRule.length >= 3) {
                    substringRules.add(cleanRule.lowercase())
                }
            }
        }
    }

    /**
     * Determines if a request should be blocked.
     */
    fun shouldBlock(requestUri: Uri, pageHost: String? = null): Boolean {
        val requestHost = requestUri.host?.lowercase() ?: return false
        val fullUrl = requestUri.toString().lowercase()

        // 1. Check exceptions (whitelist)
        if (pageHost != null && exceptionSet.contains(pageHost.lowercase())) {
            return false
        }
        if (exceptionSet.contains(requestHost)) {
            return false
        }

        // 2. Check domain rule fast path (O(1) lookup on host suffixes)
        var currentHost: String? = requestHost
        while (!currentHost.isNullOrEmpty()) {
            if (domainSet.contains(currentHost)) {
                return true
            }
            val dotIndex = currentHost.indexOf('.')
            currentHost = if (dotIndex != -1 && dotIndex < currentHost.length - 1) {
                currentHost.substring(dotIndex + 1)
            } else {
                null
            }
        }

        // 3. Check substring path rules
        for (rule in substringRules) {
            if (fullUrl.contains(rule)) {
                return true
            }
        }

        return false
    }

    fun getLoadedRulesCount(): Int = domainSet.size + substringRules.size
}
