# GTK4 + FFM Spike

Feasibility proof for Phase 1B of the Java 25 + FFM evaluation
(`docs/java25-ffm.md`). A standalone JVM-only Kotlin program that:

1. Loads `libgtk-4.so.1`, `libgobject-2.0.so.0`, and `libgio-2.0.so.0`
   directly via FFM. No JNI, no native shim.
2. Creates a `GtkApplication`, registers an `activate` callback as an
   FFM upcall stub, and on activation builds a `GtkApplicationWindow`.
3. Runs the GTK main loop (`g_application_run`) until you close the
   window or kill the process.

## Prerequisites

- JDK 22+ (FFM stable). Tested on Eclipse Temurin 25.0.3.
- GTK4 runtime: `libgtk-4.so.1`. Fedora 44 ships GTK 4.22.4.
- A Wayland or X11 session.

## Run

```sh
./gradlew run
```

The build forces `GDK_BACKEND=wayland`. Override by editing
`build.gradle.kts` if you want to test the X11 backend.

## Verifying the dependency claims

Logging includes the JVM PID so you can inspect `/proc/<pid>/maps` and
`/proc/<pid>/fd/` while the window is open:

```sh
JPID=$(grep -oP 'PID=\K\d+' build/log)   # or read from console
grep -E 'libX11|libwayland|libxcb|libjawt|libawt|libfontmanager' /proc/$JPID/maps | awk '{print $NF}' | sort -u
```

## Findings (Fedora 44, GTK 4.22.4, Temurin 25.0.3, Wayland session)

### What worked

- **FFM downcall**: 8 GTK / GObject / GIO functions resolved and called
  successfully via `Linker.downcallHandle`. No native shim.
- **FFM upcall**: the `activate` signal handler executed correctly via
  `Linker.upcallStub` bound to a Kotlin top-level function. GObject's
  `g_signal_connect_data` accepted the `MemorySegment` returned by
  `upcallStub` as a `GCallback`.
- **Window presented**: `gtk_application_window_new` →
  `gtk_window_set_title` → `gtk_window_set_default_size` →
  `gtk_window_present` chain rendered a real Wayland-compositor window.
- **AWT bypass confirmed**: `/proc/<pid>/maps` contained **zero**
  `libjawt`, `libawt`, `libfontmanager`, or `libsplashscreen` entries.
  The JVM never initialized AWT.

### What didn't (the honest finding)

- **`libX11.so.6` is still loaded into the process** even with
  `GDK_BACKEND=wayland`. So are 8 `libxcb-*` libraries (`libxcb-dri3`,
  `libxcb-present`, `libxcb-render`, `libxcb-randr`, `libxcb-shm`,
  `libxcb-sync`, `libxcb-xfixes`, `libxcb`).
- However, **no X server socket connection** exists. Inspection of
  `/proc/<pid>/fd/` and `lsof -p <pid> -U` shows no socket pointing at
  `/tmp/.X11-unix/X0`. The Wayland display socket
  (`$XDG_RUNTIME_DIR/wayland-0`) is the active display connection.

### Interpretation

`libX11` and `libxcb-*` are *loaded* into the address space because:

1. **Mesa's GPU stack** (DRI3, GLX) transitively pulls them in for
   buffer-sharing primitives, even on Wayland.
2. **GTK4's GDK** is built with both X11 and Wayland backends in the
   same shared library. `GDK_BACKEND=wayland` selects which backend is
   *used*, but the X11 backend code is still mapped.

**Library mapping is not the same as a runtime dependency on an X
server.** The spike runs on a Wayland-only environment with no X server
present, because no code path inside it actually opens an X server
socket. But the binaries still need `libX11.so.6` to be installed on
disk to be linked at startup.

### To actually drop libX11 from the address space

Two paths, neither in scope for this spike:

1. **Custom GTK4 build** with `-Dx11-backend=false` (Meson option).
   Drops the GTK X11 backend code from `libgtk-4.so.1`. Still leaves
   the Mesa transitive dependency.
2. **Custom Mesa build** without X11 (`--without-x` equivalent) plus
   a GTK4 without X11. Gets to truly zero `libX11` mappings, but
   requires repackaging the Linux GPU stack for the deployment.

For Phase 1B's stated goal of "ship a Skiko app on a Wayland-only
system without X server present," the **stock distro packages
suffice** — the X server is never contacted, libX11 is just along for
the ride in the address space. For the stronger goal of "zero
`libX11.so` in the deployed image," custom GTK + Mesa builds are
needed.

## Files

- `src/main/kotlin/Main.kt` — the spike. ~150 lines.
- `build.gradle.kts` — Kotlin/JVM 2.3.20, JDK 25 toolchain,
  `--enable-native-access=ALL-UNNAMED`, `GDK_BACKEND=wayland`.
- `settings.gradle.kts`, `gradle.properties` — standard.
- `gradle/wrapper/` — Gradle 9.5.0.
