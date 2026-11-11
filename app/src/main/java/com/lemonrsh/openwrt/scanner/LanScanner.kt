package com.lemonrsh.openwrt.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanProgress
import com.lemonrsh.openwrt.model.ScanSource
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LanScanner(private val context: Context) {
    private val isCancelled = AtomicBoolean(false)
    private var executor = Executors.newFixedThreadPool(24)

    fun cancel() {
        isCancelled.set(true)
        executor.shutdownNow()
    }

    fun scan(
        onProgress: (ScanProgress) -> Unit,
        onDeviceFound: (RouterDevice) -> Unit,
        onComplete: (List<RouterDevice>) -> Unit
    ) {
        isCancelled.set(false)
        executor = Executors.newFixedThreadPool(24)

        Thread {
            val foundDevices = ConcurrentHashMap<String, RouterDevice>()
            val candidates = LinkedHashSet<String>()
            val arpMap = readArpTable()

            // 1. Identify Default Gateway & local IP
            val localIp = getLocalWifiIp()
            val gatewayIp = getGatewayIp()

            if (!gatewayIp.isNullOrBlank()) {
                candidates.add(gatewayIp)
            }

            // Common default OpenWrt router IPs
            val commonRouterIps = listOf(
                "192.168.1.1",
                "192.168.0.1",
                "192.168.8.1",
                "192.168.2.1",
                "192.168.31.1",
                "10.0.0.1"
            )
            candidates.addAll(commonRouterIps)

            // Add ARP neighbors
            candidates.addAll(arpMap.keys)

            // Add full /24 subnet around local IP if available
            if (localIp != null) {
                val subnetPrefix = localIp.substringBeforeLast(".")
                for (i in 1..254) {
                    candidates.add("$subnetPrefix.$i")
                }
            }

            val targetList = candidates.toList()
            val total = targetList.size
            val scannedCount = AtomicInteger(0)

            onProgress(
                ScanProgress(
                    currentIp = "Starting LAN scan",
                    scannedCount = 0,
                    totalCount = total,
                    statusMessage = "Probing default gateway and local subnet…",
                    foundCount = 0
                )
            )

            // Fast pass on gateway and top candidates first
            for (ip in targetList) {
                if (isCancelled.get()) break

                executor.execute {
                    if (isCancelled.get()) return@execute

                    try {
                        val device = OpenWrtProbe.probeDevice(
                            ip = ip,
                            source = ScanSource.LAN,
                            macAddress = arpMap[ip]
                        )

                        if (device != null) {
                            foundDevices[ip] = device
                            onDeviceFound(device)
                        }
                    } catch (_: Exception) {}

                    val count = scannedCount.incrementAndGet()
                    onProgress(
                        ScanProgress(
                            currentIp = ip,
                            scannedCount = count,
                            totalCount = total,
                            statusMessage = "Scanning $ip…",
                            foundCount = foundDevices.size
                        )
                    )
                }
            }

            executor.shutdown()
            try {
                executor.awaitTermination(25, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}

            onComplete(foundDevices.values.toList())
        }.start()
    }

    private fun getLocalWifiIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.") && !host.startsWith("100.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getGatewayIp(): String? {
        // Read /proc/net/route
        try {
            val file = File("/proc/net/route")
            if (file.exists() && file.canRead()) {
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val tokens = line!!.split("\\s+".toRegex())
                        if (tokens.size >= 3 && tokens[1] == "00000000") {
                            val hexGw = tokens[2]
                            val gw = hexGw.toLong(16).toInt()
                            return String.format(
                                "%d.%d.%d.%d",
                                gw and 0xFF,
                                (gw shr 8) and 0xFF,
                                (gw shr 16) and 0xFF,
                                (gw shr 24) and 0xFF
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to WifiManager
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val gw = dhcpInfo.gateway
                return String.format(
                    "%d.%d.%d.%d",
                    gw and 0xFF,
                    (gw shr 8) and 0xFF,
                    (gw shr 16) and 0xFF,
                    (gw shr 24) and 0xFF
                )
            }
        } catch (_: Exception) {}

        return "192.168.1.1"
    }

    private fun readArpTable(): Map<String, String> {
        val arpMap = HashMap<String, String>()
        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists() && arpFile.canRead()) {
                BufferedReader(FileReader(arpFile)).use { reader ->
                    // Skip header line
                    reader.readLine()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val parts = line!!.split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3]
                            if (mac != "00:00:00:00:00:00" && ip.isNotBlank()) {
                                arpMap[ip] = mac
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return arpMap
    }
}
