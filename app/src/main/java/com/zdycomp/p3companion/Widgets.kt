package com.zdycomp.p3companion

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Battery ring for the Glasses hero card (DESIGN-SPEC 3.13): 8 dp stroke, round caps, the number in
 * the middle, a bolt when charging. The sweep animates from the last value shown.
 */
class RingView(context: Context, private val ui: Ui, private val trackColor: Int, private val textColor: Int) : View(context) {
    private val stroke = ui.dpf(8f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = trackColor }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = ui.font(500)
        fontFeatureSettings = "tnum"
    }
    private val box = RectF()
    private var shown = 0f
    private var glowAt = -1f
    private var anim: ValueAnimator? = null
    private var loop: ValueAnimator? = null
    private val bolt = ui.drawable(R.drawable.ic_bolt, textColor)

    var value: Int? = null
        private set
    var charging = false
        private set

    fun set(percent: Int?, isCharging: Boolean) {
        value = percent?.coerceIn(0, 100)
        charging = isCharging && percent != null
        val target = (value ?: 0).toFloat()
        val from = lastShown
        anim?.cancel()
        if (ui.reduceMotion || value == null) {
            shown = target
        } else {
            anim = ValueAnimator.ofFloat(from, target).apply {
                duration = 600
                interpolator = ui.decelerate
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (value != null) lastShown = target
        arc.color = when {
            value == null -> trackColor
            value!! >= 50 -> ui.s.success
            value!! >= 20 -> ui.s.warning
            else -> ui.s.error
        }
        glow.color = Ui.withAlpha(textColor, 128)
        contentDescription = "Battery"
        stateDescription = if (value == null) "Unknown" else "$value percent" + if (charging) ", charging" else ""
        updateLoop()
        invalidate()
    }

    private fun updateLoop() {
        loop?.cancel(); loop = null; glowAt = -1f
        if (!charging || ui.reduceMotion || !isAttachedToWindow) return
        loop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { glowAt = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }
    override fun onDetachedFromWindow() { loop?.cancel(); anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val pad = stroke / 2 + 1
        box.set(pad, pad, width - pad, height - pad)
        track.pathEffect = if (value == null) DashPathEffect(floatArrayOf(ui.dpf(4f), ui.dpf(6f)), 0f) else null
        c.drawArc(box, 0f, 360f, false, track)
        if (value != null && shown > 0f) {
            val sweep = 360f * shown / 100f
            c.drawArc(box, -90f, sweep, false, arc)
            if (glowAt >= 0f) c.drawArc(box, -90f + (sweep - 20f).coerceAtLeast(0f) * glowAt, minOf(20f, sweep), false, glow)
        }
        val text = value?.let { "$it%" } ?: "—"
        label.textSize = height * 0.24f
        val cy = height / 2f - (label.ascent() + label.descent()) / 2f - if (charging) height * 0.06f else 0f
        c.drawText(text, width / 2f, cy, label)
        if (charging) {
            val s = (height * 0.18f).toInt()
            val x = width / 2 - s / 2
            val y = (cy + label.descent() + height * 0.01f).toInt()
            bolt.setBounds(x, y, x + s, y + s)
            bolt.draw(c)
        }
    }

    companion object {
        /** The last value drawn, so a screen rebuild animates from there instead of from zero. */
        private var lastShown = 0f
    }
}

/**
 * The big round shutter (DESIGN-SPEC 3.14): 4 dp ring, 6 dp gap, a disc that shrinks while pressed and
 * springs back. Shows a countdown number on the disc during the self-timer.
 */
@SuppressLint("ClickableViewAccessibility")
class ShutterButton(context: Context, private val ui: Ui, private val ringColor: Int, private val discColor: Int) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(4f) }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = ui.font(500) }
    private var scale = 1f
    private var checkAlpha = 0f
    private val check = ui.drawable(R.drawable.ic_check, ui.s.onPrimary)

    /** Number drawn on the disc (self-timer), or null. */
    var countdown: Int? = null
        set(v) { field = v; invalidate() }

    init {
        isClickable = true
        isFocusable = true
        elevation = if (ui.s.amoled) 0f else ui.dpf(6f)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setOval(0, 0, view.width, view.height) }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        stateDescription = if (enabled) null else "Unavailable, glasses not connected"
        invalidate()
    }

    fun flashSuccess() {
        if (ui.reduceMotion) return
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600
            addUpdateListener { checkAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateScale(to: Float, ms: Long, overshoot: Boolean) {
        if (ui.reduceMotion) { scale = to; invalidate(); return }
        ValueAnimator.ofFloat(scale, to).apply {
            duration = ms
            interpolator = if (overshoot) OvershootInterpolator(2f) else ui.standard
            addUpdateListener { scale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { animateScale(0.88f, 100, false); ui.haptic(this, Haptic.PRESS) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(1f, 200, true)
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val on = isEnabled
        ring.color = if (on) ringColor else Ui.withAlpha(ui.s.onSurface, 97)
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        disc.color = if (on) discColor else Ui.withAlpha(ui.s.onSurface, 31)
        val inner = (r - ring.strokeWidth - ui.dpf(6f)) * scale
        c.drawCircle(cx, cy, inner, disc)
        countdown?.let {
            label.color = ui.s.onPrimary
            label.textSize = inner * 0.9f
            c.drawText(it.toString(), cx, cy - (label.ascent() + label.descent()) / 2f, label)
        }
        if (checkAlpha > 0f) {
            val s = ui.dpf(24f).toInt()
            check.alpha = (checkAlpha * 255).toInt()
            check.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            check.draw(c)
        }
    }
}

/** Record button (DESIGN-SPEC 3.14): a red dot that morphs into a rounded square while recording. */
class RecordButton(context: Context, private val ui: Ui, private val ringColor: Int) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(3f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f // 0 dot, 1 square

    var recording = false
        set(v) {
            if (field == v) return
            field = v
            stateDescription = if (v) "Recording" else null
            if (ui.reduceMotion) { t = if (v) 1f else 0f; invalidate(); return }
            ValueAnimator.ofFloat(t, if (v) 1f else 0f).apply {
                duration = 200
                interpolator = ui.standard
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.s.onSurface, 31)), null, ui.oval(android.graphics.Color.WHITE)
        )
    }

    override fun onDraw(c: Canvas) {
        val on = isEnabled
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        ring.color = if (!on) Ui.withAlpha(ui.s.onSurface, 97) else if (t > 0.5f) ui.s.error else ringColor
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        fill.color = if (on) ui.s.error else Ui.withAlpha(ui.s.onSurface, 61)
        val half = ui.dpf(12f) + (ui.dpf(11f) - ui.dpf(12f)) * t
        val corner = half + (ui.dpf(6f) - half) * t
        rect.set(cx - half, cy - half, cx + half, cy + half)
        c.drawRoundRect(rect, corner, corner, fill)
    }
}
