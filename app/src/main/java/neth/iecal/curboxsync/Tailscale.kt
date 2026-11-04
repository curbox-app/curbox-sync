package neth.iecal.curboxsync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Tailscale puts every signed in device on one private network, wherever it is. When this phone has
 * a Tailscale address, the browser keeps reaching it after either side leaves the shared WiFi.
 */
object Tailscale {
    const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val CONNECT = "com.tailscale.ipn.CONNECT_VPN"

    /** 100.64.0.0/10, the range Tailscale hands out. */
    fun isTailnet(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address
        return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
    }

    /** This phone's Tailscale address with the sync port, or null while Tailscale is off. */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            // Carriers use 100.64/10 too (VoWiFi on ccmni/xfrm); Tailscale's is always a tun.
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull(::isTailnet)
            ?.let { "${it.hostAddress}:${LanServer.PORT}" }
    }.getOrNull()

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Asks Tailscale to connect without opening it. Works once it has been set up there. */
    fun connect(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent(CONNECT).setClassName(PACKAGE, RECEIVER)) }
    }

    fun open(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The app store page, or the download page when there is no store. */
    fun get(ctx: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(store)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
