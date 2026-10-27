package io.github.kennethcheong.qrdrop

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/** An address this phone can be reached on, and what kind of network it is. */
data class LocalAddress(val iface: String, val ip: String, val kind: Kind) {
    enum class Kind { WIFI, HOTSPOT }
}

object LocalAddresses {
    /** Interface names: Wi-Fi client is wlan0; hotspots show up as ap0, swlan0, wlan1… */
    fun kindOf(iface: String): LocalAddress.Kind? = when {
        iface == "wlan0" -> LocalAddress.Kind.WIFI
        Regex("^(ap|swlan|softap)\\d*$").matches(iface) || Regex("^wlan[1-9]\\d*$").matches(iface) -> LocalAddress.Kind.HOTSPOT
        else -> null // mobile data (rmnet, ccmni), VPNs (tun), Wi-Fi Direct (p2p): an iPhone can't reach these
    }

    fun current(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                val kind = kindOf(ni.name) ?: return@flatMap emptyList()
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a ->
                    a.hostAddress?.let { LocalAddress(ni.name, it, kind) }
                }
            }
    }.getOrDefault(emptyList())
}

/** The Wi-Fi network an iPhone joins when there's no shared Wi-Fi. */
data class HotspotInfo(val ssid: String, val passphrase: String) {
    /** Standard Wi-Fi QR payload; the iPhone camera offers to join it. */
    fun qrPayload(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

    private fun escape(s: String) = s.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
}

/**
 * Android's "local-only hotspot": a private Wi-Fi network with a random name and
 * password and no internet, made just for this app. It needs no change to your
 * own hotspot settings and goes away when the app stops it.
 */
class PrivateHotspot(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission") // MainActivity asks for NEARBY_WIFI_DEVICES first
    @Suppress("DEPRECATION") // SoftApConfiguration.ssid: the replacement needs API 33
    fun start(whenStarted: (HotspotInfo) -> Unit, whenFailed: (String) -> Unit) {
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                val config = r.softApConfiguration
                whenStarted(HotspotInfo(config.ssid?.removeSurrounding("\"").orEmpty(), config.passphrase.orEmpty()))
            }

            override fun onFailed(reason: Int) {
                whenFailed(
                    when (reason) {
                        LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Turn off your phone's regular hotspot first, or use it instead."
                        LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspots aren't allowed on this phone."
                        LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No free Wi-Fi channel for a hotspot."
                        else -> "Couldn't start a hotspot (error $reason)."
                    },
                )
            }

            override fun onStopped() {
                reservation = null
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        reservation?.close()
        reservation = null
    }
}
