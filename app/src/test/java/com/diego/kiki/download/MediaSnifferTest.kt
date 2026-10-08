package com.diego.kiki.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parses the JSON emitted by the page-side INJECT_JS enumerator. */
class MediaSnifferTest {

    private val sniffer = MediaSniffer(
        tabId = "t",
        onCandidates = { _, _ -> },
        onPlaylist = { _, _, _, _ -> },
        onBlobEvent = { _, _ -> }
    )

    @Test
    fun `parses url type and tag`() {
        val candidates = sniffer.parseCandidates(
            """[{"url":"https://cdn.com/a.mp4","type":"VIDEO","tag":"video"}]"""
        )
        assertEquals(1, candidates.size)
        assertEquals("https://cdn.com/a.mp4", candidates[0].url)
        assertEquals(MediaTypes.VIDEO, candidates[0].type)
        assertEquals("video", candidates[0].sourceTag)
    }

    @Test
    fun `parses poster and dimensions`() {
        val candidates = sniffer.parseCandidates(
            """[{"url":"https://cdn.com/a.mp4","type":"VIDEO","tag":"video",
                "poster":"https://cdn.com/p.jpg","w":1920,"h":1080}]"""
        )
        assertEquals("https://cdn.com/p.jpg", candidates[0].posterUrl)
        assertEquals(1920, candidates[0].width)
        assertEquals(1080, candidates[0].height)
    }

    @Test
    fun `blob urls are accepted`() {
        val candidates = sniffer.parseCandidates(
            """[{"url":"blob:https://site.com/abc-123","type":"BLOB","tag":"video","h":720}]"""
        )
        assertEquals(1, candidates.size)
        assertEquals(MediaTypes.BLOB, candidates[0].type)
        assertEquals(720, candidates[0].height)
    }

    @Test
    fun `missing metadata defaults cleanly`() {
        val candidates = sniffer.parseCandidates(
            """[{"url":"https://cdn.com/a.mp4","type":"VIDEO","tag":"video","poster":null,"w":0,"h":0}]"""
        )
        assertNull(candidates[0].posterUrl)
        assertEquals(0, candidates[0].width)
        assertEquals(0, candidates[0].height)
    }

    @Test
    fun `non-http urls are dropped`() {
        val candidates = sniffer.parseCandidates(
            """[{"url":"javascript:void(0)","type":"VIDEO","tag":"a"},
                {"url":"data:video/mp4;base64,AAAA","type":"VIDEO","tag":"a"}]"""
        )
        assertEquals(0, candidates.size)
    }

    @Test
    fun `malformed json yields empty list`() {
        assertEquals(0, sniffer.parseCandidates("not json").size)
    }
}
