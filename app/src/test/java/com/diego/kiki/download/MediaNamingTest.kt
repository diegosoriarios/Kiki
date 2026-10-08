package com.diego.kiki.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamingTest {

    // displayTitle / smartBaseName -------------------------------------------------

    @Test
    fun `episode filename becomes the title`() {
        assertEquals("ep2", MediaNaming.displayTitle("https://cdn.acme.com/videos/ep2.mp4"))
    }

    @Test
    fun `generic playlist name falls back to parent directory`() {
        assertEquals(
            "video1",
            MediaNaming.displayTitle("https://cdn.com/hls/video1/index.m3u8")
        )
    }

    @Test
    fun `all-generic url falls back to host`() {
        assertEquals("acme.com", MediaNaming.displayTitle("https://acme.com/index.m3u8"))
    }

    @Test
    fun `unparseable url falls back to provided label`() {
        assertEquals("Media", MediaNaming.displayTitle("::::", fallback = "Media"))
    }

    @Test
    fun `query strings are ignored`() {
        assertEquals("ep2", MediaNaming.displayTitle("https://cdn.com/v/ep2.mp4?token=abc"))
    }

    @Test
    fun `url-encoded names are decoded`() {
        assertEquals("my video", MediaNaming.displayTitle("https://cdn.com/v/my%20video.mp4"))
    }

    @Test
    fun `hash-like names are treated as generic`() {
        assertEquals(
            "acme.com",
            MediaNaming.smartBaseName("https://acme.com/media/a1b2c3d4e5f6a7b8c9d0.mp4").let {
                if (MediaNaming.isGenericToken(it)) "acme.com" else it
            }
        )
    }

    @Test
    fun `pure numeric filenames are generic`() {
        assertTrue(MediaNaming.isGenericToken("12345"))
        assertFalse(MediaNaming.isGenericToken("ep2"))
        assertFalse(MediaNaming.isGenericToken("trailer-2024"))
    }

    // qualityLabel ------------------------------------------------------------------

    @Test
    fun `quality labels from height`() {
        assertEquals("1080p", MediaNaming.qualityLabel(1080))
        assertEquals("4K", MediaNaming.qualityLabel(2160))
        assertEquals("720p", MediaNaming.qualityLabel(720))
        assertNull(MediaNaming.qualityLabel(0))
    }

    @Test
    fun `bandwidth labels`() {
        assertEquals("4.5 Mbps", MediaNaming.bandwidthLabel(4_500_000))
        assertEquals("1 Mbps", MediaNaming.bandwidthLabel(1_000_000))
        assertEquals("480 kbps", MediaNaming.bandwidthLabel(480_000))
        assertNull(MediaNaming.bandwidthLabel(0))
    }

    @Test
    fun `combined label from resolution and bandwidth`() {
        assertEquals(
            "1080p · 4.5 Mbps",
            MediaNaming.qualityLabelFor("1920x1080", 4_500_000)
        )
        assertEquals("720p", MediaNaming.qualityLabelFor("1280x720", 0))
        assertEquals("4 Mbps", MediaNaming.qualityLabelFor(null, 4_000_000))
        assertNull(MediaNaming.qualityLabelFor(null, 0))
    }

    @Test
    fun `dom height override wins over resolution`() {
        assertEquals(
            "480p · 1 Mbps",
            MediaNaming.qualityLabelFor("1920x1080", 1_000_000, heightOverride = 480)
        )
    }

    // shortHost ---------------------------------------------------------------------

    @Test
    fun `www prefix is stripped and case normalized`() {
        assertEquals("cdn.acme.com", MediaNaming.shortHost("https://WWW.CDN.Acme.com/v/a.mp4"))
    }

    @Test
    fun `invalid host yields empty string`() {
        assertEquals("", MediaNaming.shortHost("not a url"))
    }
}
