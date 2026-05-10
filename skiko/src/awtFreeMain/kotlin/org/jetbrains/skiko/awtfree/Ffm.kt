package org.jetbrains.skiko.awtfree

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// Phase 1B (docs/java25-ffm.md): shared FFM plumbing for the GTK4 /
// GObject / GIO bindings. Extracted from samples/Gtk4FfmSpike.

internal val linker: Linker = Linker.nativeLinker()

// Process-lifetime arena. Per-window arenas (Arena.ofConfined) live
// alongside this for the windowing layer; the symbol lookups and
// upcall stubs that exist for the JVM lifetime live here.
internal val ffmArena: Arena = Arena.global()

internal val ADDRESS = ValueLayout.ADDRESS
internal val INT = ValueLayout.JAVA_INT
internal val LONG = ValueLayout.JAVA_LONG

internal fun downcall(
    lookup: SymbolLookup,
    name: String,
    descriptor: FunctionDescriptor,
): MethodHandle {
    val sym = lookup.find(name).orElseThrow {
        UnsatisfiedLinkError("FFM symbol not found: $name")
    }
    return linker.downcallHandle(sym, descriptor)
}

internal fun cstr(s: String, arena: Arena = ffmArena): MemorySegment = arena.allocateFrom(s)
