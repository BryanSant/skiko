package org.jetbrains.skiko.awtfree

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

// Optional libadwaita-1 integration.
//
// libadwaita is GNOME's Adwaita-styled widget library on top of GTK4.
// Without it, our GtkApplicationWindow renders with GTK4's default
// header chrome, which looks visibly out of place on a GNOME / Adwaita
// desktop. With it, AdwApplicationWindow gets matching window
// decorations and the Adwaita CSS provider for free.
//
// Skiko's stance is "GTK4-only baseline": libadwaita is not a build-
// time dependency. We probe libadwaita-1.so.0 at runtime via FFM —
// SymbolLookup.libraryLookup throws IllegalArgumentException when the
// SONAME cannot be resolved, which is how we detect absence. When
// present, callers swap in Adw* constructors; when absent, the GTK4
// path runs unchanged.
//
// Selection precedence (most explicit wins):
//   -Dskiko.linux.adwaita=on   — require libadwaita; error if missing.
//   -Dskiko.linux.adwaita=off  — never load libadwaita.
//   -Dskiko.linux.adwaita=auto — load if present, fall back if not
//                                 (default).

private val mode: String =
    (System.getProperty("skiko.linux.adwaita") ?: "auto").lowercase()

private val adwaitaLookup: SymbolLookup? = run {
    if (mode == "off") return@run null
    try {
        SymbolLookup.libraryLookup("libadwaita-1.so.0", ffmArena)
    } catch (e: IllegalArgumentException) {
        if (mode == "on") {
            throw IllegalStateException(
                "skiko.linux.adwaita=on but libadwaita-1.so.0 is not on the runtime library path",
                e,
            )
        }
        null
    }
}

internal val isAdwaitaAvailable: Boolean
    get() = adwaitaLookup != null

private val adw: SymbolLookup
    get() = adwaitaLookup
        ?: error("Adwaita symbols requested but libadwaita-1.so.0 was not loaded")

// adw_application_new(application_id, flags) -> AdwApplication*
//
// AdwApplication subclasses GtkApplication, so the resulting handle
// can be passed to gApplicationRun, gObjectUnref, and any other
// GApplication-shaped binding without further wrapping.
private val adwApplicationNewHandle: MethodHandle by lazy {
    downcall(
        adw, "adw_application_new",
        FunctionDescriptor.of(ADDRESS, ADDRESS, INT),
    )
}

internal fun adwApplicationNew(applicationId: String, flags: Int): MemorySegment {
    val app = adwApplicationNewHandle.invoke(cstr(applicationId), flags) as MemorySegment
    require(!app.equals(MemorySegment.NULL)) {
        "adw_application_new(\"$applicationId\", $flags) returned NULL"
    }
    return app
}

// adw_application_window_new(app) -> AdwApplicationWindow*
//
// AdwApplicationWindow subclasses GtkApplicationWindow, so the same
// gtk_window_* setters (set_title, set_default_size, present) work
// against the returned handle.
private val adwApplicationWindowNewHandle: MethodHandle by lazy {
    downcall(
        adw, "adw_application_window_new",
        FunctionDescriptor.of(ADDRESS, ADDRESS),
    )
}

internal fun adwApplicationWindowNew(application: MemorySegment): MemorySegment =
    adwApplicationWindowNewHandle.invoke(application) as MemorySegment

// adw_application_window_set_content(window, content)
//
// AdwApplicationWindow expects content via this setter rather than
// gtk_window_set_child — internally the Adw window owns a wrapper
// widget that hosts the chrome and the content area.
private val adwApplicationWindowSetContentHandle: MethodHandle by lazy {
    downcall(
        adw, "adw_application_window_set_content",
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
    )
}

internal fun adwApplicationWindowSetContent(window: MemorySegment, content: MemorySegment) {
    adwApplicationWindowSetContentHandle.invoke(window, content)
}
