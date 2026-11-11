package com.lemonrsh.openwrt.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isScanning = false
    private var sweepAngle = 0f
    private var pulseRadiusRatio = 0f
    private var lockOnTarget = false
    private var targetAngle = 45f
    private var targetDistanceRatio = 0.65f
    private var targetPulse = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#1C2C4E")
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#253A66")
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#00E5FF")
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#52D6A3")
    }

    private val targetLockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#52D6A3")
    }

    private val targetGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val blips = mutableListOf<Blip>()

    data class Blip(
        val angle: Float,
        val distanceRatio: Float,
        var alpha: Float = 1.0f
    )

    private var sweepAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        setupAnimators()
    }

    private fun setupAnimators() {
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                updateBlips()
                invalidate()
            }
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseRadiusRatio = it.animatedValue as Float
                if (lockOnTarget) {
                    targetPulse = (it.animatedValue as Float)
                }
            }
        }
    }

    fun startScanning() {
        isScanning = true
        lockOnTarget = false
        blips.clear()
        sweepAnimator?.start()
        pulseAnimator?.start()
    }

    fun stopScanning() {
        isScanning = false
        sweepAnimator?.cancel()
        pulseAnimator?.cancel()
        invalidate()
    }

    fun notifyDeviceFound(ip: String) {
        lockOnTarget = true
        // Position target on radar based on IP hash
        val hash = ip.hashCode()
        targetAngle = (Math.abs(hash) % 360).toFloat()
        targetDistanceRatio = 0.45f + ((Math.abs(hash / 360) % 40) / 100f)

        // Add prominent blip
        blips.add(Blip(targetAngle, targetDistanceRatio, 1.0f))
        invalidate()
    }

    fun addBlip(angle: Float = (Math.random() * 360).toFloat(), distance: Float = 0.2f + (Math.random() * 0.7f).toFloat()) {
        if (blips.size > 15) {
            blips.removeAt(0)
        }
        blips.add(Blip(angle, distance, 1.0f))
        invalidate()
    }

    private fun updateBlips() {
        val iterator = blips.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            b.alpha -= 0.015f
            if (b.alpha <= 0.05f && (!lockOnTarget || b.angle != targetAngle)) {
                iterator.remove()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f) * 0.88f
        if (radius <= 0) return

        // 1. Draw Concentric Grid Rings
        for (i in 1..4) {
            val r = radius * (i / 4f)
            gridPaint.color = if (i == 4) Color.parseColor("#2F4370") else Color.parseColor("#182542")
            canvas.drawCircle(cx, cy, r, gridPaint)
        }

        // 2. Draw Crosshairs & Diagonals
        canvas.drawLine(cx - radius, cy, cx + radius, cy, dashedPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, dashedPaint)

        // 3. Draw Sonar Wave Pulse (expanding outwards)
        if (isScanning) {
            val pulseR = radius * pulseRadiusRatio
            val alpha = ((1f - pulseRadiusRatio) * 180).toInt().coerceIn(0, 255)
            pulsePaint.color = Color.argb(alpha, 0, 229, 255)
            canvas.drawCircle(cx, cy, pulseR, pulsePaint)
        }

        // 4. Draw Rotating Radar Sweep
        if (isScanning) {
            canvas.save()
            canvas.rotate(sweepAngle, cx, cy)

            val sweepShader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(15, 0, 229, 255),
                    Color.argb(80, 0, 229, 255),
                    Color.argb(160, 82, 214, 163)
                ),
                floatArrayOf(0.0f, 0.70f, 0.82f, 0.95f, 1.0f)
            )
            sweepPaint.shader = sweepShader
            canvas.drawCircle(cx, cy, radius, sweepPaint)

            // Leading bright sweep line
            val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#52D6A3")
                strokeWidth = dp(2.2f)
            }
            canvas.drawLine(cx, cy, cx + radius, cy, leadPaint)

            canvas.restore()
        }

        // 5. Draw Discovered Blips
        for (blip in blips) {
            val rad = Math.toRadians(blip.angle.toDouble())
            val bx = (cx + (radius * blip.distanceRatio).toDouble() * cos(rad)).toFloat()
            val by = (cy + (radius * blip.distanceRatio).toDouble() * sin(rad)).toFloat()

            blipPaint.color = Color.argb((blip.alpha.coerceIn(0f, 1f) * 255).toInt(), 82, 214, 163)
            canvas.drawCircle(bx, by, dp(4.5f), blipPaint)
        }

        // 6. Draw Locked Target Reticle & Shockwave if OpenWrt found
        if (lockOnTarget) {
            val rad = Math.toRadians(targetAngle.toDouble())
            val tx = (cx + (radius * targetDistanceRatio).toDouble() * cos(rad)).toFloat()
            val ty = (cy + (radius * targetDistanceRatio).toDouble() * sin(rad)).toFloat()

            // Target pulse wave
            val tPulseR = dp(12f) + dp(18f) * targetPulse
            val tPulseAlpha = ((1f - targetPulse) * 220).toInt().coerceIn(0, 255)
            targetLockPaint.color = Color.argb(tPulseAlpha, 82, 214, 163)
            canvas.drawCircle(tx, ty, tPulseR, targetLockPaint)

            // Inner target crosshair reticle
            targetLockPaint.color = Color.parseColor("#00E5FF")
            targetLockPaint.strokeWidth = dp(2f)
            canvas.drawCircle(tx, ty, dp(8f), targetLockPaint)

            val ch = dp(14f)
            canvas.drawLine(tx - ch, ty, tx - dp(10f), ty, targetLockPaint)
            canvas.drawLine(tx + dp(10f), ty, tx + ch, ty, targetLockPaint)
            canvas.drawLine(tx, ty - ch, tx, ty - dp(10f), targetLockPaint)
            canvas.drawLine(tx, ty + dp(10f), tx, ty + ch, targetLockPaint)

            // Glowing center dot
            targetGlowPaint.color = Color.parseColor("#52D6A3")
            canvas.drawCircle(tx, ty, dp(3.5f), targetGlowPaint)
        }

        // Center hub dot
        val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isScanning) Color.parseColor("#00E5FF") else Color.parseColor("#3D507F")
        }
        canvas.drawCircle(cx, cy, dp(4.5f), centerDotPaint)
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
