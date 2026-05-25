package org.jetbrains.skiko.awtfree

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

// libgtk-4 bindings used by the awtfree windowing layer.

internal val gtk: SymbolLookup =
    SymbolLookup.libraryLookup("libgtk-4.so.1", ffmArena)

private val gtkApplicationNewHandle: MethodHandle = downcall(
    gtk, "gtk_application_new",
    FunctionDescriptor.of(ADDRESS, ADDRESS, INT),
)

internal fun gtkApplicationNew(applicationId: String, flags: Int): MemorySegment {
    val app = gtkApplicationNewHandle.invoke(cstr(applicationId), flags) as MemorySegment
    require(!app.equals(MemorySegment.NULL)) {
        "gtk_application_new(\"$applicationId\", $flags) returned NULL"
    }
    return app
}

private val gtkApplicationWindowNewHandle: MethodHandle = downcall(
    gtk, "gtk_application_window_new",
    FunctionDescriptor.of(ADDRESS, ADDRESS),
)

internal fun gtkApplicationWindowNew(application: MemorySegment): MemorySegment =
    gtkApplicationWindowNewHandle.invoke(application) as MemorySegment

private val gtkWindowSetTitleHandle: MethodHandle = downcall(
    gtk, "gtk_window_set_title",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
)

internal fun gtkWindowSetTitle(window: MemorySegment, title: String) {
    gtkWindowSetTitleHandle.invoke(window, cstr(title))
}

private val gtkWindowSetDefaultSizeHandle: MethodHandle = downcall(
    gtk, "gtk_window_set_default_size",
    FunctionDescriptor.ofVoid(ADDRESS, INT, INT),
)

internal fun gtkWindowSetDefaultSize(window: MemorySegment, width: Int, height: Int) {
    gtkWindowSetDefaultSizeHandle.invoke(window, width, height)
}

private val gtkWindowPresentHandle: MethodHandle = downcall(
    gtk, "gtk_window_present",
    FunctionDescriptor.ofVoid(ADDRESS),
)

internal fun gtkWindowPresent(window: MemorySegment) {
    gtkWindowPresentHandle.invoke(window)
}

// gtk_window_set_child(GtkWindow*, GtkWidget*) — mount a single child widget.
private val gtkWindowSetChildHandle: MethodHandle = downcall(
    gtk, "gtk_window_set_child",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
)

internal fun gtkWindowSetChild(window: MemorySegment, child: MemorySegment) {
    gtkWindowSetChildHandle.invoke(window, child)
}

// --- GtkGLArea: GTK4's OpenGL drawing surface widget ---
//
// Render flow per frame:
//   1. GTK schedules a draw cycle via the system frame clock.
//   2. GTK makes the widget's GdkGLContext current and binds an FBO
//      that the application is expected to draw into.
//   3. The "render" signal fires with the GtkGLArea + GdkGLContext.
//   4. The handler calls into Skia (BackendRenderTarget.makeGL +
//      Surface.makeFromBackendRenderTarget, see Phase 1B task 8).
//   5. Returning TRUE signals "I drew, don't fall back to default".

private val gtkGlAreaNewHandle: MethodHandle = downcall(
    gtk, "gtk_gl_area_new",
    FunctionDescriptor.of(ADDRESS),
)

internal fun gtkGlAreaNew(): MemorySegment =
    gtkGlAreaNewHandle.invoke() as MemorySegment

private val gtkGlAreaMakeCurrentHandle: MethodHandle = downcall(
    gtk, "gtk_gl_area_make_current",
    FunctionDescriptor.ofVoid(ADDRESS),
)

internal fun gtkGlAreaMakeCurrent(area: MemorySegment) {
    gtkGlAreaMakeCurrentHandle.invoke(area)
}

private val gtkGlAreaQueueRenderHandle: MethodHandle = downcall(
    gtk, "gtk_gl_area_queue_render",
    FunctionDescriptor.ofVoid(ADDRESS),
)

internal fun gtkGlAreaQueueRender(area: MemorySegment) {
    gtkGlAreaQueueRenderHandle.invoke(area)
}

// Skia's GL backend renders into an FBO that needs a stencil buffer
// for clipping/path rasterisation; depth is unused but cheap to keep.
// Defaults are off, so set them explicitly.

private val gtkGlAreaSetHasDepthBufferHandle: MethodHandle = downcall(
    gtk, "gtk_gl_area_set_has_depth_buffer",
    FunctionDescriptor.ofVoid(ADDRESS, INT),
)

internal fun gtkGlAreaSetHasDepthBuffer(area: MemorySegment, has: Boolean) {
    gtkGlAreaSetHasDepthBufferHandle.invoke(area, if (has) 1 else 0)
}

private val gtkGlAreaSetHasStencilBufferHandle: MethodHandle = downcall(
    gtk, "gtk_gl_area_set_has_stencil_buffer",
    FunctionDescriptor.ofVoid(ADDRESS, INT),
)

internal fun gtkGlAreaSetHasStencilBuffer(area: MemorySegment, has: Boolean) {
    gtkGlAreaSetHasStencilBufferHandle.invoke(area, if (has) 1 else 0)
}

private val gtkWidgetGetWidthHandle: MethodHandle = downcall(
    gtk, "gtk_widget_get_width",
    FunctionDescriptor.of(INT, ADDRESS),
)

internal fun gtkWidgetGetWidth(widget: MemorySegment): Int =
    gtkWidgetGetWidthHandle.invoke(widget) as Int

private val gtkWidgetGetHeightHandle: MethodHandle = downcall(
    gtk, "gtk_widget_get_height",
    FunctionDescriptor.of(INT, ADDRESS),
)

internal fun gtkWidgetGetHeight(widget: MemorySegment): Int =
    gtkWidgetGetHeightHandle.invoke(widget) as Int
