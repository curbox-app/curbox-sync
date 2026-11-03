package neth.iecal.curboxsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** A WiFi network this phone has synced on. [id] stays the same each time the phone rejoins it. */
data class SavedNetwork(
    val id: String,
    val label: String,
    val address: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val syncs: Int,
)

/** The network the phone is on right now, as far as the browser needs to know. */
data class CurrentNetwork(val id: String, val defaultLabel: String, val address: String)

/**
 * Remembers each network a browser reached this phone on, so the browser can find the phone again
 * after moving between home, work or a hotspot, and the user can see and name those places.
 */
class Networks(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "networks.json")

    @Synchronized
    fun all(): List<SavedNetwork> = load().sortedByDescending { it.lastSeen }

    /** Records a sync on [net]. Writes at most once a minute per network. */
    @Synchronized
    fun touch(net: CurrentNetwork, now: Long = System.currentTimeMillis()): List<SavedNetwork> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == net.id }
        if (i >= 0) {
            val old = list[i]
            if (now - old.lastSeen < 60_000L && old.address == net.address) return list
            list[i] = old.copy(address = net.address, lastSeen = now, syncs = old.syncs + 1)
        } else {
            list += SavedNetwork(net.id, net.defaultLabel, net.address, now, now, 1)
            list.sortByDescending { it.lastSeen }
            while (list.size > MAX) list.removeAt(list.lastIndex)
        }
        save(list)
        return list
    }

    @Synchronized
    fun rename(id: String, label: String): List<SavedNetwork> {
        val clean = label.trim().take(40)
        val list = load().map { if (it.id == id && clean.isNotEmpty()) it.copy(label = clean) else it }
        save(list)
        return list
    }

    @Synchronized
    fun forget(id: String): List<SavedNetwork> {
        val list = load().filter { it.id != id }
        save(list)
        return list
    }

    fun labelFor(net: CurrentNetwork): String = all().firstOrNull { it.id == net.id }?.label ?: net.defaultLabel

    private fun load(): List<SavedNetwork> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedNetwork(
                o.getString("id"), o.optString("label"), o.optString("address"),
                o.optLong("firstSeen"), o.optLong("lastSeen"), o.optInt("syncs"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<SavedNetwork>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("label", it.label).put("address", it.address)
                    .put("firstSeen", it.firstSeen).put("lastSeen", it.lastSeen).put("syncs", it.syncs)
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val MAX = 12

        /**
         * Names the current network by its range and router, which needs no location permission.
         * The network name is used as the label when Android shares it.
         */
        fun current(context: Context): CurrentNetwork? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val onLan = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            if (onLan) {
                val props = cm.getLinkProperties(active)
                val link = props?.linkAddresses?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
                if (link != null) {
                    val ip = link.address.hostAddress.orEmpty()
                    val subnet = subnetOf(ip, link.prefixLength)
                    val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                        ?.gateway?.hostAddress.orEmpty()
                    val ssid = wifiName(context)
                    return CurrentNetwork(
                        id = "$subnet/${link.prefixLength}@$gateway",
                        defaultLabel = ssid ?: "WiFi ${subnet.substringBeforeLast('.')}",
                        address = "$ip:${LanServer.PORT}",
                    )
                }
            }
            // The phone may be the hotspot itself, which Android does not report as the active network.
            val hotspot = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && (it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.startsWith("wlan")) }
                    .flatMap { it.interfaceAddresses }
                    .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
            }.getOrNull() ?: return null
            val ip = hotspot.address.hostAddress.orEmpty()
            val subnet = subnetOf(ip, hotspot.networkPrefixLength.toInt())
            return CurrentNetwork("hotspot:$subnet/${hotspot.networkPrefixLength}", "This phone's hotspot", "$ip:${LanServer.PORT}")
        }

        @Suppress("DEPRECATION")
        private fun wifiName(context: Context): String? {
            val raw = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
            val name = raw?.trim('"')
            return name?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && raw.startsWith('"') }
        }

        fun subnetOf(ip: String, prefix: Int): String {
            val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return ip
            val value = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
            val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
            val net = value and mask
            return (3 downTo 0).joinToString(".") { ((net shr (it * 8)) and 0xFF).toString() }
        }
    }
}
