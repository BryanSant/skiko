package org.jetbrains.skiko.awtfree

import java.lang.foreign.MemorySegment
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.Library
import org.jetbrains.skiko.OpenGLApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.makeGLContext
import org.jetbrains.skiko.makeGLRenderTarget

// Phase 1B task 8: Skia GL backend driver for a GtkGLArea.
//
// Bridges the GTK4 render-signal callback (windowing, FFM-bound) into
// Skia's GL backend (rendering, today still JNI to libskiko). The
// flow per frame:
//
//   render-signal fires
//     -> GTK has bound the area's FBO and made the GdkGLContext current
//     -> we query GL_DRAW_FRAMEBUFFER_BINDING for the FBO id
//     -> lazy-init the Skia DirectContext (first frame only)
//     -> rebuild BackendRenderTarget + Surface if size changed
//     -> hand the Canvas to the user
//     -> flush DirectContext, return TRUE
//
// The Skia DirectContext is bound to the GL context that was current
// when makeGLContext() was first called. GtkGLArea uses the same
// GdkGLContext for the lifetime of the widget, so one Skia context
// per SkiaGLArea is correct.
class SkiaGLArea(
    private val onDraw: (canvas: Canvas, width: Int, height: Int) -> Unit,
) {
    val area: MemorySegment = gtkGlAreaNew()

    init {
        Library.load() // libskiko — Skia's GL bindings still come through JNI.
        gtkGlAreaSetHasStencilBuffer(area, true)
        connectRenderSignal(area, ::render)
    }

    private var directContext: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var lastWidth = -1
    private var lastHeight = -1

    private fun render(area: MemorySegment, glContext: MemorySegment): Boolean {
        val width = gtkWidgetGetWidth(area)
        val height = gtkWidgetGetHeight(area)
        if (width <= 0 || height <= 0) return false

        val gl = OpenGLApi.instance
        val fbId = gl.glGetIntegerv(gl.GL_DRAW_FRAMEBUFFER_BINDING)

        val context = directContext ?: makeGLContext().also { directContext = it }

        if (width != lastWidth || height != lastHeight || surface == null) {
            surface?.close()
            renderTarget?.close()
            renderTarget = makeGLRenderTarget(
                width, height,
                /* sampleCnt = */ 0,
                /* stencilBits = */ 8,
                fbId,
                FramebufferFormat.GR_GL_RGBA8,
            )
            surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
            ) ?: throw RenderException(
                "Surface.makeFromBackendRenderTarget returned null (fb=$fbId, ${width}x$height)"
            )
            lastWidth = width
            lastHeight = height
        }

        val canvas = surface!!.canvas
        onDraw(canvas, width, height)
        context.flush()
        return true
    }

    fun queueRender() = gtkGlAreaQueueRender(area)

    fun dispose() {
        disconnectRenderSignal(area)
        surface?.close(); surface = null
        renderTarget?.close(); renderTarget = null
        directContext?.close(); directContext = null
    }
}
