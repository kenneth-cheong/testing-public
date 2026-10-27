package io.github.kennethcheong.qrdrop

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Where uploaded files go. */
fun interface FileSink {
    fun save(name: String, mimeType: String?, body: InputStream): SavedFile
}

data class SavedFile(val name: String, val bytes: Long, val uri: String)

sealed interface DropEvent {
    data class Receiving(val name: String) : DropEvent
    data class Received(val file: SavedFile) : DropEvent
    data class Failed(val name: String, val reason: String) : DropEvent
}

/**
 * The receiver. Everything lives under a secret path, `/<token>/`, which is only
 * in the QR code, so someone who merely joins the same Wi-Fi can't send files.
 *
 * - `GET /<token>/` is the Safari send page.
 * - `PUT /<token>/upload?name=<file name>` with the file as the body saves it.
 */
class DropServer(
    private val token: String,
    private val sink: FileSink,
    private val page: () -> String,
    private val onEvent: (DropEvent) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null

    /** Starts listening on all interfaces; returns the port actually used. */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) }
        } catch (e: IOException) {
            ServerSocket(0)
        }
        val pool = Executors.newCachedThreadPool()
        socket = server
        workers = pool
        pool.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        socket?.close()
        workers?.shutdownNow()
        socket = null
        workers = null
    }

    private fun serve(client: Socket) = client.use {
        it.soTimeout = 60_000
        handle(it.getInputStream().buffered(), it.getOutputStream().buffered())
    }

    /** One request/response; public so tests can drive it without sockets. */
    fun handle(input: InputStream, output: OutputStream) {
        try {
            val request = Http.read(input)
            route(request, output)
        } catch (e: HttpException) {
            runCatching { Http.respond(output, e.status, "text/plain; charset=utf-8", (e.message ?: "Error").toByteArray()) }
        } catch (e: IOException) {
            // The browser went away mid-request; nothing to answer.
        }
    }

    private fun route(request: HttpRequest, output: OutputStream) {
        val base = "/$token"
        when {
            request.path == base -> Http.respond(output, 302, "text/plain", ByteArray(0), mapOf("Location" to "$base/"))
            !request.path.startsWith("$base/") -> Http.respond(output, 403, "text/plain; charset=utf-8", "Scan the QR code on the phone to send files.".toByteArray())
            request.path == "$base/" && request.method == "GET" ->
                Http.respond(output, 200, "text/html; charset=utf-8", page().toByteArray())
            request.path == "$base/upload" && (request.method == "PUT" || request.method == "POST") -> upload(request, output)
            else -> Http.respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
        }
    }

    private fun upload(request: HttpRequest, output: OutputStream) {
        if (request.headers["content-length"] == null && request.headers["transfer-encoding"] == null) {
            throw HttpException(411, "Missing Content-Length")
        }
        val name = safeFileName(request.query["name"])
        onEvent(DropEvent.Receiving(name))
        val saved = try {
            sink.save(name, request.headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }, request.body)
        } catch (e: IOException) {
            onEvent(DropEvent.Failed(name, e.message ?: "Upload failed"))
            // If the browser is still there, tell it; if it went away, there's no one to tell.
            runCatching { Http.respond(output, 500, "text/plain; charset=utf-8", (e.message ?: "Upload failed").toByteArray()) }
            return
        }
        onEvent(DropEvent.Received(saved))
        Http.respond(output, 201, "application/json", """{"ok":true,"name":${jsonString(saved.name)},"bytes":${saved.bytes}}""".toByteArray())
    }

    companion object {
        const val DEFAULT_PORT = 8484

        /** 10 random characters from an unambiguous alphabet. */
        fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }

        /** Keeps the last path segment, drops characters file systems dislike, caps the length. */
        fun safeFileName(raw: String?): String {
            val leaf = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            val cleaned = leaf.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.')
            if (cleaned.isEmpty()) return "file"
            if (cleaned.length <= 120) return cleaned
            val ext = cleaned.substringAfterLast('.', "").take(10)
            return if (ext.isEmpty()) cleaned.take(120) else cleaned.take(119 - ext.length) + "." + ext
        }

        private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
