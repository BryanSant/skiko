package org.jetbrains.skiko.awtfree

import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect

// Phase 1B reference consumer. Shape mirrors what a downstream
// awtfree app should look like — only public symbols from the
// library are used. See GtkSkiaApp.kt for the API surface.
//
// Run with:
//   ./gradlew :skiko:runAwtFreeDemo -Pskiko.awtfree.enabled=true
// Override Adwaita selection:
//   -Dskiko.linux.adwaita=on|off|auto

private data class Palette(
    val background: Int,
    val accentBlue: Int,
    val accentRed: Int,
    val border: Int,
)

private val LIGHT = Palette(
    background = 0xFFFAFAFA.toInt(),
    accentBlue = 0xFF1F6FEB.toInt(),
    accentRed = 0xFFE5534B.toInt(),
    border = 0xFF24292F.toInt(),
)

private val DARK = Palette(
    background = 0xFF1E1E1E.toInt(),
    accentBlue = 0xFF58A6FF.toInt(),
    accentRed = 0xFFFF7B72.toInt(),
    border = 0xFFC9D1D9.toInt(),
)

fun main() {
    val pid = ProcessHandle.current().pid()
    println("[awtfree-demo] JVM ${System.getProperty("java.version")}, PID=$pid")
    println("[awtfree-demo] chrome: ${if (isAdwaitaAvailable) "libadwaita-1" else "gtk4 baseline"}")
    println("[awtfree-demo] verify clean: grep -E 'libjawt|libawt|libfontmanager' /proc/$pid/maps")

    val title = if (isAdwaitaAvailable) "Skiko awtfree — Adwaita" else "Skiko awtfree — GTK4"
    val exitCode = GtkSkiaApp(
        applicationId = "org.jetbrains.skiko.awtfree.Demo",
        title = title,
    ) { canvas, width, height, isDark ->
        val p = if (isDark) DARK else LIGHT
        canvas.clear(p.background)

        val w = width.toFloat()
        val h = height.toFloat()

        Paint().use { fill ->
            fill.color = p.accentBlue
            fill.mode = PaintMode.FILL
            canvas.drawRect(Rect(w * 0.10f, h * 0.20f, w * 0.45f, h * 0.80f), fill)

            fill.color = p.accentRed
            canvas.drawCircle(w * 0.72f, h * 0.50f, minOf(w, h) * 0.20f, fill)
        }

        Paint().use { stroke ->
            stroke.color = p.border
            stroke.mode = PaintMode.STROKE
            stroke.strokeWidth = 4f
            canvas.drawRect(Rect(2f, 2f, w - 2f, h - 2f), stroke)
        }
    }.run()

    if (exitCode != 0) System.exit(exitCode)
}
