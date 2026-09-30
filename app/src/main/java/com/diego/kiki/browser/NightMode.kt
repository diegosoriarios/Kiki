package com.diego.kiki.browser

import com.diego.kiki.data.SiteSettingsRecord

/**
 * Night mode for web content: CSS filter inversion with media double-inverted
 * back to normal. Deterministic across API levels; global toggle + per-site
 * override (site wins).
 */
object NightMode {

    fun isNightActive(globalEnabled: Boolean, siteOverride: Int): Boolean =
        when (siteOverride) {
            SiteSettingsRecord.ON -> true
            SiteSettingsRecord.OFF -> false
            else -> globalEnabled
        }

    /** Injects (idempotent) the night stylesheet into the current document. */
    val INJECT_JS: String = """
        (function() {
            try {
                if (document.getElementById('__kiki_night')) return;
                var s = document.createElement('style');
                s.id = '__kiki_night';
                s.textContent = 'html{filter:invert(0.92) hue-rotate(180deg) !important;background:#121212 !important;}' +
                    'img,video,picture,iframe,svg,canvas,embed,object{filter:invert(0.92) hue-rotate(180deg) !important;}';
                (document.head || document.documentElement).appendChild(s);
            } catch (e) {}
        })();
    """.trimIndent()

    /** Removes the night stylesheet and any leftover filter. */
    val CLEAR_JS: String = """
        (function() {
            try {
                var s = document.getElementById('__kiki_night');
                if (s && s.parentNode) s.parentNode.removeChild(s);
                document.documentElement.style.webkitFilter = '';
            } catch (e) {}
        })();
    """.trimIndent()
}
