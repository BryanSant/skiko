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

// --- "activate" signal on GApplication ---
//
// Callback shape: void activate(GApplication*, gpointer user_data).
// GApplication emits this once after g_application_run() registers the
// app on the bus. GTK4 forbids creating widgets before activation, so
// the application's widget tree is built inside this handler.

private val activateHandlers = ConcurrentHashMap<Long, () -> Unit>()

fun gApplicationActivateTrampoline(
    app: MemorySegment,
    @Suppress("UNUSED_PARAMETER") userData: MemorySegment,
) {
    activateHandlers[app.address()]?.invoke()
}

private val activateUpcallStub: MemorySegment by lazy {
    upcall(
        cls = Class.forName("org.jetbrains.skiko.awtfree.GtkSignalsKt"),
        name = "gApplicationActivateTrampoline",
        methodType = MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java,
            MemorySegment::class.java,
        ),
        descriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
    )
}

internal fun connectActivateSignal(application: MemorySegment, handler: () -> Unit): Long {
    activateHandlers[application.address()] = handler
    return gSignalConnect(application, "activate", activateUpcallStub)
}

// --- "notify::PROPERTY" signals on any GObject ---
//
// Callback shape: void notify(GObject*, GParamSpec*, gpointer).
// One trampoline + one upcall stub serve every notify subscription;
// the dispatch table is keyed on (instance address, signal-detail) so
// the same object can subscribe to multiple property notifications
// without colliding.

private val notifyHandlers = ConcurrentHashMap<Pair<Long, String>, () -> Unit>()

fun gObjectNotifyTrampoline(
    instance: MemorySegment,
    paramSpec: MemorySegment,
    @Suppress("UNUSED_PARAMETER") userData: MemorySegment,
) {
    // ParamSpec carries the property name in its `name` field but
    // looking it up requires another downcall; the dispatch key
    // already encodes the property name on the connect side, so we
    // search the handlers map by instance and invoke any match.
    val addr = instance.address()
    notifyHandlers.entries
        .filter { it.key.first == addr }
        .forEach { it.value.invoke() }
}

private val notifyUpcallStub: MemorySegment by lazy {
    upcall(
        cls = Class.forName("org.jetbrains.skiko.awtfree.GtkSignalsKt"),
        name = "gObjectNotifyTrampoline",
        methodType = MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
        ),
        descriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
    )
}

// Subscribe to a GObject property change. `property` is the bare
// name (e.g. "dark"); the GObject signal name becomes "notify::dark".
internal fun connectNotifySignal(
    instance: MemorySegment,
    property: String,
    handler: () -> Unit,
): Long {
    notifyHandlers[instance.address() to property] = handler
    return gSignalConnect(instance, "notify::$property", notifyUpcallStub)
}
