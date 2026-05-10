package org.jetbrains.skiko.awtfree

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

// libgio-2.0 bindings used by the awtfree windowing layer.

internal val gio: SymbolLookup =
    SymbolLookup.libraryLookup("libgio-2.0.so.0", ffmArena)

// Bypasses D-Bus app registration so the spike can launch even on
// session buses that already host the same application id.
internal const val G_APPLICATION_NON_UNIQUE: Int = 32

private val gApplicationRunHandle: MethodHandle = downcall(
    gio, "g_application_run",
    FunctionDescriptor.of(INT, ADDRESS, INT, ADDRESS),
)

// Runs the GApplication main loop until the last window closes. Pass
// argc=0 + argv=NULL when forwarding command-line args from the JVM
// is not desired (Phase 1B: argv parsing happens upstream).
internal fun gApplicationRun(application: MemorySegment): Int =
    gApplicationRunHandle.invoke(application, 0, MemorySegment.NULL) as Int
