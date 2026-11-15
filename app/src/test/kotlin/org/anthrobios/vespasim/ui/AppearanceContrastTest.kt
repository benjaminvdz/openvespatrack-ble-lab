package org.anthrobios.vespasim.ui

import org.junit.Test
import kotlin.math.pow
import kotlin.test.assertTrue

class AppearanceContrastTest {
    @Test fun bothThemesKeepReadingAndSemanticTextAccessible() {
        for (dark in listOf(false, true)) {
            val c = Appearance.palette(dark)
            val pairs = listOf(
                "body/page" to (c.text to c.page),
                "body/surface" to (c.text to c.surface),
                "body/paper" to (c.text to c.paper),
                "muted/page" to (c.muted to c.page),
                "muted/surface" to (c.muted to c.surface),
                "action" to (c.onAccent to c.accent),
                "link" to (c.accent to c.surface),
                "selected text" to (c.text to c.selected),
                "success" to (c.success to c.successBg),
                "warning" to (c.warning to c.warningBg),
                "danger" to (c.danger to c.dangerBg),
            )
            for ((label, pair) in pairs) assertTrue(contrast(pair.first, pair.second) >= 4.5,
                "$label must have 4.5:1 contrast in ${if (dark) "dark" else "light"} mode")
            assertTrue(contrast(c.border, c.surface) >= 3.0, "Functional boundaries need 3:1 contrast")
            assertTrue(contrast(c.focus, c.surface) >= 3.0, "Keyboard focus must remain visible")
        }
    }

    private fun luminance(color: Int): Double {
        fun linear(channel: Int): Double = (channel / 255.0).let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((color ushr 16) and 255) +
            0.7152 * linear((color ushr 8) and 255) + 0.0722 * linear(color and 255)
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}

