package org.jetbrains.skiko.awtfree

import java.lang.foreign.MemorySegment
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect

// Phase 1B task 9 runnable: end-to-end proof that the FFM-bound GTK4
// windowing path can host a Skia GL render. When libadwaita-1 is on
// the host, the demo also tracks the system light/dark preference via
// AdwStyleManager and recolours the Skia content to match.
//
// Run with:
//   ./gradlew :skiko:runAwtFreeDemo -Pskiko.awtfree.enabled=true
//
// Verify zero AWT mappings while the window is open:
//   grep -E 'libjawt|libawt|libfontmanager|libsplashscreen' \
//       /proc/<pid>/maps && echo "AWT LEAKED" || echo "clean"

// Theme-aware palette. Adwaita-style colours: light/dark surface,
// muted-blue accent, GNOME-red secondary.
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

class GtkSkiaApp(applicationId: String) {
    // AdwApplication subclasses GtkApplication; handles flow through
    // gApplicationRun / connectActivateSignal unchanged.
    private val useAdwaita = isAdwaitaAvailable
    private val app: MemorySegment =
        if (useAdwaita) adwApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)
        else gtkApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)

    // Style manager and initial palette are resolved on activate —
    // adw_style_manager_get_default() ends up calling
    // gdk_display_manager_get(), which asserts gtk_init() has already
    // run. AdwApplication's activate flow is what triggers gtk_init,
    // so we can't touch the style manager from the constructor or
    // from main().
    private var styleManager: MemorySegment? = null

    @Volatile
    private var palette: Palette = LIGHT

    private var skia: SkiaGLArea? = null

    init {
        connectActivateSignal(app, ::onActivate)
    }

    private fun onActivate() {
        val title = if (useAdwaita) "Skiko awtfree — Adwaita" else "Skiko awtfree — GTK4"
        val area = SkiaGLArea(::draw).also { skia = it }

        val window = if (useAdwaita) {
            // AdwApplicationWindow doesn't render a header bar by
            // itself — Adwaita's pattern is to mount an AdwToolbarView
            // with an AdwHeaderBar as a top bar above the content.
            val w = adwApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, 800, 600)

            val toolbarView = adwToolbarViewNew()
            adwToolbarViewAddTopBar(toolbarView, adwHeaderBarNew())
            adwToolbarViewSetContent(toolbarView, area.area)
            adwApplicationWindowSetContent(w, toolbarView)

            // Track system light/dark via AdwStyleManager. Resolved
            // here, not in the constructor — see field comment.
            val mgr = adwStyleManagerGetDefault().also { styleManager = it }
            palette = if (adwStyleManagerGetDark(mgr)) DARK else LIGHT
            // The "dark" property notifies whenever the resolved
            // theme changes (system pref flip, manual toggle, etc.).
            connectNotifySignal(mgr, "dark") {
                palette = if (adwStyleManagerGetDark(mgr)) DARK else LIGHT
                skia?.queueRender()
            }
            w
        } else {
            val w = gtkApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, 800, 600)
            gtkWindowSetChild(w, area.area)
            w
        }

        gtkWindowPresent(window)
    }

    private fun draw(canvas: Canvas, width: Int, height: Int) {
        val p = palette
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
