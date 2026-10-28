package io.github.kennethcheong.qrdrop

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** An address this phone can be reached on, and what kind of network it is. */
data class LocalAddress(val iface: String, val ip: String, val kind: Kind) {
    /** DIRECT is a Wi-Fi Direct group: QR Drop's own fast hotspot, or another app's (casting, say). */
    enum class Kind { WIFI, HOTSPOT, DIRECT }
}

object LocalAddresses {
    /** Interface names: Wi-Fi client is wlan0; hotspots show up as ap0, swlan0, wlan1…; Wi-Fi Direct groups as p2p0 or p2p-wlan0-0. */
    fun kindOf(iface: String): LocalAddress.Kind? = when {
        iface == "wlan0" -> LocalAddress.Kind.WIFI
        Regex("^(ap|swlan|softap)\\d*$").matches(iface) || Regex("^wlan[1-9]\\d*$").matches(iface) -> LocalAddress.Kind.HOTSPOT
        iface.startsWith("p2p") -> LocalAddress.Kind.DIRECT
        else -> null // mobile data (rmnet, ccmni), VPNs (tun): an iPhone can't reach these
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

/** The Wi-Fi network an iPhone joins when there's no shared Wi-Fi. [frequencyMhz] is null when Android doesn't say. */
data class HotspotInfo(val ssid: String, val passphrase: String, val frequencyMhz: Int? = null, val viaDirect: Boolean = false) {
    val fast: Boolean get() = (frequencyMhz ?: 0) >= 4900

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

/**
 * A Wi-Fi Direct group that the iPhone joins like any other Wi-Fi network. Android
 * puts the local-only hotspot on 2.4 GHz at 20 MHz, but an app may ask for 5 GHz
 * here, which is several times faster. Needs Wi-Fi switched on, though not joined
 * to anything; when it can't start, [DropViewModel] falls back to [PrivateHotspot].
 */
class DirectHotspot(context: Context) {
    private val app = context.applicationContext
    private val manager: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null

    fun start(whenStarted: (HotspotInfo) -> Unit, whenFailed: (String) -> Unit) {
        val p2p = manager
        val ch = p2p?.initialize(app, Looper.getMainLooper(), null)
        if (p2p == null || ch == null) return whenFailed("This phone has no Wi-Fi Direct.")
        channel = ch
        val config = WifiP2pConfig.Builder()
            .setNetworkName(newNetworkName())
            .setPassphrase(newPassphrase())
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .build()
        // A group left over from an earlier run blocks a new one; failing here just means there was none.
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(p2p, ch, config, whenStarted, whenFailed)
            override fun onFailure(reason: Int) = create(p2p, ch, config, whenStarted, whenFailed)
        })
    }

    @SuppressLint("MissingPermission") // MainActivity asks for NEARBY_WIFI_DEVICES first
    private fun create(p2p: WifiP2pManager, ch: WifiP2pManager.Channel, config: WifiP2pConfig, whenStarted: (HotspotInfo) -> Unit, whenFailed: (String) -> Unit) {
        if (channel !== ch) return
        p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitGroup(p2p, ch, whenStarted, whenFailed, triesLeft = 40)
            override fun onFailure(reason: Int) = whenFailed(
                when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "This phone has no Wi-Fi Direct."
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Is Wi-Fi on?"
                    else -> "Couldn't start Wi-Fi Direct (error $reason)."
                },
            )
        })
    }

    /** createGroup returns before the network is up; ask until it has a name and password. */
    @SuppressLint("MissingPermission")
    private fun awaitGroup(p2p: WifiP2pManager, ch: WifiP2pManager.Channel, whenStarted: (HotspotInfo) -> Unit, whenFailed: (String) -> Unit, triesLeft: Int) {
        if (channel !== ch) return
        p2p.requestGroupInfo(ch) { group ->
            when {
                channel !== ch -> Unit
                group != null && group.isGroupOwner && !group.passphrase.isNullOrEmpty() ->
                    whenStarted(HotspotInfo(group.networkName, group.passphrase, group.frequency.takeIf { it > 0 }, viaDirect = true))
                triesLeft > 0 -> main.postDelayed({ awaitGroup(p2p, ch, whenStarted, whenFailed, triesLeft - 1) }, 250)
                else -> whenFailed("The Wi-Fi Direct network didn't come up.")
            }
        }
    }

    /**
     * A group outlives the app if it's killed mid-hotspot (an update, say), with a
     * password nobody can see any more. Clear ours, by name, when the app starts.
     */
    @SuppressLint("MissingPermission")
    fun clearLeftover() {
        val p2p = manager ?: return
        val ch = p2p.initialize(app, Looper.getMainLooper(), null) ?: return
        runCatching {
            p2p.requestGroupInfo(ch) { group ->
                if (group?.networkName?.endsWith(NAME_SUFFIX) == true && channel == null) {
                    p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = ch.close()
                        override fun onFailure(reason: Int) = ch.close()
                    })
                } else {
                    ch.close()
                }
            }
        }.onFailure { ch.close() } // no NEARBY_WIFI_DEVICES yet, so we can't have made one
    }

    fun stop() {
        val ch = channel ?: return
        channel = null
        manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = ch.close()
            override fun onFailure(reason: Int) = ch.close()
        })
    }

    companion object {
        private const val ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        /** Android requires Wi-Fi Direct names to start with "DIRECT-" and two letters or digits. */
        fun newNetworkName(): String = "DIRECT-" + pick(ALPHANUMERIC, 2) + NAME_SUFFIX
        private const val NAME_SUFFIX = "-QR Drop"

        /** Lower case and digits only, so it's easy to type if the QR code won't scan. */
        fun newPassphrase(): String = pick("abcdefghjkmnpqrstuvwxyz23456789", 12)

        private fun pick(alphabet: String, n: Int) = String(CharArray(n) { alphabet[random.nextInt(alphabet.length)] })
    }
}
