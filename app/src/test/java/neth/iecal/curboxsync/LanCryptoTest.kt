package neth.iecal.curboxsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// The same vector is asserted by the extension's src/lib/lan/crypto.test.ts.
class LanCryptoTest {
    private val code = "abcd efgh jkmn"
    private val keyB64 = "tE9kkV_GRNDzPb-01O88D1KAlYvYBCe17xPpLFql3IM"
    private val blob = "AAECAwQFBgcICQoLEznSUdb8T3fZS0qz5HeeGjGzJN-kzwbm3sL0Mtro"

    @Test
    fun derivesSharedKey() {
        assertEquals(keyB64, LanCrypto.toBase64Url(LanCrypto.deriveKey(code)))
    }

    @Test
    fun sealsSharedVector() {
        val key = LanCrypto.fromBase64Url(keyB64)
        val nonce = ByteArray(12) { it.toByte() }
        assertEquals(blob, LanCrypto.sealWithNonce(key, LanCrypto.REQUEST_AAD, """{"op":"state"}""", nonce))
        assertEquals("""{"op":"state"}""", LanCrypto.open(key, LanCrypto.REQUEST_AAD, blob))
    }

    @Test
    fun rejectsWrongAad() {
        val key = LanCrypto.fromBase64Url(keyB64)
        assertThrows(Exception::class.java) { LanCrypto.open(key, LanCrypto.responseAad("x"), blob) }
    }

    @Test
    fun formatsCode() {
        assertEquals("ABCD EFGH JKMN", LanCrypto.format(code))
    }
}
