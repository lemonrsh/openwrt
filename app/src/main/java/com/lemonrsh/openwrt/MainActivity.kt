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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanSource
import com.lemonrsh.openwrt.model.ScanState
import com.lemonrsh.openwrt.scanner.LanScanner
import com.lemonrsh.openwrt.scanner.OpenWrtProbe
import com.lemonrsh.openwrt.scanner.TailscaleScanner
import com.lemonrsh.openwrt.ui.ConnectDialog
import com.lemonrsh.openwrt.ui.RadarScanView
import com.lemonrsh.openwrt.util.CertificateHelper
import com.lemonrsh.openwrt.util.HapticHelper
import com.lemonrsh.openwrt.util.RootHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val bg = Color.rgb(11, 16, 32)
    private val card = Color.rgb(22, 30, 51)
    private val cardFound = Color.rgb(26, 38, 64)
    private val cardSelected = Color.rgb(18, 28, 52)
    private val inputBg = Color.rgb(26, 38, 68)
    private val muted = Color.rgb(151, 163, 190)
    private val white = Color.rgb(241, 245, 255)
    private val blue = Color.rgb(111, 145, 255)
    private val cyan = Color.rgb(0, 229, 255)
    private val green = Color.rgb(82, 214, 163)
    private val amber = Color.rgb(255, 183, 77)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

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

    // Selected device for inline login (NO disruptive pop-up after scan!)
    private var selectedDevice: RouterDevice? = null
    private val routerUsernames = mutableMapOf<String, String>()
    private val routerPasswords = mutableMapOf<String, String>()
    private val routerPorts = mutableMapOf<String, Int>()
    private val passwordVisibilityState = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        // Auto-accept all SSL/TLS self-signed router certificates (e.g. LuCI HTTPS)
        CertificateHelper.installTrustAllCertificates()

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
                    rootBadge.background = rounded(Color.rgb(25, 32, 48), 6, Color.rgb(40, 52, 75), 1)
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

        statusText.text = "SCANNING LOCAL WI-FI SUBNET…"
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
                    scanFinished("LAN scan finished: ${list.size} OpenWrt router(s) found")
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

        statusText.text = "PROBING TAILSCALE CGNAT NODES…"
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

        // Select the first device discovered so it's ready for login right away
        if (selectedDevice == null) {
            selectedDevice = device
        }

        // 1. Radar animation lock
        radarView.notifyDeviceFound(device.ip)

        // 2. Triumphant vibration haptic feedback!
        HapticHelper.vibrateFound(this)

        // 3. Update status text
        statusText.text = "● OPENWRT FOUND: ${device.displayName}"
        statusText.setTextColor(green)
        progressSubtext.text = "Select router below and enter credentials to log in"

        // 4. Render card with inline login (NO disruptive pop-up!)
        renderDevicesList()
    }

    private fun scanFinished(message: String) {
        scanState = ScanState.IDLE
        setButtonsScanning(false)
        radarView.stopScanning()
        statusText.text = if (discoveredDevices.isNotEmpty()) "ROUTERS DISCOVERED (SELECT TO LOG IN)" else "SCAN FINISHED"
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
            val isSelected = (selectedDevice?.ip == device.ip)
            val cardView = createDeviceCard(device, isSelected)
            devicesContainer.addView(cardView)

            // Smooth entrance animation
            cardView.alpha = 0f
            cardView.scaleY = 0.92f
            cardView.scaleX = 0.92f
            cardView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(350)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    private fun createDeviceCard(device: RouterDevice, isSelected: Boolean): View {
        val strokeColor = if (isSelected) cyan else Color.rgb(38, 54, 88)
        val strokeWidth = if (isSelected) 2 else 1
        val cardBg = if (isSelected) cardSelected else cardFound

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 14, strokeColor, strokeWidth)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        // Header: Hostname/IP + Source Badge + Selection Indicator
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                HapticHelper.vibrateTick(this@MainActivity)
                selectedDevice = if (isSelected) null else device
                renderDevicesList()
            }
        }

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = (if (isSelected) "▶ " else "") + device.displayName
            setTextColor(if (isSelected) cyan else white)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        val ipView = TextView(this).apply {
            text = "${device.ip} : ${device.port}  ·  ${device.latencyMs} ms"
            setTextColor(if (isSelected) green else muted)
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

        card.addView(space(8))

        // Service badge pill
        val serverPill = TextView(this).apply {
            text = "● ${device.serverSummary}" + if (device.hasLuCi) "  +  LuCI Web UI" else ""
            setTextColor(green)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isClickable = true
            setOnClickListener {
                HapticHelper.vibrateTick(this@MainActivity)
                selectedDevice = if (isSelected) null else device
                renderDevicesList()
            }
        }
        card.addView(serverPill)

        if (!isSelected) {
            card.addView(space(10))
            val selectPrompt = TextView(this).apply {
                text = "TAP TO SELECT & LOG IN →"
                setTextColor(blue)
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.05f
            }
            card.addView(selectPrompt)
            card.setOnClickListener {
                HapticHelper.vibrateTick(this@MainActivity)
                selectedDevice = device
                renderDevicesList()
            }
            return card
        }

        // ==========================================
        // INLINE LOGIN PANEL (NO POP-UP NEEDED!)
        // ==========================================
        card.addView(space(12))
        card.addView(divider())
        card.addView(space(12))

        // Auto-accept Certificate & SSH Key Notice
        val certNotice = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(18, 36, 44), 8, Color.rgb(20, 80, 80), 1)
            val p = dp(8)
            setPadding(p, p, p, p)
        }

        val certIcon = TextView(this).apply {
            text = "🛡 "
            textSize = 12f
        }

        val certText = TextView(this).apply {
            text = "Auto-Accept SSH Host Certificate & SSL Keys (StrictHostKeyChecking=no)"
            setTextColor(green)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        certNotice.addView(certIcon)
        certNotice.addView(certText)
        card.addView(certNotice)

        card.addView(space(12))

        // Username Field
        card.addView(label("SSH USERNAME"))
        card.addView(space(4))

        val currentUsername = routerUsernames[device.ip] ?: "root"
        val usernameInput = EditText(this).apply {
            setText(currentUsername)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(11)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    routerUsernames[device.ip] = s?.toString()?.trim() ?: "root"
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        card.addView(usernameInput)

        card.addView(space(10))

        // Password Header with SHOW/HIDE Toggle
        val passHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val passLabel = label("SSH PASSWORD").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        var isPassVisible = passwordVisibilityState[device.ip] ?: false

        val toggleBtn = TextView(this).apply {
            text = if (isPassVisible) "🔒 HIDE" else "👁 SHOW"
            textSize = 11f
            setTextColor(cyan)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val px = dp(6)
            val py = dp(2)
            setPadding(px, py, px, py)
        }

        passHeader.addView(passLabel)
        passHeader.addView(toggleBtn)
        card.addView(passHeader)

        card.addView(space(4))

        val currentPassword = routerPasswords[device.ip] ?: ""
        val passwordInput = EditText(this).apply {
            hint = "Enter OpenWrt password (or leave empty)"
            setText(currentPassword)
            setTextColor(white)
            setHintTextColor(Color.rgb(100, 115, 145))
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(11)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (!isPassVisible) {
                transformationMethod = PasswordTransformationMethod.getInstance()
            } else {
                transformationMethod = null
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    routerPasswords[device.ip] = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val user = usernameInput.text.toString().trim().ifBlank { "root" }
                    val pass = text.toString()
                    val port = routerPorts[device.ip] ?: device.port
                    loginAndConnect(device, user, pass, port)
                    true
                } else false
            }
        }

        toggleBtn.setOnClickListener {
            isPassVisible = !isPassVisible
            passwordVisibilityState[device.ip] = isPassVisible
            if (isPassVisible) {
                passwordInput.transformationMethod = null
                toggleBtn.text = "🔒 HIDE"
            } else {
                passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
                toggleBtn.text = "👁 SHOW"
            }
            passwordInput.setSelection(passwordInput.text.length)
        }

        card.addView(passwordInput)

        card.addView(space(10))

        // Port Field
        card.addView(label("SSH PORT"))
        card.addView(space(4))

        val currentPort = routerPorts[device.ip] ?: device.port
        val portInput = EditText(this).apply {
            setText(currentPort.toString())
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(11)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    routerPorts[device.ip] = s?.toString()?.toIntOrNull() ?: device.port
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        card.addView(portInput)

        card.addView(space(12))

        // Status Feedback View
        val cardStatusLabel = TextView(this).apply {
            text = "Press button below to log in and open router dashboard"
            setTextColor(muted)
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        card.addView(cardStatusLabel)

        card.addView(space(14))

        // PRIMARY ACTION: LOGIN & CONNECT
        val loginBtn = Button(this).apply {
            text = "LOGIN & CONNECT TO ROUTER →"
            setTextColor(Color.rgb(10, 15, 26))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(green, 10)
            val p = dp(13)
            setPadding(p, p, p, p)
            setOnClickListener {
                val user = usernameInput.text.toString().trim().ifBlank { "root" }
                val pass = passwordInput.text.toString()
                val port = portInput.text.toString().toIntOrNull() ?: device.port
                loginAndConnect(device, user, pass, port)
            }
        }
        card.addView(loginBtn)

        card.addView(space(8))

        // SECONDARY ACTIONS: Test SSH & Open LuCI
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val testBtn = Button(this).apply {
            text = "Verify SSH Port"
            setTextColor(cyan)
            textSize = 12f
            background = rounded(card, 8, cyan, 1)
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val port = portInput.text.toString().toIntOrNull() ?: device.port
                cardStatusLabel.text = "Testing SSH on ${device.ip}:$port…"
                cardStatusLabel.setTextColor(cyan)
                isEnabled = false
                executor.execute {
                    val res = OpenWrtProbe.probeSsh(device.ip, port, timeoutMs = 1200)
                    runOnUiThread {
                        isEnabled = true
                        if (res.isOpen) {
                            cardStatusLabel.text = "✓ SSH Port $port OPEN: ${res.banner ?: "Dropbear Ready"}"
                            cardStatusLabel.setTextColor(green)
                        } else {
                            cardStatusLabel.text = "✗ Port $port unreachable or closed"
                            cardStatusLabel.setTextColor(Color.rgb(255, 82, 82))
                        }
                    }
                }
            }
        }

        val luciBtn = Button(this).apply {
            text = "Open LuCI Web"
            setTextColor(blue)
            textSize = 12f
            background = rounded(card, 8, blue, 1)
            val p = dp(10)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener {
                openLuCiInBrowser(device.ip)
            }
        }

        actionRow.addView(testBtn)
        actionRow.addView(luciBtn)
        card.addView(actionRow)

        card.addView(space(8))

        // TERTIARY ROW: Launch External Terminal & Copy SSH Command
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val terminalBtn = Button(this).apply {
            text = "Terminal (ssh://)"
            setTextColor(cyan)
            textSize = 12f
            background = rounded(inputBg, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val user = usernameInput.text.toString().trim().ifBlank { "root" }
                val port = portInput.text.toString().toIntOrNull() ?: device.port
                launchSshTerminal(device.ip, user, port)
            }
        }

        val copyBtn = Button(this).apply {
            text = "Copy SSH Command"
            setTextColor(muted)
            textSize = 12f
            background = rounded(inputBg, 8)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener {
                val user = usernameInput.text.toString().trim().ifBlank { "root" }
                val port = portInput.text.toString().toIntOrNull() ?: device.port
                val cmd = CertificateHelper.buildSshCommand(user, device.ip, port, autoAcceptCert = true)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("OpenWrt SSH", cmd))
                Toast.makeText(this@MainActivity, "Copied: $cmd", Toast.LENGTH_SHORT).show()
            }
        }

        bottomRow.addView(terminalBtn)
        bottomRow.addView(copyBtn)
        card.addView(bottomRow)

        return card
    }

    private fun loginAndConnect(device: RouterDevice, user: String, pass: String, port: Int) {
        HapticHelper.vibrateTick(this)
        val intent = Intent(this, RouterDashboardActivity::class.java).apply {
            putExtra("EXTRA_IP", device.ip)
            putExtra("EXTRA_HOSTNAME", device.displayName)
            putExtra("EXTRA_USER", user)
            putExtra("EXTRA_PASS", pass)
            putExtra("EXTRA_PORT", port)
            putExtra("EXTRA_SOURCE", device.source.name)
            putExtra("EXTRA_AUTO_ACCEPT_CERT", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun launchSshTerminal(ip: String, user: String, port: Int) {
        val uri = Uri.parse("ssh://$user@$ip:$port")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            Toast.makeText(this, "Launching SSH terminal…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            val cmd = CertificateHelper.buildSshCommand(user, ip, port, autoAcceptCert = true)
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OpenWrt SSH Command", cmd))
            Toast.makeText(this, "SSH command copied (auto-accepts host key): $cmd", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLuCiInBrowser(ip: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip/")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    override fun onDestroy() {
        lanScanner?.cancel()
        tailscaleScanner?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        setTextColor(muted)
        textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.05f
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.rgb(38, 54, 88))
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
