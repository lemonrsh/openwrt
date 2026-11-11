package com.lemonrsh.openwrt

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lemonrsh.openwrt.model.RouterStats
import com.lemonrsh.openwrt.model.ScanSource
import com.lemonrsh.openwrt.scanner.OpenWrtClient
import com.lemonrsh.openwrt.util.HapticHelper
import java.util.concurrent.Executors

class RouterDashboardActivity : Activity() {

    private val bg = Color.rgb(11, 16, 32)
    private val card = Color.rgb(22, 30, 51)
    private val cardInner = Color.rgb(28, 38, 66)
    private val border = Color.rgb(38, 52, 85)
    private val muted = Color.rgb(151, 163, 190)
    private val white = Color.rgb(241, 245, 255)
    private val blue = Color.rgb(111, 145, 255)
    private val cyan = Color.rgb(0, 229, 255)
    private val green = Color.rgb(82, 214, 163)
    private val amber = Color.rgb(255, 183, 77)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var routerIp: String = "192.168.1.1"
    private var routerHostname: String = "OpenWrt"
    private var sshUser: String = "root"
    private var sshPass: String = ""
    private var sshPort: Int = 22
    private var scanSource: ScanSource = ScanSource.LAN

    // UI Widgets
    private lateinit var routerTitleView: TextView
    private lateinit var routerModelView: TextView
    private lateinit var routerIpBadge: TextView
    private lateinit var uptimeValueView: TextView
    private lateinit var tempValueView: TextView
    private lateinit var tempStatusView: TextView

    private lateinit var ramPercentBadge: TextView
    private lateinit var ramNumbersView: TextView
    private lateinit var ramProgressBar: ProgressBar
    private lateinit var ramFreeView: TextView

    private lateinit var storagePercentBadge: TextView
    private lateinit var storageNumbersView: TextView
    private lateinit var storageProgressBar: ProgressBar
    private lateinit var storageFreeView: TextView

    private lateinit var refreshBtn: Button
    @Volatile private var isPolling = true
    private var currentStats = RouterStats()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            pollStats()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        com.lemonrsh.openwrt.util.CertificateHelper.installTrustAllCertificates()

        routerIp = intent.getStringExtra("EXTRA_IP") ?: "192.168.1.1"
        routerHostname = intent.getStringExtra("EXTRA_HOSTNAME") ?: "OpenWrt Router"
        sshUser = intent.getStringExtra("EXTRA_USER") ?: "root"
        sshPass = intent.getStringExtra("EXTRA_PASS") ?: ""
        sshPort = intent.getIntExtra("EXTRA_PORT", 22)
        val sourceStr = intent.getStringExtra("EXTRA_SOURCE") ?: "LAN"
        scanSource = try { ScanSource.valueOf(sourceStr) } catch (_: Exception) { ScanSource.LAN }

        setupUI()
        pollStats()
        handler.postDelayed(refreshRunnable, 3000)
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            val p = dp(16)
            setPadding(p, p, p, p)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Navigation / Header Bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = TextView(this).apply {
            text = "← DISCONNECT"
            setTextColor(muted)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(10)
            val pY = dp(6)
            setPadding(pX, pY, pX, pY)
            background = rounded(Color.rgb(18, 25, 44), 8, border, 1)
            setOnClickListener { finish() }
        }

        val liveBadge = TextView(this).apply {
            text = "● CONNECTED VIA SSH"
            setTextColor(green)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        navBar.addView(backBtn)
        navBar.addView(liveBadge)
        content.addView(navBar)

        content.addView(space(16))

        // ==========================================
        // 1. TOP SECTION: Router Name, Uptime & Temp
        // ==========================================
        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16, border, 1)
            val p = dp(18)
            setPadding(p, p, p, p)
        }

        // Router Name & Model Header
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        routerTitleView = TextView(this).apply {
            text = routerHostname
            setTextColor(white)
            textSize = 20f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.03f
        }

        routerModelView = TextView(this).apply {
            text = "MediaTek MT7981 · OpenWrt 23.05.3"
            setTextColor(cyan)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        titleCol.addView(routerTitleView)
        titleCol.addView(routerModelView)

        routerIpBadge = TextView(this).apply {
            text = "$routerIp : $sshPort"
            setTextColor(if (scanSource == ScanSource.TAILSCALE) cyan else green)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(8)
            val pY = dp(4)
            setPadding(pX, pY, pX, pY)
            background = rounded(Color.rgb(18, 28, 48), 6, border, 1)
        }

        nameRow.addView(titleCol)
        nameRow.addView(routerIpBadge)
        topCard.addView(nameRow)

        topCard.addView(space(16))
        topCard.addView(divider())
        topCard.addView(space(16))

        // Uptime & Temperature Metric Cards (Row of 2)
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Uptime Sub-card
        val uptimeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardInner, 12, border, 1)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val uptimeLabel = TextView(this).apply {
            text = "UPTIME"
            setTextColor(muted)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
        }

        uptimeValueView = TextView(this).apply {
            text = "14d 6h 23m"
            setTextColor(white)
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        val uptimeStatus = TextView(this).apply {
            text = "● System Stable"
            setTextColor(green)
            textSize = 11f
        }

        uptimeBox.addView(uptimeLabel)
        uptimeBox.addView(space(4))
        uptimeBox.addView(uptimeValueView)
        uptimeBox.addView(space(2))
        uptimeBox.addView(uptimeStatus)

        // Temperature Sub-card
        val tempBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardInner, 12, border, 1)
            val p = dp(14)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(10)
            layoutParams = lp
        }

        val tempLabel = TextView(this).apply {
            text = "CPU TEMP"
            setTextColor(muted)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
        }

        tempValueView = TextView(this).apply {
            text = "48.5°C"
            setTextColor(green)
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        tempStatusView = TextView(this).apply {
            text = "● Optimal Temp"
            setTextColor(muted)
            textSize = 11f
        }

        tempBox.addView(tempLabel)
        tempBox.addView(space(4))
        tempBox.addView(tempValueView)
        tempBox.addView(space(2))
        tempBox.addView(tempStatusView)

        metricsRow.addView(uptimeBox)
        metricsRow.addView(tempBox)
        topCard.addView(metricsRow)

        content.addView(topCard)

        content.addView(space(18))

        // ==========================================
        // 2. MIDDLE SECTION: RAM & Storage Usage
        // ==========================================
        val sectionTitle = TextView(this).apply {
            text = "RESOURCE USAGE"
            setTextColor(muted)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
        }
        content.addView(sectionTitle)

        content.addView(space(8))

        // RAM Usage Card
        val ramCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 14, border, 1)
            val p = dp(16)
            setPadding(p, p, p, p)
        }

        val ramHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ramTitle = TextView(this).apply {
            text = "RAM (MEMORY)"
            setTextColor(white)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        ramPercentBadge = TextView(this).apply {
            text = "38% USED"
            setTextColor(cyan)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(8)
            val pY = dp(4)
            setPadding(pX, pY, pX, pY)
            background = rounded(Color.rgb(18, 38, 54), 6, cyan, 1)
        }

        ramHeader.addView(ramTitle)
        ramHeader.addView(ramPercentBadge)
        ramCard.addView(ramHeader)

        ramCard.addView(space(10))

        ramProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 38
            progressDrawable = createBarDrawable(blue, cyan)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
        }
        ramCard.addView(ramProgressBar)

        ramCard.addView(space(8))

        val ramStatsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        ramNumbersView = TextView(this).apply {
            text = "Used: 195 MB / 512 MB"
            setTextColor(muted)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        ramFreeView = TextView(this).apply {
            text = "Free: 317 MB"
            setTextColor(green)
            textSize = 12f
            gravity = Gravity.END
        }

        ramStatsRow.addView(ramNumbersView)
        ramStatsRow.addView(ramFreeView)
        ramCard.addView(ramStatsRow)

        content.addView(ramCard)

        content.addView(space(12))

        // Storage Usage Card
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 14, border, 1)
            val p = dp(16)
            setPadding(p, p, p, p)
        }

        val storageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val storageTitle = TextView(this).apply {
            text = "FLASH STORAGE (OVERLAY)"
            setTextColor(white)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        storagePercentBadge = TextView(this).apply {
            text = "36% USED"
            setTextColor(green)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(8)
            val pY = dp(4)
            setPadding(pX, pY, pX, pY)
            background = rounded(Color.rgb(16, 42, 34), 6, green, 1)
        }

        storageHeader.addView(storageTitle)
        storageHeader.addView(storagePercentBadge)
        storageCard.addView(storageHeader)

        storageCard.addView(space(10))

        storageProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 36
            progressDrawable = createBarDrawable(Color.rgb(46, 117, 89), green)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
        }
        storageCard.addView(storageProgressBar)

        storageCard.addView(space(8))

        val storageStatsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        storageNumbersView = TextView(this).apply {
            text = "Used: 46.2 MB / 128 MB"
            setTextColor(muted)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        storageFreeView = TextView(this).apply {
            text = "Available: 81.8 MB"
            setTextColor(green)
            textSize = 12f
            gravity = Gravity.END
        }

        storageStatsRow.addView(storageNumbersView)
        storageStatsRow.addView(storageFreeView)
        storageCard.addView(storageStatsRow)

        content.addView(storageCard)

        content.addView(space(22))

        // ==========================================
        // 3. BOTTOM SECTION: Controls & Shortcuts
        // ==========================================
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        refreshBtn = Button(this).apply {
            text = "REFRESH STATS"
            setTextColor(white)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(Color.rgb(28, 42, 74), 10, blue, 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                HapticHelper.vibrateTick(this@RouterDashboardActivity)
                pollStats()
            }
        }

        val luciBtn = Button(this).apply {
            text = "OPEN LUCI WEB"
            setTextColor(Color.rgb(10, 15, 26))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(green, 10)
            val p = dp(12)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(10)
            layoutParams = lp
            setOnClickListener { openLuCi() }
        }

        actionRow.addView(refreshBtn)
        actionRow.addView(luciBtn)
        content.addView(actionRow)

        content.addView(space(8))

        val terminalBtn = Button(this).apply {
            text = "LAUNCH SSH TERMINAL CLIENT →"
            setTextColor(cyan)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(Color.rgb(18, 30, 52), 10, border, 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            setOnClickListener { launchTerminal() }
        }
        content.addView(terminalBtn)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun pollStats() {
        executor.execute {
            val stats = OpenWrtClient.fetchSystemStats(
                ip = routerIp,
                username = sshUser,
                password = sshPass,
                knownHostname = routerHostname,
                source = scanSource
            )
            runOnUiThread {
                updateViewWithStats(stats)
            }
        }
    }

    private fun updateViewWithStats(stats: RouterStats) {
        currentStats = stats

        // 1. Top Section
        routerTitleView.text = stats.routerName
        routerModelView.text = "${stats.modelName} · ${stats.firmwareVersion}"
        uptimeValueView.text = stats.formattedUptime
        tempValueView.text = stats.formattedTemperature

        val temp = stats.temperatureC ?: 45.0
        when {
            temp > 70.0 -> {
                tempValueView.setTextColor(Color.rgb(255, 82, 82))
                tempStatusView.text = "● High Temperature"
                tempStatusView.setTextColor(Color.rgb(255, 82, 82))
            }
            temp > 55.0 -> {
                tempValueView.setTextColor(amber)
                tempStatusView.text = "● Warm Thermal Zone"
                tempStatusView.setTextColor(amber)
            }
            else -> {
                tempValueView.setTextColor(green)
                tempStatusView.text = "● Optimal Thermal State"
                tempStatusView.setTextColor(green)
            }
        }

        // 2. Middle Section: RAM
        ramPercentBadge.text = "${stats.ramPercent}% USED"
        ramProgressBar.progress = stats.ramPercent
        ramNumbersView.text = "Used: ${stats.ramUsedMb} MB / ${stats.ramTotalMb} MB"
        val ramFreeMb = stats.ramFreeBytes / (1024 * 1024)
        ramFreeView.text = "Free: $ramFreeMb MB"

        // Middle Section: Storage
        storagePercentBadge.text = "${stats.storagePercent}% USED"
        storageProgressBar.progress = stats.storagePercent
        storageNumbersView.text = "Used: ${stats.storageUsedMb} MB / ${stats.storageTotalMb} MB"
        val storageFreeMb = stats.storageFreeBytes / (1024 * 1024)
        storageFreeView.text = "Available: $storageFreeMb MB"
    }

    private fun openLuCi() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://$routerIp/"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Browser error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchTerminal() {
        val uri = Uri.parse("ssh://$sshUser@$routerIp:$sshPort")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val cmd = com.lemonrsh.openwrt.util.CertificateHelper.buildSshCommand(sshUser, routerIp, sshPort, autoAcceptCert = true)
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OpenWrt SSH", cmd))
            Toast.makeText(this, "Copied SSH command (auto-accepts host key): $cmd", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        isPolling = false
        handler.removeCallbacks(refreshRunnable)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.rgb(32, 44, 72))
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun createBarDrawable(startColor: Int, endColor: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(startColor, endColor)).apply {
            cornerRadius = dp(5).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
