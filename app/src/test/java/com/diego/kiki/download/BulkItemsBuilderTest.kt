package com.diego.kiki.download

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkItemsBuilderTest {

    private fun capture(
        streamGroups: List<SegmentCandidateRegistry.StreamGroup> = emptyList(),
        mediaGroups: List<SegmentCandidateRegistry.MediaGroup> = emptyList()
    ) = SegmentCandidateRegistry.TabMediaCapture(
        playlists = emptyList(),
        mediaGroups = mediaGroups,
        segmentGroups = emptyList(),
        streamGroups = streamGroups
    )

    private fun streamGroup(url: String, kind: String, vararg variantUrls: String) =
        SegmentCandidateRegistry.StreamGroup(
            host = "cdn.example.com",
            kind = kind,
            best = SegmentCandidateRegistry.PlaylistCapture(url, kind),
            variants = variantUrls.map { SegmentCandidateRegistry.PlaylistCapture(it, kind) },
            segmentCount = 3
        )

    @Test
    fun `hls stream becomes one row on its best url`() {
        val items = BulkItemsBuilder.build(
            capture(streamGroups = listOf(streamGroup("https://x/master.m3u8", MediaTypes.HLS, "https://x/v1.m3u8"))),
            emptyList()
        )
        assertEquals(1, items.size)
        assertEquals("https://x/master.m3u8", items[0].url)
        assertEquals(MediaTypes.HLS, items[0].type)
    }

    @Test
    fun `dash stream groups are skipped`() {
        val items = BulkItemsBuilder.build(
            capture(streamGroups = listOf(streamGroup("https://x/manifest.mpd", MediaTypes.DASH))),
            emptyList()
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `media groups follow streams in capture order`() {
        val items = BulkItemsBuilder.build(
            capture(
                streamGroups = listOf(streamGroup("https://x/master.m3u8", MediaTypes.HLS)),
                mediaGroups = listOf(
                    SegmentCandidateRegistry.MediaGroup("https://y/ep1.mp4", "y.com", 2, MediaTypes.VIDEO, 1L),
                    SegmentCandidateRegistry.MediaGroup("https://y/ep2.mp4", "y.com", 1, MediaTypes.VIDEO, 2L)
                )
            ),
            emptyList()
        )
        assertEquals(listOf("https://x/master.m3u8", "https://y/ep1.mp4", "https://y/ep2.mp4"), items.map { it.url })
    }

    @Test
    fun `dom candidates skip images dash and stream variant urls`() {
        val items = BulkItemsBuilder.build(
            capture(streamGroups = listOf(
                streamGroup("https://x/master.m3u8", MediaTypes.HLS, "https://x/v1.m3u8")
            )),
            listOf(
                MediaCandidate("https://x/v1.m3u8", MediaTypes.HLS),
                MediaCandidate("https://z/pic.jpg", MediaTypes.IMAGE),
                MediaCandidate("https://x/manifest.mpd", MediaTypes.DASH),
                MediaCandidate("https://y/clip.mp4", MediaTypes.VIDEO)
            )
        )
        assertEquals(listOf("https://x/master.m3u8", "https://y/clip.mp4"), items.map { it.url })
    }

    @Test
    fun `duplicate urls keep only the first occurrence`() {
        val items = BulkItemsBuilder.build(
            capture(mediaGroups = listOf(
                SegmentCandidateRegistry.MediaGroup("https://y/ep1.mp4", "y.com", 2, MediaTypes.VIDEO, 1L)
            )),
            listOf(
                MediaCandidate("https://y/ep1.mp4", MediaTypes.VIDEO),
                MediaCandidate("https://y/ep2.mp4", MediaTypes.VIDEO)
            )
        )
        assertEquals(listOf("https://y/ep1.mp4", "https://y/ep2.mp4"), items.map { it.url })
    }
}
