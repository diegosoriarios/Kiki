package com.diego.kiki.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3U8ParserTest {

    private val baseUrl = "https://example.com/stream/video/index.m3u8"

    @Test
    fun `parses master playlist with variants`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"
            video/360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4128000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            video/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
            video/720p.m3u8
        """.trimIndent()

        val playlist = M3U8Parser.parse(text, baseUrl)
        assertTrue(playlist is HlsPlaylist.Master)
        val master = playlist as HlsPlaylist.Master
        assertEquals(3, master.variants.size)

        val highest = master.variants.maxByOrNull { it.bandwidth }!!
        assertEquals(4128000L, highest.bandwidth)
        assertEquals("https://example.com/stream/video/video/1080p.m3u8", highest.url)
        assertEquals("avc1.640028,mp4a.40.2", highest.codecs)
        assertEquals("1920x1080", highest.resolution)
    }

    @Test
    fun `parses media playlist with relative urls and endlist`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            segment0.ts
            #EXTINF:9.009,
            segment1.ts
            #EXTINF:8.0,
            ../seg/segment2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3U8Parser.parse(text, baseUrl)
        assertTrue(playlist is HlsPlaylist.Media)
        val media = (playlist as HlsPlaylist.Media).playlist

        assertFalse(media.isLive)
        assertEquals(3, media.segments.size)
        assertEquals(10L, media.targetDurationSeconds)
        assertEquals(0L, media.mediaSequence)

        assertEquals("https://example.com/stream/video/segment0.ts", media.segments[0].url)
        assertEquals("https://example.com/stream/seg/segment2.ts", media.segments[2].url)
        assertEquals(0L, media.segments[0].sequence)
        assertEquals(9.009, media.segments[0].durationSeconds, 0.001)
        assertNull(media.segments[0].key)
    }

    @Test
    fun `detects live stream without endlist`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXTINF:6.0,
            live120.ts
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        assertTrue(media.isLive)
        assertEquals(120L, media.mediaSequence)
        assertEquals(120L, media.segments[0].sequence)
    }

    @Test
    fun `parses aes-128 key with iv and applies to segments`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x9c7db8778570d05c3177c349fd9236aa
            #EXTINF:10.0,
            seg5.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        val key = media.segments[0].key
        assertNotNull(key)
        assertTrue(key!!.isAes128)
        assertEquals("https://example.com/stream/video/key.bin", key.uri)
        assertEquals("0x9c7db8778570d05c3177c349fd9236aa", key.ivHex)
    }

    @Test
    fun `key method none clears encryption for later segments`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k1.key",IV=0x00000000000000000000000000000001
            #EXTINF:10.0,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:10.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        assertNotNull(media.segments[0].key)
        assertNull(media.segments[1].key)
    }

    @Test
    fun `sample-aes and widevine keys are detected as drm`() {
        val sampleAes = HlsKey(method = "SAMPLE-AES", uri = "https://x.com/k", ivHex = null)
        assertTrue(sampleAes.isDrmOrUnsupported)

        val fairPlay = HlsKey(
            method = "AES-128",
            uri = "https://x.com/k",
            keyFormat = "com.apple.streamingkeydelivery"
        )
        assertTrue(fairPlay.isDrmOrUnsupported)

        val widevine = HlsKey(
            method = "AES-128",
            uri = "https://x.com/k",
            keyFormat = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        )
        assertTrue(widevine.isDrmOrUnsupported)

        val plain = HlsKey(method = "AES-128", uri = "https://x.com/k")
        assertFalse(plain.isDrmOrUnsupported)
    }

    @Test
    fun `drm keyformat parsed from playlist attribute list with quotes`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.key",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXTINF:10.0,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        val key = media.segments[0].key!!
        assertTrue(key.isDrmOrUnsupported)
        assertEquals("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", key.keyFormat)
    }

    @Test
    fun `parses byterange with explicit and implicit offsets`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-BYTERANGE:75232@0
            #EXTINF:8.0,
            media.ts
            #EXT-X-BYTERANGE:82112
            #EXTINF:8.0,
            media.ts
            #EXT-X-BYTERANGE:69864
            #EXTINF:8.0,
            media.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        assertEquals(0L, media.segments[0].byterange!!.offset)
        assertEquals(75232L, media.segments[0].byterange!!.length)
        // Implicit offset = previous offset + length of same resource
        assertEquals(75232L, media.segments[1].byterange!!.offset)
        assertEquals(75232L + 82112L, media.segments[2].byterange!!.offset)
    }

    @Test
    fun `parses ext-x-map init segment`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = (M3U8Parser.parse(text, baseUrl) as HlsPlaylist.Media).playlist
        assertEquals("https://example.com/stream/video/init.mp4", media.initSegmentUrl)
    }

    @Test
    fun `attribute list parser handles quoted commas`() {
        val attrs = M3U8Parser.parseAttributeList(
            """BANDWIDTH=1000,CODECS="avc1.42E01E,mp4a.40.2",URI="http://a/b?x=1,2""""
        )
        assertEquals("1000", attrs["BANDWIDTH"])
        assertEquals("avc1.42E01E,mp4a.40.2", attrs["CODECS"])
        assertEquals("http://a/b?x=1,2", attrs["URI"])
    }

    @Test
    fun `rejects non-m3u8 content`() {
        val thrown = try {
            M3U8Parser.parse("<html>404</html>", baseUrl)
            null
        } catch (e: M3U8Parser.ParseException) {
            e
        }
        assertNotNull(thrown)
    }

    @Test
    fun `absolute urls pass through unchanged`() {
        assertEquals(
            "https://cdn.other.com/seg.ts",
            M3U8Parser.resolve(baseUrl, "https://cdn.other.com/seg.ts")
        )
        assertEquals(
            "http://insecure.com/a.ts",
            M3U8Parser.resolve(baseUrl, "http://insecure.com/a.ts")
        )
    }
}
