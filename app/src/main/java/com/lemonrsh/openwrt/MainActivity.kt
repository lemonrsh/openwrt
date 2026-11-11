package com.lemonrsh.openwrt

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanProgress
import com.lemonrsh.openwrt.model.ScanSource
import com.lemonrsh.openwrt.model.ScanState
import com.lemonrsh.openwrt.scanner.LanScanner
import com.lemonrsh.openwrt.scanner.TailscaleScanner
import com.lemonrsh.openwrt.ui.ConnectDialog
import com.lemonrsh.openwrt.ui.RadarScanView
import com.lemonrsh.openwrt.util.HapticHelper
import com.lemonrsh.openwrt.util.RootHelper
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : Activity() {

    private val bg = Color.rgb(11, 16, 32)
    private val card = Color.rgb(22, 30, 51)
    private val cardFound = Color.rgb(26, 38, 64)
    private val muted = Color.rgb(151, 163, 190)
    private val white = Color.rgb(241, 245, 255)
    private val blue = Color.rgb(111, 145, 255)
    private val cyan = Color.rgb(0, 229, 255)
    private val green = Color.rgb(82, 214, 163)
    private val red = Color.rgb(255, 82, 82)
    private val amber = Color.rgb(255, 183, 77)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var radarView: RadarScanView
    private lateinit var statusText: TextView
    private lateinit var progressSubtext: TextView
    private lateinit var rootBadge: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var scanLanBtn: Button
    private lateinit var scanTailscaleBtn: Button
    private lateinit var scanAllBtn: Button

    private var lanScanner: LanScanner? = null
    private var tailscaleScanner: TailscaleScanner? = null

    private val discoveredDevices = CopyOnWriteArrayList<RouterDevice>()
    private var scanState = ScanState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        lanScanner = LanScanner(this)
        tailscaleScanner = TailscaleScanner(this)

        setupMainLayout()
        checkEnvironmentStatus()
    }

    private fun setupMainLayout() {
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
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Header with App Title & Root Status Pill
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "OPENWRT SCANNER"
            setTextColor(white)
            textSize = 20f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.06f
        }

        val subtitle = TextView(this).apply {
            text = "LAN & TAILSCALE SSH DISCOVERY"
            setTextColor(cyan)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
        }

        titleCol.addView(title)
        titleCol.addView(subtitle)

        rootBadge = TextView(this).apply {
            text = "CHECKING ROOT…"
            setTextColor(muted)
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val px = dp(9)
            val py = dp(5)
            setPadding(px, py, px, py)
            background = rounded(Color.rgb(18, 25, 42), 6, Color.rgb(38, 52, 85), 1)
        }

        header.addView(titleCol)
        header.addView(rootBadge)
        content.addView(header)

        content.addView(space(18))

        // 2. High-Tech Animated Radar Scan View
        val radarSize = dp(240)
        radarView = RadarScanView(this).apply {
            layoutParams = LinearLayout.LayoutParams(radarSize, radarSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        content.addView(radarView)

        content.addView(space(14))

        // 3. Scan Status Display
        statusText = TextView(this).apply {
            text = "READY TO SCAN"
            setTextColor(green)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.05f
        }
        content.addView(statusText)

        content.addView(space(4))

        progressSubtext = TextView(this).apply {
            text = "Tap a button below to search OpenWrt routers"
            setTextColor(muted)
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(progressSubtext)

        content.addView(space(18))

        // 4. Action Buttons (LAN / Tailscale Root / All)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scanLanBtn = Button(this).apply {
            text = "SCAN LAN"
            setTextColor(white)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(Color.rgb(30, 48, 88), 10, blue, 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startLanScan() }
        }

        scanTailscaleBtn = Button(this).apply {
            text = "TAILSCALE (ROOT)"
            setTextColor(white)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(Color.rgb(20, 52, 70), 10, cyan, 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            lp.marginStart = dp(8)
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener { startTailscaleScan() }
        }

        scanAllBtn = Button(this).apply {
            text = "SCAN BOTH"
            setTextColor(Color.rgb(10, 15, 26))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(green, 10)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startAllScan() }
        }

        buttonRow.addView(scanLanBtn)
        buttonRow.addView(scanTailscaleBtn)
        buttonRow.addView(scanAllBtn)
        content.addView(buttonRow)

        content.addView(space(24))

        // 5. Discovered Routers List Section
        val sectionHeader = TextView(this).apply {
            text = "DISCOVERED ROUTERS"
            setTextColor(muted)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(sectionHeader)

        content.addView(space(8))

        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(devicesContainer)

        // Initial empty card
        updateEmptyCard()

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun checkEnvironmentStatus() {
        Thread {
            val suExists = RootHelper.isSuBinaryPresent()
            val isRootGranted = if (suExists) RootHelper.isRootGranted() else false

            runOnUiThread {
                if (isRootGranted) {
                    rootBadge.text = "● ROOT GRANTED"
                    rootBadge.setTextColor(green)
                    rootBadge.background = rounded(Color.rgb(15, 45, 35), 6, green, 1)
                } else if (suExists) {
                    rootBadge.text = "ROOT SU AVAILABLE"
                    rootBadge.setTextColor(amber)
                    rootBadge.background = rounded(Color.rgb(45, 38, 20), 6, amber, 1)
                } else {
                    rootBadge.text = "NO ROOT (LAN ONLY)"
                    rootBadge.setTextColor(muted)
                    rootBadge.background = rounded(Color.rgb(25, 30, 45), 6, Color.rgb(40, 50, 75), 1)
                }
            }
        }.start()
    }

    private fun startLanScan() {
        if (scanState != ScanState.IDLE) return
        scanState = ScanState.SCANNING_LAN
        setButtonsScanning(true)
        HapticHelper.vibrateTick(this)
        radarView.startScanning()

        statusText.text = "SCANNING LAN SUBNET…"
        statusText.setTextColor(cyan)

        lanScanner?.scan(
            onProgress = { progress ->
                runOnUiThread {
                    progressSubtext.text = progress.statusMessage
                    radarView.addBlip()
                }
            },
            onDeviceFound = { device ->
                runOnUiThread {
                    handleDeviceFound(device)
                }
            },
            onComplete = { list ->
                runOnUiThread {
                    scanFinished("LAN scan complete: ${list.size} OpenWrt found")
                }
            }
        )
    }

    private fun startTailscaleScan() {
        if (scanState != ScanState.IDLE) return
        scanState = ScanState.SCANNING_TAILSCALE
        setButtonsScanning(true)
        HapticHelper.vibrateTick(this)
        radarView.startScanning()

        statusText.text = "INSPECTING TAILSCALE VIA ROOT…"
        statusText.setTextColor(cyan)

        tailscaleScanner?.scan(
            onProgress = { progress ->
                runOnUiThread {
                    progressSubtext.text = progress.statusMessage
                    radarView.addBlip()
                }
            },
            onDeviceFound = { device ->
                runOnUiThread {
                    handleDeviceFound(device)
                }
            },
            onComplete = { list, errorMsg ->
                runOnUiThread {
                    val summary = if (errorMsg != null && list.isEmpty()) {
                        errorMsg
                    } else {
                        "Tailscale scan complete: ${list.size} OpenWrt found"
                    }
                    scanFinished(summary)
                }
            }
        )
    }

    private fun startAllScan() {
        if (scanState != ScanState.IDLE) return
        scanState = ScanState.SCANNING_ALL
        setButtonsScanning(true)
        HapticHelper.vibrateTick(this)
        radarView.startScanning()

        statusText.text = "SCANNING LAN & TAILSCALE…"
        statusText.setTextColor(green)

        lanScanner?.scan(
            onProgress = { progress ->
                runOnUiThread {
                    progressSubtext.text = "[LAN] " + progress.statusMessage
                    radarView.addBlip()
                }
            },
            onDeviceFound = { device ->
                runOnUiThread {
                    handleDeviceFound(device)
                }
            },
            onComplete = { lanList ->
                // Next do Tailscale
                tailscaleScanner?.scan(
                    onProgress = { progress ->
                        runOnUiThread {
                            progressSubtext.text = "[Tailscale] " + progress.statusMessage
                            radarView.addBlip()
                        }
                    },
                    onDeviceFound = { device ->
                        runOnUiThread {
                            handleDeviceFound(device)
                        }
                    },
                    onComplete = { tsList, _ ->
                        runOnUiThread {
                            val total = lanList.size + tsList.size
                            scanFinished("Complete: $total OpenWrt router(s) found")
                        }
                    }
                )
            }
        )
    }

    private fun handleDeviceFound(device: RouterDevice) {
        if (discoveredDevices.any { it.ip == device.ip }) return
        discoveredDevices.add(0, device)

        // 1. Radar animation lock
        radarView.notifyDeviceFound(device.ip)

        // 2. Triumphant vibration haptic feedback!
        HapticHelper.vibrateFound(this)

        // 3. Update status text
        statusText.text = "● OPENWRT FOUND: ${device.ip}"
        statusText.setTextColor(green)

        // 4. Render card with animated entrance
        renderDevicesList()

        // 5. Automatically popup connect dialog for the discovered router!
        showConnectDialog(device)
    }

    private fun scanFinished(message: String) {
        scanState = ScanState.IDLE
        setButtonsScanning(false)
        radarView.stopScanning()
        statusText.text = if (discoveredDevices.isNotEmpty()) "ROUTERS DISCOVERED" else "SCAN FINISHED"
        statusText.setTextColor(if (discoveredDevices.isNotEmpty()) green else muted)
        progressSubtext.text = message
    }

    private fun setButtonsScanning(isScanning: Boolean) {
        scanLanBtn.isEnabled = !isScanning
        scanTailscaleBtn.isEnabled = !isScanning
        scanAllBtn.isEnabled = !isScanning
        scanLanBtn.alpha = if (isScanning) 0.5f else 1.0f
        scanTailscaleBtn.alpha = if (isScanning) 0.5f else 1.0f
        scanAllBtn.alpha = if (isScanning) 0.5f else 1.0f
    }

    private fun renderDevicesList() {
        devicesContainer.removeAllViews()

        if (discoveredDevices.isEmpty()) {
            updateEmptyCard()
            return
        }

        for (device in discoveredDevices) {
            val cardView = createDeviceCard(device)
            devicesContainer.addView(cardView)

            // Smooth entrance animation
            cardView.alpha = 0f
            cardView.scaleY = 0.85f
            cardView.scaleX = 0.85f
            cardView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun createDeviceCard(device: RouterDevice): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardFound, 14, green, 1)
            val p = dp(16)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                HapticHelper.vibrateTick(this@MainActivity)
                showConnectDialog(device)
            }
        }

        // Header: Hostname/IP + Source Badge
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = device.displayName
            setTextColor(white)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        val ipView = TextView(this).apply {
            text = "SSH Port ${device.port}  ·  ${device.latencyMs} ms"
            setTextColor(cyan)
            textSize = 12f
        }

        nameCol.addView(nameView)
        nameCol.addView(ipView)

        val sourceBadge = TextView(this).apply {
            text = if (device.source == ScanSource.TAILSCALE) "TAILSCALE" else "LAN"
            textSize = 10f
            val badgeColor = if (device.source == ScanSource.TAILSCALE) cyan else green
            setTextColor(badgeColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val px = dp(8)
            val py = dp(4)
            setPadding(px, py, px, py)
            background = rounded(Color.argb(40, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)), 6, badgeColor, 1)
        }

        topRow.addView(nameCol)
        topRow.addView(sourceBadge)
        card.addView(topRow)

        card.addView(space(10))

        // Service badge pill
        val serverPill = TextView(this).apply {
            text = "● ${device.serverSummary}" + if (device.hasLuCi) "  +  LuCI Web UI" else ""
            setTextColor(green)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        card.addView(serverPill)

        card.addView(space(10))

        // Connect action button on card
        val connectPrompt = TextView(this).apply {
            text = "TAP TO ENTER PASSWORD & CONNECT →"
            setTextColor(blue)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
        }
        card.addView(connectPrompt)

        return card
    }

    private fun updateEmptyCard() {
        devicesContainer.removeAllViews()
        val emptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card, 12, Color.rgb(32, 44, 72), 1)
            val p = dp(24)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = TextView(this).apply {
            text = "☵"
            setTextColor(Color.rgb(65, 85, 130))
            textSize = 32f
            gravity = Gravity.CENTER
        }

        val msg = TextView(this).apply {
            text = "No OpenWrt routers discovered yet\nSelect LAN or Tailscale scan above"
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        emptyCard.addView(icon)
        emptyCard.addView(space(8))
        emptyCard.addView(msg)
        devicesContainer.addView(emptyCard)
    }

    private var activeDialog: ConnectDialog? = null

    private fun showConnectDialog(device: RouterDevice) {
        if (isFinishing || isDestroyed) return
        if (activeDialog?.isShowing == true) return

        val dialog = ConnectDialog(this, device)
        activeDialog = dialog
        dialog.setOnDismissListener { activeDialog = null }
        dialog.show()
    }

    override fun onDestroy() {
        lanScanner?.cancel()
        tailscaleScanner?.cancel()
        super.onDestroy()
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
