package org.jetbrains.skiko.awtfree

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

// Render-signal dispatcher for GtkGLArea.
//
// GTK4's "render" signal callback signature is
//   gboolean render(GtkGLArea*, GdkGLContext*, gpointer user_data)
// Returning TRUE means "I drew, don't fall back to default rendering."
//
// FFM upcall stubs need a static Kotlin function to resolve. Per-area
// state lives in a Long-keyed dispatch map: when the C callback fires,
// we look up the GtkGLArea pointer in the map and invoke the Kotlin
// lambda registered for it. user_data is unused — letting Kotlin own
// the lifetime of the lambda is simpler than handing GTK a heap-
// allocated context to free via destroy_data.

private val renderHandlers = ConcurrentHashMap<Long, RenderHandler>()

internal fun interface RenderHandler {
    // Receives the GtkGLArea pointer and the active GdkGLContext.
    // Return true if the area was drawn.
    fun render(area: MemorySegment, glContext: MemorySegment): Boolean
}

// Top-level functions are already static at the JVM level (lifted
// into the synthetic GtkSignalsKt file class). The class lookup below
// resolves it through that synthetic class.
fun gtkGlAreaRenderTrampoline(
    area: MemorySegment,
    glContext: MemorySegment,
    @Suppress("UNUSED_PARAMETER") userData: MemorySegment,
): Int {
    val handler = renderHandlers[area.address()] ?: return 0
    return if (handler.render(area, glContext)) 1 else 0
}

private val renderUpcallStub: MemorySegment by lazy {
    upcall(
        cls = Class.forName("org.jetbrains.skiko.awtfree.GtkSignalsKt"),
        name = "gtkGlAreaRenderTrampoline",
        methodType = MethodType.methodType(
            Int::class.javaPrimitiveType,
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
        ),
        descriptor = FunctionDescriptor.of(INT, ADDRESS, ADDRESS, ADDRESS),
    )
}

// Wire a render handler to a GtkGLArea. Idempotent per area: a second
// call replaces the previous handler.
internal fun connectRenderSignal(area: MemorySegment, handler: RenderHandler): Long {
    renderHandlers[area.address()] = handler
    return gSignalConnect(area, "render", renderUpcallStub)
}

internal fun disconnectRenderSignal(area: MemorySegment) {
    renderHandlers.remove(area.address())
}
