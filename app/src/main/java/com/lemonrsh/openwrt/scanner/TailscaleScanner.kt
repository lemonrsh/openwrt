package com.lemonrsh.openwrt.scanner

import android.content.Context
import android.util.Log
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanProgress
import com.lemonrsh.openwrt.model.ScanSource
import com.lemonrsh.openwrt.util.RootHelper
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TailscaleScanner(private val context: Context) {
    private val isCancelled = AtomicBoolean(false)
    private var executor = Executors.newFixedThreadPool(16)

    data class TailscaleNode(
        val ip: String,
        val hostname: String,
        val isOnline: Boolean = true
    )

    fun cancel() {
        isCancelled.set(true)
        executor.shutdownNow()
    }

    fun scan(
        onProgress: (ScanProgress) -> Unit,
        onDeviceFound: (RouterDevice) -> Unit,
        onComplete: (List<RouterDevice>, String?) -> Unit
    ) {
        isCancelled.set(false)
        executor = Executors.newFixedThreadPool(16)

        Thread {
            val foundDevices = ConcurrentHashMap<String, RouterDevice>()
            var errorMessage: String? = null

            onProgress(
                ScanProgress(
                    currentIp = "Tailscale",
                    scannedCount = 0,
                    totalCount = 0,
                    statusMessage = "Requesting root to inspect Tailscale daemon…",
                    foundCount = 0
                )
            )

            val peerList = mutableListOf<TailscaleNode>()

            // 1. Check if Root is available and query Tailscale
            val isRoot = RootHelper.isRootGranted()
            if (isRoot) {
                val rawData = RootHelper.queryTailscaleViaRoot()
                if (!rawData.isNullOrBlank()) {
                    if (rawData.startsWith("{")) {
                        // Parse JSON status
                        peerList.addAll(parseTailscaleJson(rawData))
                    } else if (rawData.startsWith("ROUTES:")) {
                        // Parse IPs from route output
                        peerList.addAll(parseTailscaleRoutes(rawData.removePrefix("ROUTES:")))
                    }
                } else {
                    errorMessage = "Root granted, but Tailscale socket not found. Is Tailscale running?"
                }
            } else {
                val suExists = RootHelper.isSuBinaryPresent()
                if (suExists) {
                    errorMessage = "Root permission was not granted for Tailscale inspection."
                } else {
                    errorMessage = "No root binary detected (Magisk / KernelSU required for Tailscale socket inspection)."
                }
            }

            // 2. Fallback: check non-root network interfaces for tailscale0/tun0
            val ifaceIps = findTailscaleInterfaceIps()
            for (ip in ifaceIps) {
                if (peerList.none { it.ip == ip }) {
                    peerList.add(TailscaleNode(ip = ip, hostname = "tailscale-node"))
                }
            }

            if (peerList.isEmpty()) {
                onProgress(
                    ScanProgress(
                        currentIp = "Tailscale",
                        scannedCount = 0,
                        totalCount = 0,
                        statusMessage = errorMessage ?: "No Tailscale peers detected.",
                        foundCount = 0
                    )
                )
                onComplete(emptyList(), errorMessage ?: "No Tailscale peers detected.")
                return@Thread
            }

            val total = peerList.size
            val scannedCount = AtomicInteger(0)

            onProgress(
                ScanProgress(
                    currentIp = "Tailscale",
                    scannedCount = 0,
                    totalCount = total,
                    statusMessage = "Found $total Tailscale nodes. Probing OpenWrt SSH…",
                    foundCount = 0
                )
            )

            // 3. Concurrently probe each Tailscale peer for SSH / OpenWrt
            for (node in peerList) {
                if (isCancelled.get()) break

                executor.execute {
                    if (isCancelled.get()) return@execute

                    try {
                        val device = OpenWrtProbe.probeDevice(
                            ip = node.ip,
                            source = ScanSource.TAILSCALE,
                            knownHostname = node.hostname
                        )

                        if (device != null) {
                            foundDevices[node.ip] = device
                            onDeviceFound(device)
                        }
                    } catch (_: Exception) {}

                    val count = scannedCount.incrementAndGet()
                    onProgress(
                        ScanProgress(
                            currentIp = node.ip,
                            scannedCount = count,
                            totalCount = total,
                            statusMessage = "Probing Tailscale node ${node.hostname} (${node.ip})…",
                            foundCount = foundDevices.size
                        )
                    )
                }
            }

            executor.shutdown()
            try {
                executor.awaitTermination(20, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}

            onComplete(foundDevices.values.toList(), errorMessage)
        }.start()
    }

    private fun parseTailscaleJson(jsonStr: String): List<TailscaleNode> {
        val list = mutableListOf<TailscaleNode>()
        try {
            val root = JSONObject(jsonStr)

            // Check Peer object
            if (root.has("Peer")) {
                val peerObj = root.getJSONObject("Peer")
                val keys = peerObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val p = peerObj.getJSONObject(key)
                    val hostname = p.optString("HostName", "tailscale-peer")
                    val isOnline = p.optBoolean("Online", true)
                    val ipsArray = p.optJSONArray("TailscaleIPs")

                    if (ipsArray != null) {
                        for (i in 0 until ipsArray.length()) {
                            val ip = ipsArray.getString(i)
                            // Focus on IPv4 (100.x.y.z)
                            if (ip.startsWith("100.") && !ip.contains(":")) {
                                list.add(TailscaleNode(ip = ip, hostname = hostname, isOnline = isOnline))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TailscaleScanner", "Failed to parse Tailscale JSON status", e)
        }
        return list
    }

    private fun parseTailscaleRoutes(routeOutput: String): List<TailscaleNode> {
        val list = mutableListOf<TailscaleNode>()
        val ipRegex = Regex("100\\.(?:[0-9]{1,3})\\.(?:[0-9]{1,3})\\.(?:[0-9]{1,3})")
        for (match in ipRegex.findAll(routeOutput)) {
            val ip = match.value
            if (list.none { it.ip == ip }) {
                list.add(TailscaleNode(ip = ip, hostname = "tailscale-$ip"))
            }
        }
        return list
    }

    private fun findTailscaleInterfaceIps(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (name.contains("tailscale") || name.contains("tun")) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("100.") && !host.contains(":")) {
                            list.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
