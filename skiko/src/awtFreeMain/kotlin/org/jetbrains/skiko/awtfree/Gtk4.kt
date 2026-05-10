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
