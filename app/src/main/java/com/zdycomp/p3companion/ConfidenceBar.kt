package com.zdycomp.p3companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * A 0 to 256 confidence bar with its number ("218/256") at the right end. UNKNOWN is drawn as a
 * hatched grey bar labelled "unknown", so it can never be mistaken for a low number.
 */
class ConfidenceBar(
    context: Context,
    private val ui: Ui,
    private val confidence: Int?,
    private val unknown: Boolean,
    private val fill: Int,
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.s.surfaceContainerHighest }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.s.outline
        strokeWidth = ui.dpf(1.5f)
        style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.s.onSurfaceVariant
        textSize = ui.dpf(12f) * ui.textScale
        typeface = ui.font(500)
        textAlign = Paint.Align.RIGHT
    }
    private val text = SupRevoltWire.confText(confidence, unknown)
    private val rect = RectF()
    private val clip = Path()

    init {
        contentDescription = if (unknown || confidence == null) "Confidence unknown"
        else "Confidence $confidence of 256, ${SupRevoltWire.percent(confidence)} percent"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(22))
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = label.measureText("256/256") + ui.dpf(10f)
        val h = ui.dpf(8f)
        val top = (height - h) / 2f
        val right = width - labelW
        val r = h / 2f
        rect.set(0f, top, right, top + h)
        canvas.drawRoundRect(rect, r, r, track)
        if (unknown || confidence == null) {
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            var x = -h
            val step = ui.dpf(6f)
            while (x < right + h) {
                canvas.drawLine(x, top + h, x + h, top, hatch)
                x += step
            }
            canvas.restore()
        } else {
            val frac = confidence.coerceIn(0, SupRevoltWire.CERTAIN) / SupRevoltWire.CERTAIN.toFloat()
            if (frac > 0f) {
                rect.set(0f, top, maxOf(h, right * frac), top + h)
                canvas.drawRoundRect(rect, r, r, bar)
            }
        }
        val baseline = height / 2f - (label.descent() + label.ascent()) / 2f
        canvas.drawText(text, width.toFloat(), baseline, label)
    }
}
