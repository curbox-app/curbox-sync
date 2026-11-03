package neth.iecal.curboxsync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deliberately tiny HTTP/1.1 server for the WiFi link. Every request body is sealed with the
 * pairing key, so the transport itself carries no secrets. Only private network peers are served.
 *
 *   GET  /v1/hello  -> {"app":"curbox-sync","proto":1}
 *   POST /v1/rpc    -> sealed request in, sealed response out (401 when it does not open)
 */
class LanServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val rpc: suspend (String) -> String?,
) {
    private var socket: ServerSocket? = null
    private val open = AtomicInteger(0)

    fun start() {
        if (socket != null) return
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = s
        scope.launch(Dispatchers.IO) { acceptLoop(s) }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            if (!isPrivate(client.inetAddress) || open.get() >= MAX_CONNECTIONS) {
                runCatching { client.close() }
                continue
            }
            open.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                try {
                    client.use { serve(it) }
                } catch (e: Exception) {
                    Log.d(TAG, "Connection ended: ${e.message}")
                } finally {
                    open.decrementAndGet()
                }
            }
        }
    }

    private suspend fun serve(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream().buffered()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return respond(client, 400, "")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

        when {
            method == "OPTIONS" -> respond(client, 204, "")
            method == "GET" && path == "/v1/hello" -> respond(client, 200, """{"app":"curbox-sync","proto":1}""", "application/json")
            method == "POST" && path == "/v1/rpc" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: return respond(client, 411, "")
                if (length !in 1..MAX_BODY) return respond(client, 413, "")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val reply = rpc(String(body))
                if (reply == null) respond(client, 401, "") else respond(client, 200, reply)
            }
            else -> respond(client, 404, "")
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || out.size() > MAX_HEAD) return null
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
        }
        return out.toString(Charsets.ISO_8859_1.name()).trimEnd()
    }

    private fun respond(client: Socket, code: Int, body: String, type: String = "text/plain") {
        val bytes = body.toByteArray()
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            // Requests are authenticated by the sealed body, so any origin may try.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: content-type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PORT = 47621
        private const val TAG = "LanServer"
        private const val MAX_CONNECTIONS = 16
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 64 * 1024

        /** WiFi peers only: private, link local or loopback addresses. */
        fun isPrivate(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
            return address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
        }
    }
}
