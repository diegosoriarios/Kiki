package com.diego.kiki.download

/**
 * Pure builder for the bulk-download checklist: everything currently captured
 * for a tab as deduped rows, in capture order — one entry per stream (its best
 * playlist, never per variant), then playlist-side media groups, then DOM
 * candidates. DASH and images are excluded (no pipeline / not video).
 */
object BulkItemsBuilder {

    fun build(
        capture: SegmentCandidateRegistry.TabMediaCapture,
        domCandidates: List<MediaCandidate>
    ): List<BulkItem> {
        val out = LinkedHashMap<String, BulkItem>()
        capture.streamGroups.forEach { group ->
            if (group.kind == MediaTypes.HLS) {
                out.putIfAbsent(
                    group.best.url,
                    BulkItem(group.best.url, MediaNaming.displayTitle(group.best.url), MediaTypes.HLS)
                )
            }
        }
        val streamUrls = capture.streamGroups.flatMap { group -> group.variants.map { it.url } }.toSet()
        capture.mediaGroups.forEach { g ->
            out.putIfAbsent(g.url, BulkItem(g.url, MediaNaming.displayTitle(g.url), g.kind))
        }
        domCandidates.forEach { c ->
            if (c.type == MediaTypes.IMAGE || c.type == MediaTypes.DASH) return@forEach
            if (c.url in streamUrls) return@forEach
            out.putIfAbsent(
                c.url,
                BulkItem(c.url, MediaNaming.displayTitle(c.url, fallback = MediaTypes.label(c.type)), c.type)
            )
        }
        return out.values.toList()
    }
}
