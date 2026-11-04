package neth.iecal.curboxsync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Talks to another phone's [LanServer] the way the extension does. Plain sockets, since the
 * server speaks a tiny HTTP subset and Android blocks cleartext HttpURLConnection by default.
 */
object LanClient {
    class Unreachable(message: String) : IOException(message)
    class Rejected : IOException("The other phone did not accept the code")

    private const val MAX_REPLY = 2 * 1024 * 1024
    private val random = SecureRandom()

    /** True when Curbox Sync answers at [address]. */
    fun hello(address: String, timeoutMs: Int = 1_200): Boolean = runCatching {
        val (status, body) = http("GET", address, "/v1/hello", null, timeoutMs)
        status == 200 && JSONObject(body).optString("app") == "curbox-sync"
    }.getOrDefault(false)

    /** Sends a sealed request and opens the sealed reply. */
    fun rpc(address: String, key: ByteArray, body: JSONObject, timeoutMs: Int = 10_000): JSONObject {
        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        body.put("id", id).put("ts", System.currentTimeMillis())
        val blob = LanCrypto.seal(key, LanCrypto.REQUEST_AAD, body.toString())
        val (status, text) = try {
            http("POST", address, "/v1/rpc", blob, timeoutMs)
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "unreachable")
        }
        if (status == 401) throw Rejected()
        if (status != 200) throw Unreachable("HTTP $status")
        val reply = JSONObject(LanCrypto.open(key, LanCrypto.responseAad(id), text))
        if (reply.optJSONObject("phone") == null) throw Unreachable("bad reply")
        return reply
    }

    /** A plain (unsealed) request, for short code pairing. */
    fun plain(method: String, address: String, path: String, body: String?, timeoutMs: Int = 4_000): Pair<Int, String> =
        http(method, address, path, body, timeoutMs)

    private fun http(method: String, address: String, path: String, body: String?, timeoutMs: Int): Pair<Int, String> {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: LanServer.PORT
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), minOf(timeoutMs, 3_000))
            s.soTimeout = timeoutMs
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $address\r\n")
                if (body != null) append("Content-Type: text/plain\r\nContent-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            s.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8_192)
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_REPLY) throw IOException("reply too large")
            }
            val raw = out.toByteArray()
            val split = (0..raw.size - 4).firstOrNull {
                raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() && raw[it + 2] == '\r'.code.toByte() && raw[it + 3] == '\n'.code.toByte()
            } ?: throw IOException("no reply head")
            val status = String(raw, 0, split, Charsets.ISO_8859_1).lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("bad status")
            return status to String(raw, split + 4, raw.size - split - 4)
        }
    }
}
