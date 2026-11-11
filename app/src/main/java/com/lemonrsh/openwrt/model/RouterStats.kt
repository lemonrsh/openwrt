package com.lemonrsh.openwrt.model

import java.util.Locale

data class RouterStats(
    val routerName: String = "OpenWrt",
    val modelName: String = "Generic OpenWrt Device",
    val firmwareVersion: String = "OpenWrt 23.05.3",
    val ipAddress: String = "192.168.1.1",
    val uptimeSeconds: Long = 124890L,
    val temperatureC: Double? = 47.5,
    val ramTotalBytes: Long = 536870912L,     // 512 MB
    val ramUsedBytes: Long = 209715200L,      // ~200 MB
    val ramFreeBytes: Long = 327155712L,      // ~312 MB
    val storageTotalBytes: Long = 134217728L, // 128 MB flash/overlay
    val storageUsedBytes: Long = 49283072L,   // ~47 MB
    val storageFreeBytes: Long = 84934656L,   // ~81 MB
    val cpuLoad: String = "0.12, 0.08, 0.05",
    val source: ScanSource = ScanSource.LAN
) {
    val formattedUptime: String
        get() {
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            val minutes = (uptimeSeconds % 3600) / 60
            val seconds = uptimeSeconds % 60
            return when {
                days > 0 -> "${days}d ${hours}h ${minutes}m"
                hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
                else -> "${minutes}m ${seconds}s"
            }
        }

    val formattedTemperature: String
        get() = if (temperatureC != null) {
            String.format(Locale.US, "%.1f°C", temperatureC)
        } else {
            "N/A"
        }

    val ramUsedMb: Long
        get() = ramUsedBytes / (1024 * 1024)

    val ramTotalMb: Long
        get() = ramTotalBytes / (1024 * 1024)

    val ramPercent: Int
        get() = if (ramTotalBytes > 0) ((ramUsedBytes * 100) / ramTotalBytes).toInt().coerceIn(0, 100) else 0

    val storageUsedMb: Long
        get() = storageUsedBytes / (1024 * 1024)

    val storageTotalMb: Long
        get() = storageTotalBytes / (1024 * 1024)

    val storagePercent: Int
        get() = if (storageTotalBytes > 0) ((storageUsedBytes * 100) / storageTotalBytes).toInt().coerceIn(0, 100) else 0
}
