package com.lemonrsh.openwrt.scanner

import android.util.Log
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object OpenWrtProbe {
    private const val TAG = "OpenWrtProbe"

    data class SshProbeResult(
        val isOpen: Boolean,
        val banner: String? = null,
        val isDropbear: Boolean = false,
        val latencyMs: Long = 0L
    )

    data class HttpProbeResult(
        val hasLuCi: Boolean,
        val title: String? = null
    )

    /**
     * Checks if SSH (port 22) is open and grabs the banner to identify Dropbear / OpenWrt.
     */
    fun probeSsh(ip: String, port: Int = 22, timeoutMs: Int = 850): SshProbeResult {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val latency = System.currentTimeMillis() - start
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val banner = reader.readLine()?.trim()

            val isDropbear = banner?.contains("dropbear", ignoreCase = true) == true

            SshProbeResult(
                isOpen = true,
                banner = banner,
                isDropbear = isDropbear,
                latencyMs = latency
            )
        } catch (e: Exception) {
            SshProbeResult(isOpen = false)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Probes HTTP port 80/443 for OpenWrt LuCI web interface signatures.
     */
    fun probeLuCi(ip: String, timeoutMs: Int = 800): HttpProbeResult {
        val urlsToTry = arrayOf(
            "http://$ip/cgi-bin/luci",
            "http://$ip/",
            "https://$ip/cgi-bin/luci",
            "https://$ip/"
        )

        for (urlString in urlsToTry) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "OpenWrtScanner/1.0")

                val code = conn.responseCode
                if (code in 200..399) {
                    val cookie = conn.getHeaderField("Set-Cookie") ?: ""
                    val hasSysAuth = cookie.contains("sysauth")

                    val sb = StringBuilder()
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    var linesRead = 0
                    var line: String? = null
                    while (linesRead < 40 && reader.readLine().also { line = it } != null) {
                        sb.append(line).append(" ")
                        linesRead++
                    }
                    val body = sb.toString().lowercase()

                    val hasLuciTitle = body.contains("openwrt") || body.contains("luci") || body.contains("luci-static")
                    if (hasSysAuth || hasLuciTitle) {
                        return HttpProbeResult(hasLuCi = true, title = "OpenWrt LuCI Web Interface")
                    }
                }
            } catch (_: Exception) {
                // Ignore probe timeouts
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return HttpProbeResult(hasLuCi = false)
    }

    /**
     * Comprehensive probe combining SSH and LuCI checks to confirm OpenWrt router status.
     */
    fun probeDevice(
        ip: String,
        source: ScanSource,
        knownHostname: String? = null,
        macAddress: String? = null
    ): RouterDevice? {
        val sshResult = probeSsh(ip)
        if (!sshResult.isOpen) {
            // If SSH port is not even open, this device doesn't match our criteria
            return null
        }

        val luciResult = probeLuCi(ip)

        // Resolve hostname if not provided
        val hostname = knownHostname ?: try {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: "router"
        } catch (_: Exception) {
            "router"
        }

        val isOpenWrt = sshResult.isDropbear ||
                luciResult.hasLuCi ||
                hostname.contains("openwrt", ignoreCase = true) ||
                (sshResult.banner?.contains("dropbear", ignoreCase = true) == true)

        return RouterDevice(
            ip = ip,
            port = 22,
            hostname = hostname,
            source = source,
            sshBanner = sshResult.banner,
            isDropbear = sshResult.isDropbear,
            isOpenWrt = isOpenWrt,
            hasLuCi = luciResult.hasLuCi,
            latencyMs = sshResult.latencyMs,
            macAddress = macAddress,
            tailscaleNodeName = if (source == ScanSource.TAILSCALE) knownHostname else null
        )
    }
}
