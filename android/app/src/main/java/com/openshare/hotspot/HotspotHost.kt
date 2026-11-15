package com.openshare.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.openshare.core.HotspotQrPayload
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Hotspot host helper (Android Local Only Hotspot).
 * Provides system-assigned SSID/passphrase; attempts to resolve local IPv4 for QR payload.
 */
class HotspotHost(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(port: Int, onReady: (HotspotQrPayload) -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { onFailed("Hotspot requires Android 8.0 or newer."); return }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    try {
                        val cfg = r.wifiConfiguration
                        val hostIp = resolveHostIp() ?: "192.168.49.1"
                        val payload = HotspotQrPayload(
                            mode = "hotspot", v = 1,
                            ssid = cfg?.SSID ?: "",
                            passphrase = cfg?.preSharedKey,
                            host = hostIp,
                            port = port,
                            token = UUID.randomUUID().toString(),
                            name = Build.MODEL
                        )
                        Handler(Looper.getMainLooper()).post { onReady(payload) }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post { onFailed(e.message ?: "Failed to prepare hotspot payload.") }
                    }
                }
                override fun onFailed(reason: Int) {
                    val msg = when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> "Hotspot unavailable (incompatible mode, code 3). Turn Wi‑Fi ON (it can stay disconnected), disable airplane mode/VPN, close other hotspot or Wi‑Fi Direct use, then retry."
                        ERROR_TETHERING_DISALLOWED -> "Hotspot not allowed on this device/network (tethering disallowed)."
                        ERROR_GENERIC -> "Hotspot failed to start. Check Wi‑Fi, Location services, and app permissions, then retry."
                        ERROR_NO_CHANNEL -> "No Wi‑Fi channel available for hotspot. Turn Wi‑Fi ON and retry."
                        else -> "Hotspot start failed (code $reason). Ensure Wi‑Fi and Location services are ON and permissions are granted."
                    }
                    Handler(Looper.getMainLooper()).post { onFailed(msg) }
                }
                override fun onStopped() { reservation = null }
            }, Handler(Looper.getMainLooper()))
        } catch (se: SecurityException) {
            onFailed("Missing permission to start hotspot. Allow Nearby devices/Wi‑Fi/Location permissions and try again.")
        } catch (e: Exception) {
            onFailed(e.message ?: "Could not start hotspot.")
        }
    }

    private fun resolveHostIp(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val addrs = ifaces.toList()
                .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to it } }
                .filter { (_, a) -> !a.isLoopbackAddress && a is Inet4Address }
                .mapNotNull { (name, a) -> a.hostAddress?.let { name to it } }
                .filter { (_, ip) -> ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.") }
            // Local Only Hotspot commonly uses 192.168.49.1; prefer hotspot/ap-like interfaces and that range.
            addrs.firstOrNull { (n, ip) -> (n.contains("ap", true) || n.contains("wlan", true) || n.contains("swlan", true)) && ip.startsWith("192.168.49.") }?.second
                ?: addrs.firstOrNull { (_, ip) -> ip.startsWith("192.168.49.") }?.second
                ?: addrs.firstOrNull { (n, ip) -> (n.contains("ap", true) || n.contains("wlan", true) || n.contains("swlan", true)) && ip.startsWith("192.168.43.") }?.second
                ?: addrs.firstOrNull { (_, ip) -> ip.startsWith("192.168.43.") }?.second
                ?: addrs.firstOrNull { (n, _) -> n.contains("ap", true) || n.contains("wlan", true) || n.contains("swlan", true) }?.second
                ?: addrs.firstOrNull()?.second
        } catch (_: Exception) { null }
    }

    fun stop() { reservation?.close(); reservation = null }
}
