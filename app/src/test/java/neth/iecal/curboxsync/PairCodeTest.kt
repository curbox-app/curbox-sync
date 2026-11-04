package neth.iecal.curboxsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same vector as the extension's shortcode.test.ts; change both together. */
class PairCodeTest {
    private val code = PairCode.encode(29, 197, 12345)
    private val shared = ByteArray(32) { it.toByte() }

    @Test fun roundTrips() {
        assertEquals("3Q2W1S", code)
        assertEquals(PairCode.Parts(29, 197, 12345), PairCode.decode(code))
        assertEquals(PairCode.Parts(255, 255, 0x3fff), PairCode.decode(PairCode.encode(255, 255, 0x3fff)))
        assertNull(PairCode.decode("3Q2"))
        assertEquals("3Q2W1S", PairCode.forAddress("192.168.29.197:47621", 12345))
    }

    @Test fun forgivesTyping() {
        assertEquals("1VJ61S", PairCode.normalize(" 1vj-61s "))
        assertEquals("011V", PairCode.normalize("oIlu"))
        assertEquals("1VJ 61S", PairCode.format("1vj61s"))
    }

    @Test fun candidatesStartOnOwnNetwork() {
        val c = PairCode.candidates(code, listOf("10.5.0.3:47621", "100.72.1.1:47621"))
        assertEquals("10.5.29.197:47621", c[0])
        assertEquals("192.168.29.197:47621", c[1])
    }

    @Test fun matchesExtensionCrypto() {
        val k = PairCode.codeKey(code)
        assertEquals("For3oO9sKAS46pMH8RMqaKreThcuDrKRYIXSOUQJQM4", LanCrypto.toBase64Url(k))
        assertEquals("pAqx2xJdEB8ycAJdSHnS5CUgIyRj-e1US0w9q0NWhtg", PairCode.proof(k, 'b', "phonePUB", "browserPUB"))
        assertEquals("xjneHoCfoAxMf0-2JkfxTrON4bWQ34sd5VzsfejYJ3A", PairCode.proof(k, 'p', "phonePUB", "browserPUB"))
        assertEquals("quf9YQAdHSYjsdSQnQQRdEpAPiJMVK6zFV1tc2VnGjY", LanCrypto.toBase64Url(PairCode.sessionKey(shared, k, "phonePUB", "browserPUB")))
    }

    @Test fun bothSidesAgree() {
        val a = PairCode.newKeyPair()
        val b = PairCode.newKeyPair()
        assertArrayEquals(PairCode.shared(a, PairCode.publicKey(b)), PairCode.shared(b, PairCode.publicKey(a)))
    }
}
