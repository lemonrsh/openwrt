package com.lemonrsh.openwrt.util

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootHelper {
    private const val TAG = "RootHelper"

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean
    )

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk"
    )

    fun isSuBinaryPresent(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        val pathEnv = System.getenv("PATH") ?: return false
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) return true
        }
        return false
    }

    fun isRootGranted(): Boolean {
        val res = runSuCommand("id")
        return res.success && res.stdout.contains("uid=0")
    }

    fun runSuCommand(command: String, timeoutSeconds: Long = 5): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val outThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (_: Exception) {}
            }

            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (_: Exception) {}
            }

            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, stdoutBuilder.toString(), "Command timed out", false)
            }

            outThread.join(500)
            errThread.join(500)

            val exitCode = process.exitValue()
            CommandResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim(),
                success = (exitCode == 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Root execution failed for: $command", e)
            CommandResult(-1, "", e.message ?: "Failed to execute su", false)
        }
    }

    /**
     * Executes root commands to discover Tailscale interface, status, and peer IPs.
     */
    fun queryTailscaleViaRoot(): String? {
        // Strategy 1: Check if tailscale CLI is available
        val cliCheck = runSuCommand("which tailscale 2>/dev/null || find /data -name tailscale -type f 2>/dev/null | head -n 1")
        val tailscaleBin = cliCheck.stdout.lineSequence().firstOrNull { it.isNotBlank() }

        if (!tailscaleBin.isNullOrBlank()) {
            val statusRes = runSuCommand("$tailscaleBin status --json")
            if (statusRes.success && statusRes.stdout.isNotBlank()) {
                return statusRes.stdout
            }
        }

        // Strategy 2: Query tailscale localapi socket directly in Tailscale app data
        val sockPaths = arrayOf(
            "/data/data/com.tailscale.ipn/files/tailscaled.sock",
            "/data/user/0/com.tailscale.ipn/files/tailscaled.sock"
        )

        for (sock in sockPaths) {
            val checkSock = runSuCommand("[ -S '$sock' ] && echo 'exists'")
            if (checkSock.stdout.contains("exists")) {
                // Try curl with unix-socket
                val curlRes = runSuCommand("curl --max-time 3 --silent --unix-socket '$sock' 'http://local-tailscaled.sock/localapi/v0/status'")
                if (curlRes.success && curlRes.stdout.contains("Self")) {
                    return curlRes.stdout
                }

                // If curl is unavailable, try python or busybox socat / nc
                val ncRes = runSuCommand("printf 'GET /localapi/v0/status HTTP/1.0\\r\\nHost: local-tailscaled.sock\\r\\n\\r\\n' | nc -U '$sock' 2>/dev/null")
                if (ncRes.success && ncRes.stdout.contains("Self")) {
                    val bodyIndex = ncRes.stdout.indexOf("\r\n\r\n")
                    return if (bodyIndex != -1) ncRes.stdout.substring(bodyIndex + 4) else ncRes.stdout
                }
            }
        }

        // Strategy 3: Check routes and IP interface for tailscale (CGNAT range 100.64.0.0/10)
        val routeRes = runSuCommand("ip -4 route show table 52 2>/dev/null || ip -4 route show | grep 100.")
        if (routeRes.success && routeRes.stdout.isNotBlank()) {
            return "ROUTES:" + routeRes.stdout
        }

        return null
    }
}
