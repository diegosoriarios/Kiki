package com.diego.kiki.download

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Events posted from the page's JS hooks through the @JavascriptInterface bridge. */
sealed class BlobEvent {
    data class Found(val url: String, val isMediaSource: Boolean) : BlobEvent()
    data class Start(val jobKey: String, val totalBytes: Long) : BlobEvent()
    data class Chunk(val jobKey: String, val index: Int, val totalChunks: Int, val base64: String) : BlobEvent()
    data class Done(val jobKey: String) : BlobEvent()
    data class Error(val jobKey: String, val message: String) : BlobEvent()
}

/**
 * Injects JS hooks into pages and receives results through the
 * @JavascriptInterface bridge:
 * - DOM media enumerator (on page load + on demand)
 * - XHR/fetch playlist detector (#EXTM3U / <MPD responses, any URL shape)
 * - URL.createObjectURL tracker (data-backed blobs are downloadable)
 * - Blob chunk transfer for the blob-URL download bridge
 */
class MediaSniffer(
    private val tabId: String,
    private val onCandidates: (tabId: String, candidates: List<MediaCandidate>) -> Unit,
    private val onPlaylist: (tabId: String, url: String, kind: String, isMaster: Boolean) -> Unit,
    private val onBlobEvent: (tabId: String, event: BlobEvent) -> Unit
) {

    /** Called from the WebView JS bridge thread. */
    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onMediaFound(json: String) {
            val candidates = parseCandidates(json)
            if (candidates.isNotEmpty()) {
                onCandidates(tabId, candidates)
            }
        }

        @android.webkit.JavascriptInterface
        fun onPlaylistFound(url: String, kind: String, isMaster: Int) {
            if (url.startsWith("http")) {
                onPlaylist(tabId, url, kind, isMaster == 1)
            }
        }

        @android.webkit.JavascriptInterface
        fun onBlobFound(url: String, isMediaSource: Boolean) {
            if (!url.startsWith("blob:")) return
            if (!isMediaSource) {
                // Data-backed blob (XHR/fetch -> new Blob -> createObjectURL): downloadable
                onCandidates(tabId, listOf(MediaCandidate(url, MediaTypes.BLOB, "blob")))
            }
            onBlobEvent(tabId, BlobEvent.Found(url, isMediaSource))
        }

        @android.webkit.JavascriptInterface
        fun onBlobStart(jobKey: String, totalBytes: Long) {
            onBlobEvent(tabId, BlobEvent.Start(jobKey, totalBytes))
        }

        @android.webkit.JavascriptInterface
        fun onBlobChunk(jobKey: String, chunkIndex: Int, totalChunks: Int, base64: String) {
            onBlobEvent(tabId, BlobEvent.Chunk(jobKey, chunkIndex, totalChunks, base64))
        }

        @android.webkit.JavascriptInterface
        fun onBlobDone(jobKey: String) {
            onBlobEvent(tabId, BlobEvent.Done(jobKey))
        }

        @android.webkit.JavascriptInterface
        fun onBlobError(jobKey: String, message: String) {
            onBlobEvent(tabId, BlobEvent.Error(jobKey, message))
        }
    }

    fun parseCandidates(json: String): List<MediaCandidate> {
        return try {
            val array = Json.parseToJsonElement(json)
            array.jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj[KEY_URL]?.jsonPrimitive?.content ?: return@mapNotNull null
                val type = obj[KEY_TYPE]?.jsonPrimitive?.content ?: MediaTypes.VIDEO
                val tag = obj[KEY_TAG]?.jsonPrimitive?.content ?: ""
                if (url.startsWith("http")) MediaCandidate(url, type, tag) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val BRIDGE_NAME = "KikiMedia"

        private const val KEY_URL = "url"
        private const val KEY_TYPE = "type"
        private const val KEY_TAG = "tag"

        /**
         * Installed once per document: hooks XHR/fetch to detect playlist responses
         * (catches extension-less playlist URLs) and URL.createObjectURL to expose
         * in-page blobs. Idempotent via a window flag.
         */
        val HOOKS_JS = """
            (function() {
                if (window.__kikiHooksInstalled) return;
                window.__kikiHooksInstalled = true;
                try {
                    function abs(raw) {
                        try { return new URL(raw, location.href).href; } catch (e) { return null; }
                    }
                    function reportPlaylist(u, kind, isMaster) {
                        if (u && /^https?:/i.test(u)) {
                            window.KikiMedia.onPlaylistFound(u, kind, isMaster ? 1 : 0);
                        }
                    }
                    function looksLikePlaylistText(txt) {
                        if (!txt) return null;
                        var t = txt.trimStart().substring(0, 16);
                        if (t.indexOf('#EXTM3U') === 0) return 'HLS';
                        if (t.indexOf('<MPD') === 0) return 'DASH';
                        return null;
                    }
                    var xhrOpen = window.XMLHttpRequest.prototype.open;
                    window.XMLHttpRequest.prototype.open = function(m, u) {
                        this.__kikiUrl = u;
                        return xhrOpen.apply(this, arguments);
                    };
                    var xhrSend = window.XMLHttpRequest.prototype.send;
                    window.XMLHttpRequest.prototype.send = function() {
                        var xhr = this;
                        xhr.addEventListener('load', function() {
                            try {
                                var txt = xhr.responseText;
                                var kind = looksLikePlaylistText(txt);
                                if (kind) {
                                    var master = txt.indexOf('#EXT-X-STREAM-INF') >= 0;
                                    reportPlaylist(abs(xhr.__kikiUrl), kind, master);
                                }
                            } catch (e) {}
                        });
                        return xhrSend.apply(this, arguments);
                    };
                    var nativeFetch = window.fetch;
                    if (nativeFetch) {
                        window.fetch = function(input, init) {
                            var url = (typeof input === 'string') ? input : (input && input.url) || '';
                            return nativeFetch.apply(this, arguments).then(function(resp) {
                                try {
                                    var ct = (resp.headers.get('content-type') || '').toLowerCase();
                                    var cheap = ct.indexOf('mpegurl') >= 0 || ct.indexOf('dash+xml') >= 0 ||
                                        ct.indexOf('text') >= 0 || /m3u8|mpd|manifest|playlist/i.test(url);
                                    if (cheap && resp.ok) {
                                        resp.clone().text().then(function(txt) {
                                            var kind = looksLikePlaylistText(txt);
                                            if (kind) {
                                                var master = txt.indexOf('#EXT-X-STREAM-INF') >= 0;
                                                reportPlaylist(abs(url), kind, master);
                                            }
                                        }).catch(function() {});
                                    }
                                } catch (e) {}
                                return resp;
                            });
                        };
                    }
                    var nativeCreateObjectURL = window.URL.createObjectURL;
                    if (nativeCreateObjectURL) {
                        window.URL.createObjectURL = function(obj) {
                            var u = nativeCreateObjectURL.apply(this, arguments);
                            try {
                                var isMS = (typeof MediaSource !== 'undefined') && (obj instanceof MediaSource);
                                window.KikiMedia.onBlobFound(u, isMS);
                            } catch (e) {}
                            return u;
                        };
                    }
                } catch (e) {}
            })();
        """.trimIndent()

        /**
         * DOM media enumerator, run on page load and on demand (sheet open).
         * Blob srcs are intentionally skipped here — the createObjectURL hook
         * reports them with backing-type information.
         */
        val INJECT_JS = """
            (function() {
                try {
                    var seen = {};
                    var results = [];
                    var MAX = 50;
                    var mediaExt = /\.(mp4|webm|mkv|mov|avi|flv|m4v|ogv|mp3|m4a|aac|ogg|wav|flac|opus)(\?|#|$)/i;
                    var hlsExt = /\.m3u8(\?|#|$)/i;
                    var dashExt = /\.mpd(\?|#|$)/i;
                    function abs(raw) {
                        try { return new URL(raw, location.href).href; } catch (e) { return null; }
                    }
                    function add(raw, type, tag) {
                        if (!raw || results.length >= MAX) return;
                        var u = abs(raw);
                        if (!u || !/^https?:/i.test(u) || seen[u]) return;
                        seen[u] = 1;
                        results.push({url: u, type: type, tag: tag});
                    }
                    var nodes = document.querySelectorAll('video,audio,source,a[href]');
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        var tag = el.tagName.toLowerCase();
                        if (tag === 'video' || tag === 'audio') {
                            var src = el.currentSrc || el.src || el.getAttribute('src');
                            var kind = tag === 'video' ? 'VIDEO' : 'AUDIO';
                            if (src) {
                                if (hlsExt.test(src)) kind = 'HLS';
                                else if (dashExt.test(src)) kind = 'DASH';
                                add(src, kind, tag);
                            }
                            var poster = el.getAttribute('poster');
                            if (poster && tag === 'video') add(poster, 'IMAGE', 'poster');
                        } else if (tag === 'source') {
                            var s = el.getAttribute('src');
                            if (s) {
                                var t = 'VIDEO';
                                if (hlsExt.test(s)) t = 'HLS';
                                else if (dashExt.test(s)) t = 'DASH';
                                else if (mediaExt.test(s)) {
                                    var mt = (el.getAttribute('type') || '').toLowerCase();
                                    t = mt.indexOf('audio') === 0 ? 'AUDIO'
                                        : mt.indexOf('image') === 0 ? 'IMAGE' : 'VIDEO';
                                }
                                add(s, t, 'source');
                            }
                        } else if (tag === 'a') {
                            var href = el.getAttribute('href');
                            if (href && (mediaExt.test(href) || hlsExt.test(href) || dashExt.test(href))) {
                                var at = hlsExt.test(href) ? 'HLS' : dashExt.test(href) ? 'DASH' : 'VIDEO';
                                add(href, at, 'a');
                            }
                        }
                    }
                    if (results.length > 0) {
                        window.KikiMedia.onMediaFound(JSON.stringify(results));
                    }
                } catch (e) {}
            })();
        """.trimIndent()

        /**
         * Streams a blob: URL to native in base64 chunks. Requires the page to
         * stay open while transferring. [jobKey] routes chunks to the download.
         */
        fun fetchBlobScript(jobKey: String, blobUrl: String): String {
            val key = jsEscape(jobKey)
            val url = jsEscape(blobUrl)
            return """
                (function() {
                    (async function() {
                        var KEY = '$key';
                        try {
                            var resp = await fetch('$url');
                            if (!resp.ok) throw new Error('HTTP ' + resp.status);
                            var blob = await resp.blob();
                            var total = blob.size;
                            if (total === 0) throw new Error('Empty blob');
                            window.KikiMedia.onBlobStart(KEY, total);
                            var CHUNK = 524288;
                            var chunks = Math.ceil(total / CHUNK);
                            for (var i = 0; i < chunks; i++) {
                                var end = Math.min((i + 1) * CHUNK, total);
                                var bytes = new Uint8Array(await blob.slice(i * CHUNK, end).arrayBuffer());
                                var binary = '';
                                var STEP = 32768;
                                for (var j = 0; j < bytes.length; j += STEP) {
                                    binary += String.fromCharCode.apply(
                                        null, bytes.subarray(j, Math.min(j + STEP, bytes.length)));
                                }
                                window.KikiMedia.onBlobChunk(KEY, i, chunks, btoa(binary));
                            }
                            window.KikiMedia.onBlobDone(KEY);
                        } catch (e) {
                            window.KikiMedia.onBlobError(KEY, String(e && e.message || e));
                        }
                    })();
                })();
            """.trimIndent()
        }

        fun decodeBase64(base64: String): ByteArray =
            Base64.decode(base64, Base64.NO_WRAP or Base64.NO_PADDING)

        private fun jsEscape(value: String): String =
            value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
