package neth.iecal.curboxsync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("curbox_sync", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    var curboxPackage: String?
        get() = prefs.getString("curbox_package", null)
        set(value) = prefs.edit { putString("curbox_package", value) }

    /**
     * One key per browser and per phone that paired with this one's short code. The older single
     * key is kept so devices paired before short codes keep working until Unpair all.
     */
    fun pairing(): Pairing {
        val code = prefs.getString("code", null)
        val key = prefs.getString("key", null)
        if (code != null && key != null) return Pairing(code, LanCrypto.fromBase64Url(key), keys(BROWSERS), keys(PHONES), relayedKeys())
        return resetPairing()
    }

    /** Forgets every browser and makes a new typed code. */
    fun resetPairing(): Pairing {
        val code = LanCrypto.newCode()
        val key = LanCrypto.deriveKey(code)
        prefs.edit {
            putString("code", code)
            putString("key", LanCrypto.toBase64Url(key))
            putString(BROWSERS, "")
            putString(PHONES, "")
            remove(KEY_OWNERS)
        }
        return Pairing(code, key, emptyList(), emptyList(), relayedKeys())
    }

    /** Saves the key a browser or phone agreed on with this one's short code. The newest few are kept. */
    fun addKey(key: ByteArray, phone: Boolean): Pairing {
        val list = if (phone) PHONES else BROWSERS
        val keys = (listOf(LanCrypto.toBase64Url(key)) + prefs.getString(list, "").orEmpty().split(",").filter { it.isNotBlank() })
            .distinct()
            .take(MAX_KEYS)
        prefs.edit { putString(list, keys.joinToString(",")) }
        return pairing()
    }

    /** Notes which device a key belongs to, so that one device can be unpaired later. */
    fun linkKey(deviceId: String, key: ByteArray) {
        val b64 = LanCrypto.toBase64Url(key)
        val owners = owners()
        if (owners.optString(deviceId) == b64) return
        owners.put(deviceId, b64)
        prefs.edit { putString(KEY_OWNERS, owners.toString()) }
    }

    fun ownsKey(deviceId: String): Boolean = owners().has(deviceId)

    /**
     * Drops one device's key. False when its key is unknown, or is the old shared key that only
     * Unpair all can change.
     */
    fun removeDevice(deviceId: String): Boolean {
        val owners = owners()
        val b64 = owners.optString(deviceId).takeIf { it.isNotEmpty() } ?: return false
        owners.remove(deviceId)
        var removed = false
        prefs.edit {
            for (list in listOf(BROWSERS, PHONES)) {
                val keys = prefs.getString(list, "").orEmpty().split(",").filter { it.isNotBlank() }
                if (b64 in keys) {
                    removed = true
                    putString(list, keys.filter { it != b64 }.joinToString(","))
                }
            }
            putString(KEY_OWNERS, owners.toString())
        }
        return removed
    }

    /** This phone's own browser keys, as shared with paired phones. */
    fun browserKeysB64(): List<String> = prefs.getString(BROWSERS, "").orEmpty().split(",").filter { it.isNotBlank() }

    /**
     * The browser keys a paired phone shared, so its browsers reach this phone too. Each phone's
     * list replaces what it sent before; an empty list removes them.
     */
    fun setRelayed(peerId: String, keys: List<String>) {
        val all = relayed()
        val clean = keys.filter { runCatching { LanCrypto.fromBase64Url(it).size == 32 }.getOrDefault(false) }.distinct().take(MAX_KEYS)
        if (clean.isEmpty()) all.remove(peerId) else all.put(peerId, org.json.JSONArray(clean))
        prefs.edit { putString(RELAYED, all.toString()) }
    }

    private fun relayed(): JSONObject = runCatching { JSONObject(prefs.getString(RELAYED, "{}")!!) }.getOrDefault(JSONObject())

    private fun relayedKeys(): List<ByteArray> {
        val all = relayed()
        val own = browserKeysB64().toSet()
        return all.keys().asSequence().flatMap { id ->
            val a = all.optJSONArray(id) ?: return@flatMap emptySequence()
            (0 until a.length()).asSequence().map { a.optString(it) }
        }.filter { it.isNotEmpty() && it !in own }.distinct().map(LanCrypto::fromBase64Url).toList()
    }

    private fun owners(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_OWNERS, "{}")!!) }.getOrDefault(JSONObject())

    private fun keys(list: String): List<ByteArray> =
        prefs.getString(list, "").orEmpty().split(",").filter { it.isNotBlank() }.map(LanCrypto::fromBase64Url)

    companion object {
        private const val MAX_KEYS = 8
        private const val BROWSERS = "browser_keys"
        private const val PHONES = "phone_keys"
        private const val KEY_OWNERS = "key_owners"
        private const val RELAYED = "relayed_browser_keys"
    }
}

class Pairing(
    val code: String,
    val key: ByteArray,
    val browserKeys: List<ByteArray>,
    val phoneKeys: List<ByteArray>,
    /** Browsers paired with another phone of this user, which reach this one too. */
    val relayedKeys: List<ByteArray> = emptyList(),
) {
    val allKeys: List<ByteArray> get() = browserKeys + relayedKeys + phoneKeys + key
    val browserCount: Int get() = browserKeys.size
    val relayedCount: Int get() = relayedKeys.size

    /** Changes whenever this phone's own browsers change, so paired phones fetch them again. */
    val browsersHash: String get() = browserKeys.map { LanCrypto.toBase64Url(it) }.sorted().joinToString(",").hashCode().toUInt().toString(36)
}
