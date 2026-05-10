package org.jetbrains.skiko.awtfree

import java.lang.foreign.MemorySegment
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect

// Phase 1B task 9 runnable: end-to-end proof that the FFM-bound GTK4
// windowing path can host a Skia GL render. Draws a couple of Skia
// primitives in a GtkApplicationWindow + GtkGLArea, no AWT involved.
//
// Run with:
//   ./gradlew :skiko:runAwtFreeDemo -Pskiko.awtfree.enabled=true
//
// Verify zero AWT mappings while the window is open:
//   grep -E 'libjawt|libawt|libfontmanager|libsplashscreen' \
//       /proc/<pid>/maps && echo "AWT LEAKED" || echo "clean"

private fun draw(canvas: Canvas, width: Int, height: Int) {
    canvas.clear(Color.WHITE)

    val w = width.toFloat()
    val h = height.toFloat()

    Paint().use { fill ->
        fill.color = 0xFF1F6FEB.toInt()
        fill.mode = PaintMode.FILL
        canvas.drawRect(Rect(w * 0.10f, h * 0.20f, w * 0.45f, h * 0.80f), fill)

        fill.color = 0xFFE5534B.toInt()
        canvas.drawCircle(w * 0.72f, h * 0.50f, minOf(w, h) * 0.20f, fill)
    }

    Paint().use { stroke ->
        stroke.color = 0xFF24292F.toInt()
        stroke.mode = PaintMode.STROKE
        stroke.strokeWidth = 4f
        canvas.drawRect(Rect(2f, 2f, w - 2f, h - 2f), stroke)
    }
}

class GtkSkiaApp(applicationId: String) {
    // AdwApplication subclasses GtkApplication; handles flow through
    // gApplicationRun / connectActivateSignal unchanged.
    private val useAdwaita = isAdwaitaAvailable
    private val app: MemorySegment =
        if (useAdwaita) adwApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)
        else gtkApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)

    init {
        connectActivateSignal(app, ::onActivate)
    }

    private fun onActivate() {
        val title = if (useAdwaita) "Skiko awtfree — Adwaita" else "Skiko awtfree — GTK4"
        val skia = SkiaGLArea(::draw)

        val window = if (useAdwaita) {
            // AdwApplicationWindow owns its chrome; mount via set_content.
            val w = adwApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, 800, 600)
            adwApplicationWindowSetContent(w, skia.area)
            w
        } else {
            val w = gtkApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, 800, 600)
            gtkWindowSetChild(w, skia.area)
            w
        }

        gtkWindowPresent(window)
    }

    fun run(): Int = gApplicationRun(app)
}

fun main() {
    val pid = ProcessHandle.current().pid()
    println("[awtfree-demo] JVM ${System.getProperty("java.version")}, PID=$pid")
    println("[awtfree-demo] chrome: ${if (isAdwaitaAvailable) "libadwaita-1" else "gtk4 baseline"}")
    println("[awtfree-demo] verify clean: grep -E 'libjawt|libawt|libfontmanager' /proc/$pid/maps")
    val exitCode = GtkSkiaApp("org.jetbrains.skiko.awtfree.Demo").run()
    if (exitCode != 0) System.exit(exitCode)
}
