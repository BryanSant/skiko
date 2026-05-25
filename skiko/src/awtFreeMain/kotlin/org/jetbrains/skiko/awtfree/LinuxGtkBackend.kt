package org.jetbrains.skiko.awtfree

import java.lang.foreign.MemorySegment
import org.jetbrains.skia.Canvas

// Linux backend for SkikoApp: wires up an FFM-bound GTK4 application,
// a single window, and a Skia GL render surface. When libadwaita-1 is
// on the host the window gets matching Adwaita chrome (header bar
// with working window controls) and the system light/dark preference
// is tracked via AdwStyleManager; otherwise it falls back to
// GTK4-baseline chrome and reports isDark = false. Selection is
// controlled by -Dskiko.linux.adwaita=auto|on|off (default auto).
//
// Internal — consumers go through SkikoApp, which dispatches to this
// backend (or future LinuxX11Backend / MacAppKitBackend / WinD3DBackend)
// based on hostOs.
internal class LinuxGtkBackend(
    private val applicationId: String,
    private val title: String,
    private val defaultWidth: Int,
    private val defaultHeight: Int,
    private val draw: (canvas: Canvas, width: Int, height: Int, isDark: Boolean) -> Unit,
) : SkikoAppBackend {
    // AdwApplication subclasses GtkApplication; GApplication-shaped
    // bindings (run, signal connect) work against either handle.
    private val useAdwaita = isAdwaitaAvailable
    private val app: MemorySegment =
        if (useAdwaita) adwApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)
        else gtkApplicationNew(applicationId, G_APPLICATION_NON_UNIQUE)

    // Style manager and the initial dark state are resolved on
    // activate, not in the constructor — adw_style_manager_get_default
    // calls gdk_display_manager_get internally and asserts gtk_init
    // has already run. AdwApplication's activate flow is what triggers
    // gtk_init.
    private var styleManager: MemorySegment? = null

    @Volatile
    private var isDark: Boolean = false

    private var skia: SkiaGLArea? = null

    init {
        connectActivateSignal(app, ::onActivate)
    }

    private fun onActivate() {
        val area = SkiaGLArea { canvas, w, h -> draw(canvas, w, h, isDark) }
            .also { skia = it }

        val window = if (useAdwaita) {
            // AdwApplicationWindow doesn't render a header bar by
            // itself — the Adwaita pattern is to mount an
            // AdwToolbarView with an AdwHeaderBar above the content.
            val w = adwApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, defaultWidth, defaultHeight)

            val toolbarView = adwToolbarViewNew()
            adwToolbarViewAddTopBar(toolbarView, adwHeaderBarNew())
            adwToolbarViewSetContent(toolbarView, area.area)
            adwApplicationWindowSetContent(w, toolbarView)

            val mgr = adwStyleManagerGetDefault().also { styleManager = it }
            isDark = adwStyleManagerGetDark(mgr)
            // Repaint when the system theme flips (xdg-desktop-portal
            // appearance signal under the hood).
            connectNotifySignal(mgr, "dark") {
                isDark = adwStyleManagerGetDark(mgr)
                skia?.queueRender()
            }
            w
        } else {
            val w = gtkApplicationWindowNew(app)
            gtkWindowSetTitle(w, title)
            gtkWindowSetDefaultSize(w, defaultWidth, defaultHeight)
            gtkWindowSetChild(w, area.area)
            w
        }
        gtkWindowPresent(window)
    }

    override fun run(): Int = gApplicationRun(app)
}
