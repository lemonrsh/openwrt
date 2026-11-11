package com.lemonrsh.openwrt.model

enum class ScanSource {
    LAN,
    TAILSCALE,
    MANUAL
}

enum class ScanState {
    IDLE,
    SCANNING_LAN,
    SCANNING_TAILSCALE,
    SCANNING_ALL,
    COMPLETED,
    ERROR
}

data class RouterDevice(
    val ip: String,
    val port: Int = 22,
    val hostname: String,
    val source: ScanSource,
    val sshBanner: String? = null,
    val isDropbear: Boolean = false,
    val isOpenWrt: Boolean = false,
    val hasLuCi: Boolean = false,
    val latencyMs: Long = 0L,
    val macAddress: String? = null,
    val tailscaleNodeName: String? = null,
    val discoveredAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = when {
            tailscaleNodeName != null -> "$tailscaleNodeName ($ip)"
            hostname.isNotBlank() && hostname != ip -> "$hostname ($ip)"
            isOpenWrt -> "OpenWrt Router ($ip)"
            else -> "Router ($ip)"
        }

    val serverSummary: String
        get() = when {
            isDropbear -> "Dropbear SSH (Default OpenWrt)"
            sshBanner != null -> sshBanner
            else -> "SSH Port $port"
        }
}

data class ScanProgress(
    val currentIp: String = "",
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val statusMessage: String = "",
    val foundCount: Int = 0
)
