package org.anthrobios.vespasim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.core.capture.CaptureObservation

/** Receiver RSSI only: an unavailable value creates a gap, never a signal peak. */
class CaptureChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var points: List<CaptureUiLogic.Point> = emptyList()
    private var cursorMs: Long? = null
    fun show(observations: List<CaptureObservation>, cursor: Long? = null) {
        points = CaptureUiLogic.timeline(observations)
        cursorMs = cursor
        contentDescription = context.getString(R.string.capture_chart_description, observations.size, observations.count { it.measuredRssi == null })
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = Appearance.colors(context)
        canvas.drawColor(colors.paper)
        val d = resources.displayMetrics.density
        val left = 44 * d; val top = 16 * d; val right = width - 12 * d; val bottom = height - 30 * d
        if (right <= left || bottom <= top) return
        val valid = points.mapNotNull { it.observation.measuredRssi }
        val low = minOf(-100, valid.minOrNull() ?: -100)
        val high = maxOf(-20, valid.maxOrNull() ?: -20)
        val duration = maxOf(1L, points.maxOfOrNull { it.offsetMs } ?: 1)
        fun x(ms: Long) = left + ms.toFloat() / duration * (right - left)
        fun y(rssi: Int) = bottom - (rssi - low).toFloat() / (high - low) * (bottom - top)
        paint.textSize = 11 * d; paint.strokeWidth = d; paint.typeface = Appearance.uiTypeface(context)
        for (i in 0..4) {
            val value = low + (high - low) * i / 4
            paint.color = colors.rule; canvas.drawLine(left, y(value), right, y(value), paint)
            paint.color = colors.muted; canvas.drawText("$value", 2 * d, y(value) + 4 * d, paint)
        }
        canvas.drawText("dBm", 2 * d, 11 * d, paint)
        canvas.drawText(context.getString(R.string.capture_chart_seconds, 0.0), left, height - 8 * d, paint)
        val endLabel = context.getString(R.string.capture_chart_seconds, duration / 1000.0)
        canvas.drawText(endLabel, right - paint.measureText(endLabel), height - 8 * d, paint)
        paint.color = colors.accent; paint.strokeWidth = 2 * d
        var previous: CaptureUiLogic.Point? = null
        points.forEach { point ->
            val value = point.observation.measuredRssi
            val before = previous
            if (value != null) {
                if (!point.startsSegment && before?.observation?.measuredRssi != null) {
                    canvas.drawLine(x(before.offsetMs), y(before.observation.measuredRssi!!), x(point.offsetMs), y(value), paint)
                }
                canvas.drawCircle(x(point.offsetMs), y(value), 2.4f * d, paint)
            }
            previous = point
        }
        cursorMs?.let { paint.color = colors.danger; canvas.drawLine(x(it.coerceIn(0, duration)), top, x(it.coerceIn(0, duration)), bottom, paint) }
        if (points.isEmpty()) {
            paint.color = colors.muted
            val message = context.getString(R.string.capture_chart_empty)
            val available = right - left
            val measured = paint.measureText(message)
            if (measured > available) paint.textSize *= available / measured
            canvas.drawText(message, left, top + 30 * d, paint)
        }
    }
}

