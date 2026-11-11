package com.lemonrsh.openwrt.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lemonrsh.openwrt.model.RouterDevice
import com.lemonrsh.openwrt.model.ScanSource
import com.lemonrsh.openwrt.scanner.OpenWrtProbe
import java.util.concurrent.Executors

class ConnectDialog(
    context: Context,
    private val router: RouterDevice
) : Dialog(context) {

    private val bgDark = Color.rgb(14, 20, 36)
    private val cardDark = Color.rgb(22, 32, 56)
    private val inputBg = Color.rgb(26, 38, 68)
    private val textWhite = Color.rgb(241, 245, 255)
    private val textMuted = Color.rgb(151, 163, 190)
    private val accentCyan = Color.rgb(0, 229, 255)
    private val accentGreen = Color.rgb(82, 214, 163)
    private val accentBlue = Color.rgb(111, 145, 255)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var portInput: EditText
    private lateinit var togglePassBtn: TextView
    private lateinit var statusLabel: TextView
    private lateinit var testButton: Button

    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Ensure keyboard resizes and can be shown properly
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }

        setupView()

        setOnShowListener {
            val metrics = context.resources.displayMetrics
            window?.setLayout(
                (metrics.widthPixels * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Auto focus password or show keyboard
            passwordInput.requestFocus()
            mainHandler.postDelayed({
                try {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Exception) {}
            }, 150)
        }
    }

    private fun setupView() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bgDark, 20, Color.rgb(45, 60, 95), 2)
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Header Bar: Icon + Router Title + Source Badge
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeText = when (router.source) {
            ScanSource.LAN -> "LAN"
            ScanSource.TAILSCALE -> "TAILSCALE"
            ScanSource.MANUAL -> "MANUAL"
        }
        val badgeColor = if (router.source == ScanSource.TAILSCALE) accentCyan else accentGreen

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(context).apply {
            text = if (router.isOpenWrt) "OpenWrt Router" else "Linux Router"
            setTextColor(textWhite)
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        val subtitle = TextView(context).apply {
            text = "${router.ip} : ${router.port}  (${router.latencyMs} ms)"
            setTextColor(accentCyan)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        titleCol.addView(title)
        titleCol.addView(subtitle)

        val sourceBadge = TextView(context).apply {
            text = badgeText
            textSize = 11f
            setTextColor(badgeColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(8)
            val pY = dp(4)
            setPadding(pX, pY, pX, pY)
            background = rounded(Color.argb(40, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)), 6, badgeColor, 1)
        }

        header.addView(titleCol)
        header.addView(sourceBadge)
        content.addView(header)

        content.addView(space(14))
        content.addView(divider())
        content.addView(space(14))

        // Service Info Box (Dropbear / LuCI)
        val infoBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardDark, 10, Color.rgb(35, 48, 80), 1)
            val p = dp(12)
            setPadding(p, p, p, p)
        }

        val infoTitle = TextView(context).apply {
            text = "● SSH SERVICE ACTIVE"
            setTextColor(accentGreen)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val infoDesc = TextView(context).apply {
            text = router.serverSummary + (if (router.hasLuCi) "\n● LuCI Web UI detected on port 80" else "")
            setTextColor(textMuted)
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        infoBox.addView(infoTitle)
        infoBox.addView(space(4))
        infoBox.addView(infoDesc)
        content.addView(infoBox)

        content.addView(space(16))

        // 2. Input Fields
        // Username (default 'root')
        content.addView(label("SSH USERNAME"))
        content.addView(space(4))
        usernameInput = EditText(context).apply {
            setText("root")
            setTextColor(textWhite)
            setHintTextColor(textMuted)
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine(true)
        }
        content.addView(usernameInput)

        content.addView(space(12))

        // Password with show/hide toggle
        val passHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val passLabel = label("SSH PASSWORD").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        togglePassBtn = TextView(context).apply {
            text = "👁 SHOW"
            textSize = 11f
            setTextColor(accentCyan)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val pX = dp(6)
            val pY = dp(2)
            setPadding(pX, pY, pX, pY)
            setOnClickListener { togglePasswordVisibility() }
        }
        passHeader.addView(passLabel)
        passHeader.addView(togglePassBtn)
        content.addView(passHeader)

        content.addView(space(4))

        passwordInput = EditText(context).apply {
            hint = "Enter OpenWrt password (or leave empty)"
            setTextColor(textWhite)
            setHintTextColor(Color.rgb(100, 115, 145))
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    openDashboard()
                    true
                } else false
            }
        }
        content.addView(passwordInput)

        content.addView(space(12))

        // Port (default 22)
        content.addView(label("SSH PORT"))
        content.addView(space(4))
        portInput = EditText(context).apply {
            setText(router.port.toString())
            setTextColor(textWhite)
            setHintTextColor(textMuted)
            textSize = 14f
            background = rounded(inputBg, 8, Color.rgb(40, 56, 96), 1)
            val p = dp(12)
            setPadding(p, p, p, p)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isCursorVisible = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        content.addView(portInput)

        content.addView(space(12))

        // Status Label for SSH testing
        statusLabel = TextView(context).apply {
            text = "Enter credentials to connect to router dashboard"
            setTextColor(textMuted)
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(statusLabel)

        content.addView(space(16))

        // 3. Action Buttons
        // Primary Connect Button: Opens the Router Dashboard UI!
        val connectBtn = Button(context).apply {
            text = "CONNECT TO ROUTER →"
            setTextColor(Color.rgb(10, 15, 26))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(accentGreen, 10)
            val p = dp(13)
            setPadding(p, p, p, p)
            setOnClickListener { openDashboard() }
        }
        content.addView(connectBtn)

        content.addView(space(8))

        // Row of Secondary Actions: Test Connection & Open LuCI
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        testButton = Button(context).apply {
            text = "Verify SSH Port"
            setTextColor(accentCyan)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = rounded(cardDark, 10, accentCyan, 1)
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testSshConnection() }
        }

        val luciBtn = Button(context).apply {
            text = "Open LuCI Web"
            setTextColor(accentBlue)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = rounded(cardDark, 10, accentBlue, 1)
            val p = dp(10)
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener { openLuCiInBrowser() }
        }

        actionRow.addView(testButton)
        actionRow.addView(luciBtn)
        content.addView(actionRow)

        content.addView(space(8))

        // External terminal client button
        val terminalBtn = Button(context).apply {
            text = "Launch External Terminal App"
            setTextColor(accentCyan)
            textSize = 12f
            background = rounded(inputBg, 8)
            setOnClickListener { launchSshClient() }
        }
        content.addView(terminalBtn)

        content.addView(space(6))

        // Tertiary row: Copy SSH command & Close
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyBtn = Button(context).apply {
            text = "Copy SSH Command"
            setTextColor(textMuted)
            textSize = 12f
            background = rounded(Color.TRANSPARENT, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { copySshCommand() }
        }

        val closeBtn = Button(context).apply {
            text = "Close"
            setTextColor(textMuted)
            textSize = 12f
            background = rounded(Color.TRANSPARENT, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dismiss() }
        }

        bottomRow.addView(copyBtn)
        bottomRow.addView(closeBtn)
        content.addView(bottomRow)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        if (isPasswordVisible) {
            passwordInput.transformationMethod = null
            togglePassBtn.text = "🔒 HIDE"
        } else {
            passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
            togglePassBtn.text = "👁 SHOW"
        }
        passwordInput.setSelection(passwordInput.text.length)
    }

    private fun openDashboard() {
        val user = usernameInput.text.toString().trim().ifBlank { "root" }
        val pass = passwordInput.text.toString()
        val port = portInput.text.toString().toIntOrNull() ?: 22

        val intent = Intent(context, com.lemonrsh.openwrt.RouterDashboardActivity::class.java).apply {
            putExtra("EXTRA_IP", router.ip)
            putExtra("EXTRA_HOSTNAME", router.displayName)
            putExtra("EXTRA_USER", user)
            putExtra("EXTRA_PASS", pass)
            putExtra("EXTRA_PORT", port)
            putExtra("EXTRA_SOURCE", router.source.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        dismiss()
    }

    private fun testSshConnection() {
        val port = portInput.text.toString().toIntOrNull() ?: 22
        statusLabel.text = "Testing SSH connection to ${router.ip}:$port…"
        statusLabel.setTextColor(accentCyan)
        testButton.isEnabled = false

        executor.execute {
            val res = OpenWrtProbe.probeSsh(router.ip, port, timeoutMs = 1200)
            mainHandler.post {
                testButton.isEnabled = true
                if (res.isOpen) {
                    statusLabel.text = "✓ SSH Port $port OPEN: ${res.banner ?: "Dropbear Ready"}"
                    statusLabel.setTextColor(accentGreen)
                } else {
                    statusLabel.text = "✗ Port $port unreachable or closed"
                    statusLabel.setTextColor(Color.rgb(255, 82, 82))
                }
            }
        }
    }

    private fun launchSshClient() {
        val user = usernameInput.text.toString().trim().ifBlank { "root" }
        val port = portInput.text.toString().toIntOrNull() ?: 22
        val uri = Uri.parse("ssh://$user@${router.ip}:$port")

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            Toast.makeText(context, "Opening SSH client…", Toast.LENGTH_SHORT).show()
            dismiss()
        } catch (_: Exception) {
            copySshCommand()
            Toast.makeText(
                context,
                "No SSH app registered for ssh:// URL. SSH command copied to clipboard!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openLuCiInBrowser() {
        val url = "http://${router.ip}/"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            dismiss()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copySshCommand() {
        val user = usernameInput.text.toString().trim().ifBlank { "root" }
        val port = portInput.text.toString().toIntOrNull() ?: 22
        val cmd = com.lemonrsh.openwrt.util.CertificateHelper.buildSshCommand(user, router.ip, port, autoAcceptCert = true)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenWrt SSH Command", cmd)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "Copied: $cmd", Toast.LENGTH_SHORT).show()
    }

    private fun label(s: String) = TextView(context).apply {
        text = s
        setTextColor(textMuted)
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.05f
    }

    private fun space(h: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun divider() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.rgb(35, 48, 80))
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
