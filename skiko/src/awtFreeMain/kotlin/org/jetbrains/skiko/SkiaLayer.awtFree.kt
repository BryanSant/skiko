package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry

// Stub. Phase 1B (docs/java25-ffm.md) replaces this with a real
// GTK4-backed implementation: GtkApplicationWindow + GtkGLArea +
// Skia GL backend, all bound via FFM. This file exists so the
// awtFree JVM target compiles; concrete behaviour follows in
// later commits.
actual open class SkiaLayer {
    actual var renderApi: GraphicsApi = GraphicsApi.OPENGL
    actual val contentScale: Float get() = 1.0f
    actual val pixelGeometry: PixelGeometry get() = PixelGeometry.UNKNOWN
    actual var fullscreen: Boolean = false
    actual val component: Any? get() = null
    actual var renderDelegate: SkikoRenderDelegate? = null

    actual fun attachTo(container: Any): Unit = TODO("Phase 1B: GTK4 attachTo")
    actual fun detach(): Unit = TODO("Phase 1B: GTK4 detach")
    actual fun needRender(throttledToVsync: Boolean): Unit = TODO("Phase 1B: schedule frame via GMainContext")

    @Deprecated(message = "Use needRender() instead", replaceWith = ReplaceWith("needRender()"))
    actual fun needRedraw(): Unit = needRender()

    internal actual fun draw(canvas: Canvas): Unit = TODO("Phase 1B: drive Skia GL render via GtkGLArea")
}
