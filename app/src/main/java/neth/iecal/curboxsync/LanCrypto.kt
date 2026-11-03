package neth.iecal.curboxsync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for the WiFi link. Must stay byte identical with the extension's `src/lib/lan/crypto.ts`.
 *
 * key  = PBKDF2-HMAC-SHA256(normalized pairing code, salt "curbox-lan-v1", 100k, 32 bytes)
 * blob = base64url(nonce[12] || AES-256-GCM ciphertext+tag), no padding
 * AAD  = "curbox-lan-v1|req" for requests, "curbox-lan-v1|res|<request id>" for responses
 */
object LanCrypto {
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    const val CODE_LENGTH = 12
    const val ITERATIONS = 100_000
    const val REQUEST_AAD = "curbox-lan-v1|req"
    fun responseAad(id: String) = "curbox-lan-v1|res|$id"

    private val SALT = "curbox-lan-v1".toByteArray()
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun newCode(): String = buildString {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(code: String): String = code.uppercase().filter { it in ALPHABET }

    fun format(code: String): String = normalize(code).chunked(4).joinToString(" ")

    fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(normalize(code).toCharArray(), SALT, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun seal(key: ByteArray, aad: String, plaintext: String): String =
        sealWithNonce(key, aad, plaintext, ByteArray(12).also(random::nextBytes))

    internal fun sealWithNonce(key: ByteArray, aad: String, plaintext: String, nonce: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return encoder.encodeToString(nonce + cipher.doFinal(plaintext.toByteArray()))
    }

    /** Throws when the blob was not sealed with this key and AAD. */
    fun open(key: ByteArray, aad: String, blob: String): String {
        val raw = decoder.decode(blob.trim())
        require(raw.size > 12 + 16) { "blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        return String(cipher.doFinal(raw, 12, raw.size - 12))
    }

    fun toBase64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun fromBase64Url(s: String): ByteArray = decoder.decode(s)
}
