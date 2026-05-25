package org.jetbrains.skiko.awtfree

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

/**
 * Top-level entry point for an awtfree Skiko application. The same
 * code runs on every supported host OS: [SkikoApp] dispatches to the
 * appropriate native windowing backend at construction time based on
 * [org.jetbrains.skiko.hostOs].
 *
 * The [draw] lambda runs on the platform's UI thread inside the
 * native render callback. The framework has bound the offscreen
 * framebuffer and made the GL/Metal/D3D context current before
 * [draw] is invoked, so any Skia operation that needs a current
 * graphics context is safe.
 *
 * Typical use:
 * ```kotlin
 * fun main() {
 *     SkikoApp(
 *         applicationId = "com.example.MyApp",
 *         title = "Hello, Skiko",
 *     ) { canvas, width, height, isDark ->
 *         canvas.clear(if (isDark) 0xFF1E1E1E.toInt() else 0xFFFAFAFA.toInt())
 *         // ...your Skia drawing here...
 *     }.run()
 * }
 * ```
 *
 * Required JVM args: `--enable-native-access=ALL-UNNAMED`.
 *
 * Platform support (Phase 1, see `docs/java25-ffm.md`):
 * - **Linux**: GTK4 + EGL via FFM. Optional libadwaita chrome and
 *   system light/dark via AdwStyleManager. Implemented.
 * - **macOS**: AppKit + CAMetalLayer via FFM. Phase 1A — not yet
 *   implemented; constructing [SkikoApp] on macOS throws.
 * - **Windows**: Win32 + Direct3D 11 via FFM. Phase 1C — not yet
 *   implemented; constructing [SkikoApp] on Windows throws.
 *
 * @param applicationId Reverse-DNS application identifier, e.g.
 *   `"com.example.MyApp"`. On Linux this is passed to
 *   `gtk_application_new`; on macOS it will become the bundle id;
 *   on Windows the window class name.
 * @param title Window title shown in the chrome / titlebar.
 * @param defaultWidth Initial window width in logical pixels.
 * @param defaultHeight Initial window height in logical pixels.
 * @param draw Render callback. `isDark` reflects the system theme
 *   (true if the host reports dark mode); your drawing should pick
 *   colours accordingly.
 */
class SkikoApp(
    applicationId: String,
    title: String = "Skiko",
    defaultWidth: Int = 800,
    defaultHeight: Int = 600,
    draw: (canvas: Canvas, width: Int, height: Int, isDark: Boolean) -> Unit,
) {
    private val backend: SkikoAppBackend = when (hostOs) {
        OS.Linux -> LinuxGtkBackend(applicationId, title, defaultWidth, defaultHeight, draw)
        OS.MacOS -> error(
            "SkikoApp on macOS is Phase 1A in docs/java25-ffm.md — " +
                "AppKit + CAMetalLayer FFM bindings are not yet implemented."
        )
        OS.Windows -> error(
            "SkikoApp on Windows is Phase 1C in docs/java25-ffm.md — " +
                "Win32 + Direct3D 11 FFM bindings are not yet implemented."
        )
        else -> error("Unsupported host OS for SkikoApp: $hostOs")
    }

    /** Runs the platform main loop until the last window closes. Returns the exit code. */
    fun run(): Int = backend.run()
}

internal interface SkikoAppBackend {
    fun run(): Int
}
