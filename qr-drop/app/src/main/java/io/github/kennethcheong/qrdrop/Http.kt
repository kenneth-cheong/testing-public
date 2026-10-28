package io.github.kennethcheong.qrdrop

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** One parsed HTTP/1.1 request. [body] reads exactly the request body (sized or chunked). */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
)

class HttpException(val status: Int, message: String) : IOException(message)

/** Just enough HTTP/1.1 for a browser uploading files: one request per connection. */
object Http {
    private const val MAX_HEADER_BYTES = 16 * 1024

    fun read(input: InputStream): HttpRequest {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 3) throw HttpException(400, "Bad request line")
        val headers = lines.drop(1).filter { ':' in it }.associate { line ->
            line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
        }
        val target = parts[1]
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedInputStream(input)
            else -> LimitedInputStream(input, headers["content-length"]?.toLongOrNull() ?: 0L)
        }
        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    fun respond(output: OutputStream, status: Int, contentType: String, body: ByteArray, extraHeaders: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw HttpException(400, "Connection closed before the request was complete")
            buffer.write(b)
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
            if (buffer.size() > MAX_HEADER_BYTES) throw HttpException(431, "Headers too large")
        }
        return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        302 -> "Found"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }
}

/** Reads at most [remaining] bytes, then reports end of stream. */
private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining-- else throw HttpException(400, "Upload was cut off")
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw HttpException(400, "Upload was cut off")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}

/** Decodes a chunked transfer-encoded body. */
private class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (left == 0L) {
            left = readLine().substringBefore(';').trim().toLongOrNull(16) ?: throw HttpException(400, "Bad chunk size")
            if (left == 0L) {
                while (readLine().isNotEmpty()) Unit // trailers
                done = true
                return -1
            }
        }
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw HttpException(400, "Upload was cut off")
        left -= n
        if (left == 0L) readLine() // CRLF after the chunk
        return n
    }

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw HttpException(400, "Upload was cut off")
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(c.toChar())
        }
    }
}
