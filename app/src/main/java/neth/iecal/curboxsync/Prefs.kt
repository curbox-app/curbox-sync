package neth.iecal.curboxsync

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("curbox_sync", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    var curboxPackage: String?
        get() = prefs.getString("curbox_package", null)
        set(value) = prefs.edit { putString("curbox_package", value) }

    /**
     * The phone's own code (for typing into a browser) plus one key per browser that showed this
     * phone its QR code. Derived keys are cached because PBKDF2 is slow on purpose.
     */
    fun pairing(): Pairing {
        val code = prefs.getString("code", null)
        val key = prefs.getString("key", null)
        if (code != null && key != null) return Pairing(code, LanCrypto.fromBase64Url(key), browserKeys())
        return resetPairing()
    }

    /** Forgets every browser and makes a new typed code. */
    fun resetPairing(): Pairing {
        val code = LanCrypto.newCode()
        val key = LanCrypto.deriveKey(code)
        prefs.edit {
            putString("code", code)
            putString("key", LanCrypto.toBase64Url(key))
            putString("browser_keys", "")
        }
        return Pairing(code, key, emptyList())
    }

    /** Adds the key for a code a browser showed as a QR code. The newest few are kept. */
    fun addBrowserCode(code: String): Pairing {
        val key = LanCrypto.toBase64Url(LanCrypto.deriveKey(code))
        val keys = (listOf(key) + prefs.getString("browser_keys", "").orEmpty().split(",").filter { it.isNotBlank() })
            .distinct()
            .take(MAX_BROWSERS)
        prefs.edit { putString("browser_keys", keys.joinToString(",")) }
        return pairing()
    }

    private fun browserKeys(): List<ByteArray> =
        prefs.getString("browser_keys", "").orEmpty().split(",").filter { it.isNotBlank() }.map(LanCrypto::fromBase64Url)

    companion object {
        private const val MAX_BROWSERS = 8
    }
}

class Pairing(val code: String, val key: ByteArray, val browserKeys: List<ByteArray>) {
    val allKeys: List<ByteArray> get() = browserKeys + key
    val browserCount: Int get() = browserKeys.size
}
