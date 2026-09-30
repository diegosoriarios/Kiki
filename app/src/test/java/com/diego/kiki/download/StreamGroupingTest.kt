package com.diego.kiki.download

import com.diego.kiki.download.SegmentCandidateRegistry.PlaylistCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamGroupingTest {

    @Test
    fun `master with variants under its directory collapse to one group`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://cdn.com/vod/master.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MASTER),
                StreamInput("https://cdn.com/vod/720p/playlist.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MEDIA),
                StreamInput("https://cdn.com/vod/1080p/playlist.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MEDIA)
            ),
            segments = emptyList()
        )
        assertEquals(1, groups.size)
        assertEquals("https://cdn.com/vod/master.m3u8", groups[0].bestUrl)
        assertEquals(3, groups[0].variantUrls.size)
    }

    @Test
    fun `different hosts never merge`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://a.com/v/master.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MASTER),
                StreamInput("https://b.com/v/master.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MASTER)
            ),
            segments = emptyList()
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `unknown-role playlists on same directory group with first as best`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://cdn.com/hls/video1/index.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_UNKNOWN),
                StreamInput("https://cdn.com/hls/video1/index_1.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_UNKNOWN)
            ),
            segments = emptyList()
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].variantUrls.size)
        assertEquals("https://cdn.com/hls/video1/index.m3u8", groups[0].bestUrl)
    }

    @Test
    fun `unrelated directories stay separate streams`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://cdn.com/hls/video1/index.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_UNKNOWN),
                StreamInput("https://cdn.com/hls/video2/index.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_UNKNOWN)
            ),
            segments = emptyList()
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `segments under the stream directory are counted`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://cdn.com/vod/master.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MASTER)
            ),
            segments = listOf(
                SegmentSummary("cdn.com", "https://cdn.com/vod/seg-1.ts", 40),
                SegmentSummary("cdn.com", "https://cdn.com/vod/seg-2.ts", 40),
                SegmentSummary("cdn.com", "https://other.com/x/seg-0.ts", 10)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(80, groups[0].segmentCount)
    }

    @Test
    fun `kind is never mixed inside one group`() {
        val groups = groupStreams(
            playlists = listOf(
                StreamInput("https://cdn.com/v/master.m3u8", MediaTypes.HLS, PlaylistCapture.ROLE_MASTER),
                StreamInput("https://cdn.com/v/manifest.mpd", MediaTypes.DASH, PlaylistCapture.ROLE_UNKNOWN)
            ),
            segments = emptyList()
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.kind == MediaTypes.HLS || it.kind == MediaTypes.DASH })
    }
}
