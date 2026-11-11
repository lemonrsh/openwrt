package com.lemonrsh.openwrt.util

import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Utility helper to automatically accept SSL/TLS and SSH host certificates
 * for OpenWrt router interfaces (self-signed LuCI HTTPS certificates and Dropbear SSH host keys).
 */
object CertificateHelper {
    private const val TAG = "CertificateHelper"

    /**
     * Installs a trust-all SSL context and HostnameVerifier on HttpsURLConnection
     * so that self-signed OpenWrt LuCI certificates (uhttpd-cert) are automatically
     * accepted without throwing SSLHandshakeException or CertificateException.
     */
    fun installTrustAllCertificates() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            })

            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(HostnameVerifier { _, _ -> true })
            Log.d(TAG, "Trust-all SSL certificate manager installed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install trust-all SSL context: ${e.message}")
        }
    }

    /**
     * Builds an SSH connection command string that automatically accepts host key
     * certificates and prevents interactive "authenticity of host can't be established"
     * verification prompts.
     */
    fun buildSshCommand(
        user: String,
        ip: String,
        port: Int = 22,
        autoAcceptCert: Boolean = true
    ): String {
        val safeUser = user.trim().ifBlank { "root" }
        val certFlags = if (autoAcceptCert) "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null " else ""
        val portFlag = if (port != 22) "-p $port " else ""
        return "ssh $certFlags$portFlag$safeUser@$ip"
    }
}
