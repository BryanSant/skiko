package org.jetbrains.skiko.awtfree

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

// libgobject-2.0 bindings used by the awtfree windowing layer.

internal val gobject: SymbolLookup =
    SymbolLookup.libraryLookup("libgobject-2.0.so.0", ffmArena)

private val gObjectUnrefHandle: MethodHandle = downcall(
    gobject, "g_object_unref",
    FunctionDescriptor.ofVoid(ADDRESS),
)

internal fun gObjectUnref(instance: MemorySegment) {
    gObjectUnrefHandle.invoke(instance)
}

private val gSignalConnectDataHandle: MethodHandle = downcall(
    gobject, "g_signal_connect_data",
    FunctionDescriptor.of(LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, INT),
)

// Connect a Kotlin function to a GObject signal. The lookup class /
// method-name pair must match a static @JvmStatic Kotlin function (or
// a top-level fun, looked up via its synthetic Kt class). Returns the
// handler id g_signal_connect_data hands back, or 0 if connection
// failed.
internal fun gSignalConnect(
    instance: MemorySegment,
    signal: String,
    callback: MemorySegment,
): Long {
    val handlerId = gSignalConnectDataHandle.invoke(
        instance,
        cstr(signal),
        callback,
        MemorySegment.NULL,
        MemorySegment.NULL,
        0,
    ) as Long
    require(handlerId != 0L) {
        "g_signal_connect_data($signal) returned 0 — handler not connected"
    }
    return handlerId
}

// Build an FFM upcall stub for a top-level Kotlin function. The
// callback's lifetime is tied to the supplied arena; pass the
// process-wide ffmArena for handlers that live for the JVM lifetime,
// or a confined arena scoped to the connection.
internal fun upcall(
    cls: Class<*>,
    name: String,
    methodType: MethodType,
    descriptor: FunctionDescriptor,
    arena: Arena = ffmArena,
): MemorySegment {
    val handle = MethodHandles.lookup().findStatic(cls, name, methodType)
    return linker.upcallStub(handle, descriptor, arena)
}
