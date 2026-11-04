package neth.iecal.curboxsync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short pairing codes. Must stay byte identical with the extension's `src/lib/lan/shortcode.ts`.
 *
 * Six characters carry the last two parts of this phone's WiFi address and a 14 bit secret. The
 * secret only proves the person typing can see this phone; the key itself comes from ECDH P-256,
 * so listening on the WiFi reveals nothing. Codes are accepted only while the app is open, and are
 * replaced after one use or [MAX_FAILURES] wrong tries.
 *
 *   value = third octet shl 22 or fourth octet shl 14 or secret   (30 bits, 6 Crockford base32 chars)
 *   kCode = SHA-256("curbox-pair-v1|" + code)
 *   proof = HMAC-SHA256(kCode, "b|" + phonePub + "|" + browserPub)   ("p|..." for this phone's)
 *   key   = HKDF-SHA256(ikm = ECDH x, salt = kCode, info = "curbox-lan-v2|" + phonePub + "|" + browserPub)
 */
object PairCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 6
    const val MAX_FAILURES = 5
    private val random = SecureRandom()

    data class Parts(val third: Int, val fourth: Int, val secret: Int)

    fun normalize(raw: String): String = raw.uppercase().map {
        when (it) {
            'O' -> '0'
            'I', 'L' -> '1'
            'U' -> 'V'
            else -> it
        }
    }.filter { it in ALPHABET }.joinToString("")

    fun format(code: String): String = normalize(code).let { if (it.length == LENGTH) "${it.take(3)} ${it.drop(3)}" else it }

    fun encode(third: Int, fourth: Int, secret: Int): String {
        var v = ((third and 0xff) shl 22) or ((fourth and 0xff) shl 14) or (secret and 0x3fff)
        val out = CharArray(LENGTH)
        for (i in LENGTH - 1 downTo 0) {
            out[i] = ALPHABET[v and 31]
            v = v ushr 5
        }
        return String(out)
    }

    fun decode(raw: String): Parts? {
        val code = normalize(raw)
        if (code.length != LENGTH) return null
        var v = 0
        for (c in code) v = v * 32 + ALPHABET.indexOf(c)
        return Parts((v ushr 22) and 0xff, (v ushr 14) and 0xff, v and 0x3fff)
    }

    /** This phone's code on the network at [address] (an IPv4 with or without port). */
    fun forAddress(address: String, secret: Int): String? {
        val o = address.substringBefore(':').split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return null
        return encode(o[2], o[3], secret)
    }

    /** Where the phone with this code may be: its last two parts on this phone's networks first. */
    fun candidates(raw: String, ownAddresses: List<String>): List<String> {
        val d = decode(raw) ?: return emptyList()
        val own = ownAddresses.mapNotNull { a ->
            a.substringBefore(':').split('.').takeIf { it.size == 4 }?.let { "${it[0]}.${it[1]}" }
        }.filter { !it.startsWith("100.") && !it.startsWith("127.") }
        val common = listOf("192.168", "10.0", "10.1", "10.10", "10.42") + (16..31).map { "172.$it" }
        return (own + common).distinct().map { "$it.${d.third}.${d.fourth}:${LanServer.PORT}" }
    }

    fun newSecret(): Int = random.nextInt(0x4000)

    fun codeKey(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("curbox-pair-v1|${normalize(code)}".toByteArray())

    fun proof(kCode: ByteArray, side: Char, phonePub: String, browserPub: String): String =
        LanCrypto.toBase64Url(hmac(kCode, "$side|$phonePub|$browserPub".toByteArray()))

    fun sessionKey(shared: ByteArray, kCode: ByteArray, phonePub: String, browserPub: String): ByteArray {
        val prk = hmac(kCode, shared)
        // One HKDF expand block gives the 32 bytes needed.
        return hmac(prk, "curbox-lan-v2|$phonePub|$browserPub".toByteArray() + byteArrayOf(1))
    }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKey(pair: KeyPair): String = LanCrypto.toBase64Url(pair.public.encoded)

    fun shared(pair: KeyPair, otherPub: String): ByteArray {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(LanCrypto.fromBase64Url(otherPub)))
        return KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(other, true) }.generateSecret()
    }

    fun sameProof(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
