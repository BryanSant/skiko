package org.jetbrains.skiko.spike.gtk4

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * GTK4 + FFM spike — feasibility proof for Phase 1B of the FFM evaluation.
 *
 * Loads libgtk-4.so.1 directly via FFM (no JNI), creates a GtkApplication,
 * registers an "activate" callback as an FFM upcall stub, and on activation
 * builds a GtkApplicationWindow and runs the GTK main loop.
 *
 * Proves three things:
 *   1. GTK4 is callable from JVM via FFM with no native shim.
 *   2. FFM upcall stubs work for GObject signal callbacks.
 *   3. On a Wayland session, the resulting process loads no libX11.
 *      Verify with: cat /proc/<pid>/maps | grep -E 'libX11|libwayland'
 */

private val linker: Linker = Linker.nativeLinker()
private val arena: Arena = Arena.global()

private val gtk: SymbolLookup = SymbolLookup.libraryLookup("libgtk-4.so.1", arena)
private val gobject: SymbolLookup = SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena)
private val gio: SymbolLookup = SymbolLookup.libraryLookup("libgio-2.0.so.0", arena)

private fun handle(
    lookup: SymbolLookup,
    name: String,
    descriptor: FunctionDescriptor,
): MethodHandle {
    val sym = lookup.find(name).orElseThrow {
        UnsatisfiedLinkError("Symbol not found: $name")
    }
    return linker.downcallHandle(sym, descriptor)
}

// gtk_application_new(const char* application_id, GApplicationFlags flags) -> GtkApplication*
private val gtkApplicationNew = handle(
    gtk, "gtk_application_new",
    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
)

// g_application_run(GApplication*, int argc, char** argv) -> int
private val gApplicationRun = handle(
    gio, "g_application_run",
    FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
    )
)

// g_object_unref(gpointer)
private val gObjectUnref = handle(
    gobject, "g_object_unref",
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
)

// g_signal_connect_data(instance, signal, c_handler, data, destroy_data, flags) -> gulong
private val gSignalConnectData = handle(
    gobject, "g_signal_connect_data",
    FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
    )
)

// gtk_application_window_new(GtkApplication*) -> GtkWidget*
private val gtkApplicationWindowNew = handle(
    gtk, "gtk_application_window_new",
    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
)

// gtk_window_set_title(GtkWindow*, const char* title)
private val gtkWindowSetTitle = handle(
    gtk, "gtk_window_set_title",
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
)

// gtk_window_set_default_size(GtkWindow*, int width, int height)
private val gtkWindowSetDefaultSize = handle(
    gtk, "gtk_window_set_default_size",
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
)

// gtk_window_present(GtkWindow*)
private val gtkWindowPresent = handle(
    gtk, "gtk_window_present",
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
)

private fun cstr(s: String): MemorySegment = arena.allocateFrom(s)

// "activate" signal handler. GApplication emits this once after
// g_application_run() registers the application. GTK4 forbids creating
// widgets before activation, so we build the UI here.
fun onActivate(app: MemorySegment, userData: MemorySegment) {
    println("[spike] activate fired; app=${app.address().toString(16)}")
    val window = gtkApplicationWindowNew.invoke(app) as MemorySegment
    gtkWindowSetTitle.invoke(window, cstr("Skiko GTK4 + FFM Spike"))
    gtkWindowSetDefaultSize.invoke(window, 640, 480)
    gtkWindowPresent.invoke(window)
    println("[spike] window presented at ${window.address().toString(16)}")
}

private val activateHandle: MethodHandle = MethodHandles.lookup().findStatic(
    Class.forName("org.jetbrains.skiko.spike.gtk4.MainKt"),
    "onActivate",
    MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java)
)

private val activateUpcallStub: MemorySegment = linker.upcallStub(
    activateHandle,
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    arena
)

fun main() {
    println("[spike] JVM: ${System.getProperty("java.version")}")
    println("[spike] XDG_SESSION_TYPE=${System.getenv("XDG_SESSION_TYPE")}")
    println("[spike] WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY")}")
    println("[spike] PID=${ProcessHandle.current().pid()}")
    println("[spike] To verify zero libX11: cat /proc/${ProcessHandle.current().pid()}/maps | grep -E 'libX11|libwayland'")

    val applicationId = cstr("org.jetbrains.skiko.spike.Gtk4Ffm")
    val gApplicationNonUnique = 32 // G_APPLICATION_NON_UNIQUE — bypass D-Bus app registration

    val app = gtkApplicationNew.invoke(applicationId, gApplicationNonUnique) as MemorySegment
    require(!app.equals(MemorySegment.NULL)) { "gtk_application_new returned NULL" }
    println("[spike] GtkApplication created at ${app.address().toString(16)}")

    val handlerId = gSignalConnectData.invoke(
        app,
        cstr("activate"),
        activateUpcallStub,
        MemorySegment.NULL, // user_data
        MemorySegment.NULL, // destroy_data
        0                   // G_CONNECT_DEFAULT
    ) as Long
    require(handlerId != 0L) { "g_signal_connect_data returned 0" }
    println("[spike] activate handler connected (id=$handlerId)")

    val exitCode = gApplicationRun.invoke(app, 0, MemorySegment.NULL) as Int
    println("[spike] g_application_run returned $exitCode")

    gObjectUnref.invoke(app)
    println("[spike] application unrefed; exit=$exitCode")
    if (exitCode != 0) System.exit(exitCode)
}
