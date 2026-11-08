package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File

/** Startup-only passive reads. Never scans, switches Wi-Fi, or changes the hotspot. */
internal object WirelessEnvironmentDiagnostics {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun snapshot(context: Context, apInterface: String?): String = buildList {
        add("environment manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE} " +
            "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} build=${Build.DISPLAY} apInterface=$apInterface")
        add(observe("station") {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@observe "unavailable reason=no_wifi_service"
            val info = wifi.connectionInfo
            "wifiEnabled=${wifi.isWifiEnabled} networkId=${info?.networkId} " +
                "supplicant=${info?.supplicantState} ssid=${info?.ssid} bssid=${info?.bssid} " +
                "frequencyMHz=${info?.frequency} rssiDbm=${info?.rssi} linkSpeedMbps=${info?.linkSpeed} " +
                "source=WifiInfo_station_only not_hotspot_frequency=true"
        })
        add(observe("networks") {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@observe "unavailable reason=no_connectivity_service"
            val networks = manager.allNetworks
            "count=${networks.size} default=${manager.activeNetwork} sampledLimit=8"
        })
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.allNetworks?.take(8).orEmpty() }.getOrDefault(emptyList()).forEach { network ->
            add(observe("network=$network") {
                val link = manager?.getLinkProperties(network)
                val caps = manager?.getNetworkCapabilities(network)
                "default=${manager?.activeNetwork == network} iface=${link?.interfaceName} " +
                    "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                    "cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                    "vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} " +
                    "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "addresses=${link?.linkAddresses} routes=${link?.routes} dns=${link?.dnsServers} " +
                    "source=android_local_networks not_iphone_internet=true"
            })
        }
        add("evidenceScope=app_uid passive=true packetCapture=not_collected " +
            "tcpSynHistory=not_available dhcpExchange=not_collected " +
            "missingEvidenceIsNotAbsence=true neighbourCacheIsNotAssociationProof=true")
        listOf(
            "/proc/net/arp", "/proc/net/route", "/proc/net/ipv6_route",
            "/proc/net/tcp", "/proc/net/tcp6",
            "/data/misc/dhcp/dnsmasq.leases", "/data/misc/wifi/dnsmasq.leases",
            "/data/vendor/dhcp/dnsmasq.leases",
        ).forEach { path -> add(fileEvidence(path)) }
    }.joinToString("\n")

    /** Bounded local reads only; missing lease paths do not mean DHCP is disabled. */
    internal fun fileEvidence(path: String, limit: Int = 16 * 1024): String = observe("file=$path") {
        require(limit > 0)
        val text = File(path).reader().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(minOf(limit + 1, 4096))
            while (result.length <= limit) {
                val count = reader.read(buffer, 0, minOf(buffer.size, limit + 1 - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        "source=local_file_snapshot truncated=${text.length > limit} limitChars=$limit " +
            "entries=${text.take(limit).lineSequence().joinToString(";")}"
    }

    internal fun observe(label: String, read: () -> String): String = try {
        "$label ${read()}"
    } catch (error: Exception) {
        "$label unavailable failureClass=${error.javaClass.simpleName} message=${error.message}"
    }
}
