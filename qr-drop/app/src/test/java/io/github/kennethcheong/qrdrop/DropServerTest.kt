package io.github.kennethcheong.qrdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropServerTest {
    private class MemorySink : FileSink {
        val files = mutableMapOf<String, ByteArray>()
        val types = mutableMapOf<String, String?>()
        override fun save(name: String, mimeType: String?, body: java.io.InputStream): SavedFile {
            val bytes = body.readBytes()
            files[name] = bytes
            types[name] = mimeType
            return SavedFile(name, bytes.size.toLong(), "memory://$name")
        }
    }

    private val sink = MemorySink()
    private val events = mutableListOf<DropEvent>()
    private val server = DropServer("tok123", sink, page = { "<html>send page</html>" }) { events += it }

    private fun call(raw: ByteArray): String {
        val out = ByteArrayOutputStream()
        server.handle(ByteArrayInputStream(raw), out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun call(raw: String) = call(raw.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun servesThePageOnlyUnderTheToken() {
        assertTrue(call("GET /tok123/ HTTP/1.1\r\nHost: x\r\n\r\n").let { it.startsWith("HTTP/1.1 200") && it.endsWith("<html>send page</html>") })
        assertTrue(call("GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(call("GET /wrong/ HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(call("GET /tok123 HTTP/1.1\r\n\r\n").let { it.startsWith("HTTP/1.1 302") && "Location: /tok123/" in it })
    }

    @Test
    fun savesAnUploadWithItsNameAndType() {
        val body = "hello watch"
        val response = call("PUT /tok123/upload?name=My%20Photo%20%2B1.jpg HTTP/1.1\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.length}\r\n\r\n$body")

        assertTrue(response, response.startsWith("HTTP/1.1 201"))
        assertEquals(body, String(sink.files.getValue("My Photo +1.jpg")))
        assertEquals("image/jpeg", sink.types["My Photo +1.jpg"])
        assertEquals(
            listOf<DropEvent>(DropEvent.Receiving(1, "My Photo +1.jpg", 11), DropEvent.Received(1, SavedFile("My Photo +1.jpg", 11, "memory://My Photo +1.jpg"))),
            events.filterNot { it is DropEvent.Progress },
        )
    }

    @Test
    fun reportsProgressUpToTheFullSize() {
        val progress = mutableListOf<DropEvent.Progress>()
        val everyRead = DropServer("t", sink, page = { "" }, progressIntervalMs = 0) { if (it is DropEvent.Progress) progress += it }
        val payload = ByteArray(200_000) { it.toByte() }
        val head = "PUT /t/upload?name=p.bin HTTP/1.1\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        everyRead.handle(ByteArrayInputStream(head + payload), ByteArrayOutputStream())

        assertTrue(progress.isNotEmpty())
        assertEquals(progress.map { it.bytes }.sorted(), progress.map { it.bytes })
        assertEquals(DropEvent.Progress(1, "p.bin", 200_000, 200_000), progress.last())
    }

    @Test
    fun readsExactlyContentLengthOfBinaryData() {
        val payload = ByteArray(300_000) { (it * 31).toByte() }
        val head = "PUT /tok123/upload?name=blob.bin HTTP/1.1\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        call(head + payload + "TRAILING-GARBAGE".toByteArray())

        assertTrue(payload.contentEquals(sink.files.getValue("blob.bin")))
        assertNull("no type sent", sink.types["blob.bin"])
    }

    @Test
    fun decodesChunkedUploads() {
        call("PUT /tok123/upload?name=c.txt HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n")
        assertEquals("hello world", String(sink.files.getValue("c.txt")))
    }

    @Test
    fun uploadsNeedTheTokenAndALength() {
        assertTrue(call("PUT /nope/upload?name=x HTTP/1.1\r\nContent-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 403"))
        assertTrue(call("PUT /tok123/upload?name=x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 411"))
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun aCutOffUploadIsReportedAsFailed() {
        val failing = DropServer("t", { _, _, body -> body.readBytes(); error("unreachable") }, page = { "" }) { events += it }
        val out = ByteArrayOutputStream()
        failing.handle(ByteArrayInputStream("PUT /t/upload?name=big.mov HTTP/1.1\r\nContent-Length: 100\r\n\r\nonly-a-bit".toByteArray()), out)

        assertTrue(events.last() is DropEvent.Failed)
    }

    @Test
    fun fileNamesAreCleaned() {
        assertEquals("photo.jpg", DropServer.safeFileName("../../etc/photo.jpg"))
        assertEquals("a b.txt", DropServer.safeFileName("C:\\Users\\a b.txt"))
        assertEquals("what.png", DropServer.safeFileName("wh<a>t?.png"))
        assertEquals("file", DropServer.safeFileName("..."))
        assertEquals("file", DropServer.safeFileName(null))
        val long = DropServer.safeFileName("x".repeat(300) + ".jpeg")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".jpeg"))
    }

    @Test
    fun tokensAreTenUnambiguousCharacters() {
        val token = DropServer.newToken()
        assertTrue(token, Regex("^[a-hjkmnp-z2-9]{10}$").matches(token))
    }

    @Test
    fun interfaceNamesMapToNetworkKinds() {
        assertEquals(LocalAddress.Kind.WIFI, LocalAddresses.kindOf("wlan0"))
        for (hotspot in listOf("ap0", "swlan0", "wlan1", "wlan2", "softap0")) assertEquals(hotspot, LocalAddress.Kind.HOTSPOT, LocalAddresses.kindOf(hotspot))
        for (direct in listOf("p2p0", "p2p-wlan0-0", "p2p-p2p0-1")) assertEquals(direct, LocalAddress.Kind.DIRECT, LocalAddresses.kindOf(direct))
        for (other in listOf("rmnet_data0", "ccmni1", "tun0", "lo", "dummy0")) assertNull(other, LocalAddresses.kindOf(other))
    }

    @Test
    fun eachUploadGetsItsOwnId() {
        call("PUT /tok123/upload?name=a.txt HTTP/1.1\r\nContent-Length: 1\r\n\r\na")
        call("PUT /tok123/upload?name=a.txt HTTP/1.1\r\nContent-Length: 1\r\n\r\nb")
        assertEquals(listOf(1L, 2L), events.filterIsInstance<DropEvent.Received>().map { it.upload })
    }

    @Test
    fun wifiDirectNamesAndPasswordsFitTheRules() {
        repeat(20) {
            val name = DirectHotspot.newNetworkName()
            assertTrue(name, Regex("^DIRECT-[A-Za-z0-9]{2}-QR Drop$").matches(name))
            val password = DirectHotspot.newPassphrase()
            assertTrue(password, password.length in 8..63)
        }
    }

    @Test
    fun onlyFiveGigahertzCountsAsFast() {
        assertTrue(HotspotInfo("x", "y", frequencyMhz = 5745).fast)
        assertTrue(!HotspotInfo("x", "y", frequencyMhz = 2412).fast)
        assertTrue(!HotspotInfo("x", "y").fast)
    }

    @Test
    fun wifiQrEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:abc;def\\:g;;".replace("abc;def", "abc\\;def"), HotspotInfo("AndroidShare_1234", "abc;def:g").qrPayload())
    }
}
