package org.anthrobios.vespasim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.core.PlannedFrame
import kotlin.math.max

/** Planned payload RSSI only. Never represents RSSI measured by this phone or a receiver. */
class ScenarioChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frames: List<PlannedFrame> = emptyList()
    private var durationMs = 1L
    private var cursorMs: Long? = null
    private data class Geometry(val color: Int, val path: Path, val starts: List<Pair<Float, Float>>)
    private var paths: List<Geometry> = emptyList()
    private var haveRssi = false
    private val colors get() = Appearance.colors(context)
    private val trackColors get() = intArrayOf(colors.accent, colors.success, colors.warning, colors.danger)

    init { contentDescription = context.getString(R.string.main_chart_a11y) }

    fun setPlan(value: List<PlannedFrame>, duration: Long) {
        frames = value
        durationMs = max(1, duration)
        contentDescription = context.getString(R.string.main_chart_plan_a11y, value.size)
        rebuildPaths()
        invalidate()
    }

    fun setCursor(timeMs: Long?) { cursorMs = timeMs; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPaths()
    }

    /** Build geometry once per profile/size, not for every live cursor update. */
    private fun rebuildPaths() {
        val density = resources.displayMetrics.density
        val left = 42f * density
        val right = width - 14f * density
        val top = 25f * density
        val bottom = height - 48f * density
        if (right <= left || bottom <= top) { paths = emptyList(); return }
        val byTrack = linkedMapOf<String, Path>()
        val starts = mutableMapOf<String, MutableList<Pair<Float, Float>>>()
        val connected = mutableSetOf<String>()
        haveRssi = false
        frames.forEach { frame ->
            if (frame.payloadHex == null) { connected.clear(); return@forEach }
            val track = frame.trackId ?: return@forEach
            val path = byTrack.getOrPut(track) { Path() }
            val rssi = frame.reportedRssi
            if (rssi == null || rssi !in -127..20) { connected.remove(track); return@forEach }
            haveRssi = true
            val x = left + (frame.atMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * (right - left)
            val y = bottom - ((rssi + 130) / 150f).coerceIn(0f, 1f) * (bottom - top)
            if (track in connected) path.lineTo(x, y) else {
                path.moveTo(x, y)
                starts.getOrPut(track) { mutableListOf() }.add(x to y)
            }
            connected += track
        }
        paths = byTrack.entries.mapIndexed { index, (track, path) -> Geometry(trackColor(index), path, starts[track].orEmpty()) }
    }

    fun trackColor(index: Int): Int = if (index < trackColors.size) trackColors[index] else
        Color.HSVToColor(floatArrayOf((index * 137.5f) % 360f, .55f, if (Appearance.isDark(context)) .85f else .45f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 42f * density
        val right = width - 14f * density
        val top = 25f * density
        val bottom = height - 48f * density
        if (right <= left || bottom <= top) return
        val x: (Long) -> Float = { left + (it.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * (right - left) }
        val y: (Int) -> Float = { bottom - ((it + 130) / 150f).coerceIn(0f, 1f) * (bottom - top) }
        paint.typeface = Appearance.uiTypeface(context)
        paint.textSize = 10f * density
        paint.strokeWidth = density
        for (dbm in listOf(0, -40, -80, -120)) {
            paint.color = colors.rule
            canvas.drawLine(left, y(dbm), right, y(dbm), paint)
            paint.color = colors.muted
            canvas.drawText(dbm.toString(), 4f * density, y(dbm) + 3f * density, paint)
        }
        paint.color = colors.muted
        canvas.drawText("dBm*", 4f * density, 12f * density, paint)
        canvas.drawText(context.getString(R.string.main_chart_seconds, 0.0), left, height - 5f * density, paint)
        val durationText = context.getString(R.string.main_chart_seconds, durationMs / 1000.0)
        canvas.drawText(durationText, right - paint.measureText(durationText), height - 5f * density, paint)
        paths.forEach { (color, path, starts) ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f * density
            paint.color = color
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            starts.forEach { (x, y) -> canvas.drawCircle(x, y, 2f * density, paint) }
        }
        if (!haveRssi) {
            paint.color = colors.muted
            paint.textSize = 12f * density
            canvas.drawText(context.getString(R.string.main_chart_no_rssi), left, top + (bottom - top) / 2, paint)
        }
        val stride = max(1, frames.size / max(1, (right - left).toInt()))
        frames.forEachIndexed { index, frame ->
            if (index % stride == 0) {
                paint.color = if (frame.payloadHex == null) colors.warning else colors.accent
                canvas.drawRect(x(frame.atMs), bottom + 12f * density, max(x(frame.atMs + frame.durationMs), x(frame.atMs) + 1f), bottom + 19f * density, paint)
            }
        }
        cursorMs?.let {
            paint.color = colors.text
            paint.strokeWidth = 2f * density
            canvas.drawLine(x(it), top, x(it), bottom + 20f * density, paint)
        }
    }
}

