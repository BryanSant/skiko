# Java 25 + FFM in Skiko: Evaluation

## Thesis

**FFM's value to Skiko is being the transport mechanism for escaping AWT,
JAWT, and (on Linux) X11.** The internal JNI → FFM migration that comes
along for the ride is incidental cleanup, not the headline.

We considered FFM as a JNI-replacement project and concluded that on its
own it is hard to justify: it drops no real dependencies, and on Skiko's
hot path it is measurably slower than JNI (see "Measured Performance"
below). The reason to invest in FFM is that it is the only available
mechanism in JDK 25 and 26 to bypass AWT entirely and ship a
**Wayland-native, X11-free Java desktop application**, with secondary but
real benefits on Windows.

## TL;DR

- **Real dependencies dropped (the reason to do this)**: `libX11.so.6`
  and the X server connection on Linux; JAWT (`libjawt.so` and the
  `jawt_md.h` C ABI) on all platforms; the AWT toolkit init chain
  (`sun.awt.X11.XToolkit`, font config probe, Java2D pipeline); AWT's
  broken-on-Wayland clipboard/DnD/IME stack. See "Dependency Story" below.
- **Why FFM specifically**: WLToolkit (Project Wakefield's Wayland-native
  AWT replacement) is experimental in JDK 21+, incomplete in JDK 25/26,
  with no production timeline. There is no AWT-internal path to a
  Wayland-native Java desktop. FFM is the only mechanism that works in
  the JDK we're targeting.
- **Performance is not a reason**: measured FFM call overhead is ~9%
  *worse* than JNI on `Canvas.drawRect`. We do this for dependency
  elimination, not speed.
- **Effort**: multi-quarter. Biggest single milestone is Linux + GTK4
  native windowing (~12–16 weeks). macOS path is lowest-risk because
  Skiko's existing Kotlin/Native target is a working template.
- **Compose Multiplatform** must adopt a non-`JComponent` `SkiaLayer` for
  this to be useful to Compose Desktop apps. That's a parallel
  multi-quarter effort coordinated with JetBrains.
- **The internal JNI → FFM cleanup** (replacing the ~1,026 `JNIEXPORT`
  symbols in `skiko/src/jvmMain/cpp/` with FFM bindings) becomes
  attractive *after* Phase 1 ships, because the JAWT shim that today
  blocks a clean FFM port of the JVM target is gone. It falls out as
  consequence, not as a separate initiative.

## The Dependency Story

### Linux (the headline)

| Today (AWT path) | After Phase 1 (FFM + GTK4) |
|---|---|
| `libX11.so.6`, `libXext`, `libXrender`, `libXi`, `libXtst` | **None** when `GDK_BACKEND=wayland` |
| `libjawt.so` (Java AWT Native Interface) | **None** |
| `sun.awt.X11.XToolkit` init, X server connection on startup | **None** — GTK4 owns the window directly |
| `sun.java2d.*` pipeline (Java2D OpenGL, software, headless probes) | **None** — Skia is the only renderer |
| AWT clipboard / DnD / IME — broken on Wayland | GTK4 native — works on Wayland |
| AWT fractional scaling — broken on Wayland in JDK 25 | GTK4 native — works |
| AT-SPI accessibility through AWT — broken on Wayland | GTK4 native AT-SPI — works |
| 100–300 ms AWT startup cost (X connection, font init, L&F) | Skipped |

Headline: **the deployable image has zero `libX11` runtime dependency.**
Skiko-based Java apps run on Wayland-only environments (Fedora
Silverblue, Steam Deck Desktop mode, GNOME-on-Wayland with X11 disabled,
etc.) without XWayland fallback.

### Windows

| Today | After Phase 1 |
|---|---|
| JAWT shim between Skiko and DXGI/D3D11 (`awtMain/cpp/windows/directXRedrawer.cc` exists specifically to climb out of AWT to reach D3D) | Direct Win32 + DXGI; no shim |
| AWT per-monitor DPI v2 (chronic bugs around moving windows between monitors) | Native Win32/DXGI HiDPI — handled correctly out of the box |
| Limited dark mode / immersive titlebar via AWT | Native Win32 (`DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)`, custom titlebar via `WM_NCCALCSIZE`) |
| AWT EDT awkwardly coexists with render thread | Render thread owns the message pump |

Smaller dependency story than Linux, but the JAWT shim removal and the
clean DPI handling are real wins.

### macOS

| Today | After Phase 1 |
|---|---|
| JAWT `NSView` extraction shim in `awtMain/cpp/macos/` | Direct AppKit ownership; same path as Skiko's existing Kotlin/Native macOS target |
| Java AWT main-thread coordination | Pure AppKit main-thread coordination (same constraint, fewer layers) |

macOS already has a clean Metal integration through JAWT today; the win
here is removing the JAWT layer, not eliminating an external dependency
(no `libX11` equivalent exists on macOS).

## Why FFM, and Why Now

### What we considered

| Option | Outcome |
|---|---|
| Stay on AWT/JAWT, accept X11 dep | Fails the goal. Apps still need libX11; Wayland fractional scaling broken; IME broken; accessibility broken. |
| Wait for WLToolkit (Project Wakefield) | Indefinite. Experimental in JDK 21+ via `-Dawt.toolkit.name=WLToolkit`. Missing IME, incomplete DPI, no production timeline through JDK 26. Not a path we can plan against. |
| Build native windowing in JNI | Works, but reintroduces and *expands* the JNI surface we already want to retire. Defeats half the migration. |
| **Build native windowing in FFM** | **Clean. Available in JDK 22+ (stable), JDK 25 LTS. No new C/JNI surface. Idiomatic Java/Kotlin.** |

FFM is the only mechanism that lets us bypass AWT *and* avoid growing
the C/JNI footprint. That's the entire reason for the choice.

### What FFM does for us mechanically

- **Downcalls**: `Linker.downcallHandle(MemorySegment, FunctionDescriptor)`
  binds C functions from `libgtk-4.so.1`, `libobjc.dylib`, `user32.dll`,
  etc. directly to Java `MethodHandle`s. No JNI thunks to write.
- **Upcalls**: `Linker.upcallStub(...)` lets GTK4 / AppKit / Win32 call
  back into Kotlin (signal handlers, window-proc callbacks, run-loop
  callbacks) without `NewGlobalRef` or `CallVoidMethod`.
- **Memory**: `MemorySegment` with `Arena` lifetime makes ownership
  explicit. Critical for tracking native window/view/widget handles
  whose lifetime is scoped to the window, not the JVM heap.
- **No new runtime deps**: FFM is part of the JDK starting in 22. Free
  once we're on JDK 22+.

## Phase 1: AWT/JAWT/X11 Elimination

### Architecture

Skiko's `Redrawer` and `ContextHandler` interfaces in `commonMain` are
toolkit-neutral. Skiko's existing **macOS Kotlin/Native target already
proves the "Skiko owns the window" pattern**:
`skiko/src/macosMain/kotlin/.../redrawer/MetalRedrawer.macos.kt` creates
a `CAMetalLayer`, attaches it to an `NSView`, and drives a Metal command
queue — no AWT involved.

Phase 1 builds three FFM-based JVM implementations of these same
interfaces and adds a new `SkiaLayer` actual that does **not** extend
`JComponent`. Working name for the new source set: `awtFreeMain`. It
coexists with the existing `awtMain` so existing Compose Desktop apps
keep working unchanged until they migrate.

### macOS — AppKit + CAMetalLayer (lowest risk; ~6–10 weeks)

- **Bind path**: FFM → `libobjc.dylib::objc_msgSend` (and friends:
  `objc_getClass`, `sel_registerName`, `class_getInstanceMethod`).
  All AppKit interaction is `objc_msgSend(receiver, selector, args...)`.
- **What we wrap**: `NSApplication`, `NSWindow`, `NSView`, `CAMetalLayer`,
  `MTLDevice`, `MTLCommandQueue`, `NSOperationQueue`, `NSNotificationCenter`.
- **Reference implementation**: port `MetalRedrawer.macos.kt` line-for-line,
  replacing K/N `objcPtr()` interop with FFM `MethodHandle` calls. Build
  a small `class ObjCClass` / `class ObjCObject` abstraction over
  `objc_msgSend` to keep call sites readable.
- **Threading**: AppKit is main-thread-only. Java's main thread plus
  `-XstartOnFirstThread` is the established workaround; same constraint
  as today's AWT path on macOS.

### Linux — GTK4 only, no libadwaita (highest risk; ~12–16 weeks)

**This is the X11-elimination milestone — the headline of the entire
project.**

- **Bind path**: FFM → `libgtk-4.so.1`, `libgobject-2.0.so.0`,
  `libgio-2.0.so.0`, `libgdk-4.so.1`. **No libadwaita.** GObject's C ABI
  is FFM-friendly: every method is a flat C function (`gtk_application_new`,
  `gtk_window_new`, `gtk_gl_area_new`, `g_signal_connect`,
  `g_application_run`).
- **What we wrap**:
  - `GtkApplication` / `GtkApplicationWindow` (the GTK4-native
    equivalents of libadwaita's `AdwApplication` / `AdwApplicationWindow`).
  - `GtkHeaderBar` for client-side window decorations, set as the
    window's titlebar via `gtk_window_set_titlebar`. No libadwaita
    needed for the header bar.
  - **Light/dark theme detection** via two complementary sources:
    1. `GtkSettings::gtk-application-prefer-dark-theme` for the
       application's own preference (`g_object_get`/`g_object_set` on
       the default `GtkSettings`).
    2. **xdg-desktop-portal**'s `org.freedesktop.appearance::color-scheme`
       D-Bus signal for the *system* preference. Bind `libgio-2.0`'s
       `g_dbus_proxy_*` functions and watch the `Settings.Read` /
       `SettingChanged` signals. This is the modern,
       desktop-environment-agnostic mechanism (works on GNOME, KDE,
       etc.) and is what libadwaita's `AdwStyleManager` does under the
       hood.
  - `GtkGLArea` for the OpenGL context that Skia's
    `BackendRenderTarget.makeGL(...)` consumes.
  - `GMainContext` event-loop integration with the JVM render thread.
- **Why GTK4 alone**: avoids the libadwaita runtime dep (which lags
  GTK4 on some distros and isn't yet ubiquitous on enterprise/LTS
  Linux). GTK4 alone provides everything Skiko needs: header bar,
  light/dark detection, AT-SPI accessibility, IME, HiDPI, clipboard,
  drag-and-drop. We give up Adwaita-styled native widgets — but Skiko
  draws its own UI via Skia, so widget styling isn't relevant.
- **Why not raw Wayland?** GTK4 with `GDK_BACKEND=wayland` *is*
  effectively Wayland-direct from a deployment standpoint — X11 is
  fallback only. GTK4 brings window-management primitives (header bar,
  dialogs, IME, accessibility) we'd otherwise re-implement against raw
  Wayland.
- **Reference**: **none in this codebase.** `linuxMain/SkiaLayer.linux.kt`
  is `TODO()`. External templates: GIMP (C), Inkscape (C++), and
  `gtk4-rs` (Rust GObject FFI) for the binding patterns. Note that
  `gtk4-rs`'s `gtk4` crate is GTK4-only; their `libadwaita` crate is
  separate — confirms the dep boundary is clean.
- **Threading**: GTK is main-thread-only via `g_main_context_default()`.
  Same constraint as AppKit. Java main + GTK main loop on same thread,
  with periodic `g_main_context_iteration` calls (or hand the JVM thread
  to GTK and pump from a worker via `g_idle_add`).
- **Effort is largest of the three** because we're building both the
  FFM binding *and* the template. macOS we just translate from K/N;
  Linux we build from scratch.

### Windows — Win32 + Direct3D 11 (medium risk; ~8–12 weeks)

- **Bind path**: FFM → `user32.dll`, `gdi32.dll`, `kernel32.dll` (window
  class, message loop, HWND), `d3d11.dll` + `dxgi.dll` (swap chain,
  device, command list).
- **What we wrap**: `RegisterClassEx` / `CreateWindowEx` / `GetMessage` /
  `DispatchMessage`; `D3D11CreateDeviceAndSwapChain`;
  `IDXGISwapChain::Present`. Skia consumes a D3D11 texture handle via
  `BackendRenderTarget.makeDirect3D(...)`.
- **WinUI 3**: explicitly **not** in scope. WinUI 3 means WinRT/COM,
  XAML Islands, and a substantially heavier binding surface. Skiko
  draws its own UI via Skia, so XAML styling is largely irrelevant.
  Documented as a future option once Win32 + D3D11 is shipping.
- **Reference**: `skiko/src/awtMain/cpp/windows/directXRedrawer.cc`
  shows today's D3D11 path; lift the algorithm, drop the JAWT/HWND-from-AWT
  step, replace with raw `CreateWindowEx`.
- **Threading**: Win32 message loop on the thread that called
  `CreateWindowEx`. Established pattern.

### Compose Multiplatform integration (cross-cutting; multi-quarter)

Compose Desktop today has hard assumptions:
- `androidx.compose.ui.awt.ComposeWindow` extends `JFrame`.
- `ComposePanel` extends `JLayeredPane`.
- The Skiko layer is consumed as a `JComponent`.
- Event integration goes through `java.awt.event.*`.

A non-AWT Skiko cannot drop into that runtime as-is. The Compose
Multiplatform changes (out of scope for Skiko itself; coordinated with
JetBrains):

1. **Abstract Compose's host window**: introduce `ComposeHost` with
   implementations `AwtComposeHost` (current) and `NativeComposeHost`
   (new). Same shape Compose for iOS already adopted with
   `ComposeUIViewController`.
2. **New event source**: input events come from AppKit / Win32 / GTK
   instead of `java.awt.event.*`. Compose's input pipeline already
   abstracts this for iOS — extend to desktop native.
3. **Lifecycle integration**: window focus, occlusion, resize, close
   sourced from native callbacks instead of `WindowAdapter`.
4. **Resource model**: unchanged. Still on JVM, still uses
   `getResource`.

Skiko's side can land independently behind the `awtFreeMain` source
set + a new artifact `skiko-jvm-native-runtime-{platform}`. Compose can
opt in when ready.

### Phases (Phase 1)

- **A — macOS pilot** (~6–10 weeks). Port `MetalRedrawer.macos.kt` to
  JVM via FFM. Build minimal `awtFreeMain` with one Skiko sample (no
  Compose). Demonstrate parity with the existing AWT-Metal path.
- **B — Linux + GTK4** (~12–16 weeks). GTK4-only binding, no
  libadwaita. Same sample. **The X11-elimination milestone.**
- **C — Windows port** (~8–12 weeks). Win32 + D3D11.
- **D — Compose Desktop integration** (parallel with B/C, joint with
  JetBrains): `NativeComposeHost`, native event source, lifecycle
  bridge.

## Phase 2: Internal JNI Cleanup (consequence, not goal)

Once Phase 1 ships, the existing JNI surface inside Skiko's core (the
~1,026 `JNIEXPORT` symbols in `skiko/src/jvmMain/cpp/` that bind Skia's
API to Kotlin) becomes a candidate for FFM cleanup. **This is not a
primary goal.** It falls out naturally because:

1. Phase 1's Linux work removes the JAWT-shaped FFM blocker
   (`awtMain/cpp/linux/awt.cc`'s use of `JAWT_X11DrawingSurfaceInfo`).
   Without that, an FFM-native JVM target was always going to need a
   kept JNI shim. With Phase 1 done, the JVM target can go to 100% FFM.
2. The JVM target post-Phase-1 already has FFM machinery (downcall
   handles, `Arena` lifetimes, upcall stubs for the windowing layer).
   Extending that to the rest of the API surface is incremental.

### What it removes

| Layer | Today | After Phase 2 | Δ |
|---|---|---|---|
| C++ JNI thunks (`jvmMain/cpp/`) | **10,887** LOC | ≈0 (replaced by direct `extern "C"` Skia symbols) | **−9,000 to −10,000 LOC** |
| Per-type `_nGetFinalizer` stubs (~1,026 × 3 lines) | ~3,100 LOC | replaced by `Arena` ownership | **−3,000 LOC C++** |
| `try/finally` + `Reference.reachabilityFence` per call site | many | gone with `Arena` scoping | **−1,000 to −3,000 LOC Kotlin** |
| `Managed` + cleaner thread plumbing | ~120 LOC | `Arena` | **−80 to −120 LOC** |

Net Phase 2 estimate: **~13,000–17,000 LOC removed**. Important context:
**none of this is a real dependency drop.** Skia is still Skia; the
binary `libskiko-jvm.so` either disappears entirely or shrinks
dramatically, but Skia itself remains the rendering engine. This is
code-quality work, not dependency-elimination work.

### What it gets us (the "incidental" benefits)

These are all real but secondary to Phase 1's dependency story:

1. **Memory safety** via `MemorySegment` (length and lifetime tracked).
2. **Deterministic lifetime** via `Arena.ofConfined()` /
   `Arena.ofShared()` instead of the racy `Managed` + PhantomReference
   cleaner (`RefCnt.refCount` needs `reachabilityFence`; `close()` may
   double-free if the cleaner already ran).
3. **JFR observability** of native time. JNI calls are opaque to JFR;
   FFM downcalls are not.
4. **No more JNI mangling**. The `import-generator/` IR plugin's
   `Java_*` mangling rules become unnecessary on the JVM target. Wasm
   and JS paths still use them.
5. **Cleaner GraalVM native-image, Loom, generational ZGC interaction**
   in principle. Caveat: FFM upcall stubs currently need extra
   native-image config; documented limitation.

### Measured Performance (the bad news)

A JMH harness lives at `benchmarks/`. We measured Skiko's
`Canvas.drawRect` hot path on this development host:

- **Host**: AMD Ryzen 9 7900X (12-core), Linux 7.0.4-200.fc44.x86_64
- **JVM**: Eclipse Temurin 25.0.3+9-LTS
- **Skiko**: `org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.144.6`
  (latest Maven Central release; see "Known Limitation")
- **JMH**: 1.37, 3 forks × 5 warmup × 10 measurement, 2s per iteration
- **Workload**: `Canvas.drawRect(10f, 10f, 50f, 50f, paint)` against a
  256×256 software surface

Result:

```
Benchmark                          Mode  Cnt    Score    Error  Units
JniVsFfmBenchmark.jniBaseline      avgt   30  240.695 ±  8.446  ns/op
JniVsFfmBenchmark.ffmViaJniSymbol  avgt   30  261.793 ± 20.582  ns/op
```

**FFM is ~21 ns / ~9% slower than JNI on this signature.** The 99.9%
CIs do not overlap. The result contradicts the public projection that
FFM beats JNI by 5–15% on small signatures, for two reasons:

1. **The JNI thunk does almost no JNI prologue work.**
   `Java_org_jetbrains_skia_CanvasKt__1nDrawRect` takes its `JNIEnv*` and
   `jclass` and never uses them — it just casts `jlong` to
   `SkCanvas*`/`SkPaint*`. There's no `GetObjectField`, no array
   pinning. So the "FFM avoids JNI overhead" effect has nothing to
   remove. We pay FFM's `MethodHandle` dispatch on top of an
   already-thin JNI call.
2. **Variant 3 (clean C shim) was scaffolded but not built.** Building
   it requires linking against Skia, which needs the full Skiko build
   environment. Even with the `JNIEnv*`/`jclass` removal, the win is
   unlikely to flip the sign.

**Don't migrate Skiko to FFM for performance.** Phase 2 is justified
purely on code-quality grounds. The performance number is honestly bad
on Skiko's representative hot path.

### Known Limitation

We measured against the published `0.144.6` artifact instead of a
locally-built `0.0.0-SNAPSHOT` because the existing skiko build is
broken under JDK 25: Kotlin Gradle Plugin 2.0.21 fails with
`java.lang.IllegalArgumentException: 25.0.3` from
`org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse`. Bumping
skiko's Kotlin/Gradle to versions that handle JDK 25 (Kotlin 2.1+ /
Gradle 9.0+) is a prerequisite for any FFM work, and is unrelated to
FFM itself. The benchmarks subproject ships its own Gradle 9.5.0
wrapper so it can run independently against the published artifact.

## Anchor Numbers (verified)

The estimates in this document are anchored to LOC and symbol counts
measured against the current tree:

| Anchor | Measured |
|---|---|
| `skiko/src/jvmMain/cpp/` LOC (`.cc` + `.cpp`) | **10,887** |
| `skiko/src/awtMain/cpp/` LOC | **3,319** |
| `skiko/src/awtMain/objectiveC/` LOC | **1,353** |
| `JNIEXPORT` symbols in `jvmMain/cpp/` | **1,026** |
| `JNIEXPORT` symbols in `awtMain/cpp/` | **88** |
| Total JNI symbols on JVM target | **~1,114** |

## Recommended Sequencing

1. **Prerequisite**: bump skiko's existing build to a Kotlin/Gradle that
   handles JDK 25 (Kotlin 2.1+ / Gradle 9.0+). This is independent of
   FFM but unblocks everything downstream.
2. **Phase 1A — macOS pilot**. Lowest risk; the K/N reference exists.
   Validates the FFM + native-windowing model end-to-end.
3. **Phase 1B — Linux + GTK4**. The X11-elimination milestone. Highest
   value for the user's stated goal. Run partly in parallel with Phase 1A.
4. **Phase 1C — Windows** and **Phase 1D — Compose Desktop integration**
   in parallel.
5. **Phase 2** (internal JNI cleanup) follows once Phase 1 is shipping.
   Treat as a quality-of-implementation backlog, not a parallel
   initiative.

The sequencing front-loads the work that *proves the model* on the
platform with a working K/N template (macOS), tackles the
unprecedented-but-highest-value work (Linux native windowing) once the
model is proven, and defers the internal cleanup to after the
dependency-elimination payoff has shipped.

## Benchmark Harness

Lives at `benchmarks/` as a self-contained Gradle build with its own
Gradle 9.5.0 wrapper (so it runs cleanly under JDK 25 even while the
rest of the repo is on the older Gradle/Kotlin that doesn't).

### Variants

1. **`jniBaseline`** — today's binding via `Canvas.drawRect(...)` →
   `Java_org_jetbrains_skia_CanvasKt__1nDrawRect`. **Implemented and
   measured.**
2. **`ffmViaJniSymbol`** — FFM `Linker.downcallHandle` resolves the same
   JNI symbol with `MemorySegment.NULL` for `JNIEnv*` and `jclass`.
   **Implemented and measured.**
3. **`ffmViaCleanShim`** — FFM into a separate `extern "C" skiko_drawRect`
   thunk with no JNI prologue. **Scaffolded but not built**; building
   requires linking against Skia (see
   `benchmarks/src/jmh/cpp/skiko_bench_shim.cc` for instructions).

### Running

```sh
cd benchmarks
./gradlew jmh -Pskiko.version=0.144.6 \
  -Pjmh.includes="JniVsFfmBenchmark.(jniBaseline|ffmViaJniSymbol)"
```

Pass `-Pskiko.version=0.0.0-SNAPSHOT` once a local build is publishable
(blocked today by the JDK 25 / Kotlin 2.0.21 compatibility issue).

Results land in `benchmarks/build/reports/jmh/results.json`.

## Open Questions

1. **Java version floor**. Phase 1 needs ≥22 for stable FFM. JDK 25 LTS
   is the natural target. Breaking decision for downstream consumers
   (Compose Multiplatform on JDK 17 LTS especially).
2. **Pre-existing build compatibility**. Skiko's existing build (Kotlin
   2.0.21 / Gradle 8.13/8.14.3) does not run on JDK 25. Bumping is a
   prerequisite for any FFM work and is independent of FFM itself.
3. **WinUI 3**. Out of scope. Future option once Win32 ships and
   stabilizes, if Fluent styling and accessibility ever become
   relevant. Skiko's self-drawn UI makes XAML styling marginal.
4. **Linux toolkit refinement**. GTK4 alone (decided). Future options
   if GTK4's runtime cost ever becomes a concern: (a) raw Wayland for a
   smaller dep footprint, (b) libadwaita on top if Adwaita-styled
   native widgets become useful (unlikely given Skiko's self-drawn UI).
5. **GraalVM native-image**. FFM upcall stubs have known native-image
   friction. Documented limitation; not blocking.
6. **Compose Desktop runtime fork timeline**. Outside Skiko's control.
   Sequencing assumes JetBrains coordination on a multi-quarter
   timeline.
7. **Phase 2 economic case**. Phase 2 has a ~9% per-call perf
   regression and no dependency drop. The case for doing it rests
   entirely on code-quality wins (memory safety, `Arena` lifetimes, JFR
   observability) plus the LOC reduction. Worth re-evaluating after
   Phase 1 ships, when the marginal cost of completing the FFM
   migration on the rest of the API surface will be visible.

## Files Referenced

- `skiko/src/jvmMain/cpp/common/Canvas.cc` — JNI thunk for `_nDrawRect`,
  the symbol the benchmark resolves via FFM
- `skiko/src/jvmMain/kotlin/org/jetbrains/skia/impl/Managed.jvm.kt` — the
  PhantomReference cleaner that `Arena` would replace in Phase 2
- `skiko/src/commonMain/kotlin/org/jetbrains/skia/Canvas.kt:1620` —
  representative hot path
- `skiko/src/jvmMain/cpp/common/Drawable.cc`,
  `OutputWStream.cc`, `shaper/Shaper.cc` — representative upcall
  patterns (the riskiest perf area for Phase 2)
- `skiko/src/awtMain/cpp/linux/awt.cc` — the JAWT/X11 dependency that
  Phase 1B eliminates
- `skiko/src/awtMain/cpp/windows/directXRedrawer.cc` — today's D3D path,
  algorithmic reference for Phase 1C
- `skiko/src/macosMain/kotlin/.../redrawer/MetalRedrawer.macos.kt` —
  the Phase 1A template
- `skiko/src/uikitMain/kotlin/.../redrawer/MetalRedrawer.uikit.kt` —
  iOS reference for run-loop-driven Metal rendering
- `skiko/src/linuxMain/kotlin/.../SkiaLayer.linux.kt` — the empty
  `TODO()` that Phase 1B fills in
- `skiko/src/commonMain/kotlin/.../redrawer/Redrawer.kt` — the
  toolkit-neutral interface that all platforms implement
- `import-generator/src/main/kotlin/.../ImportGeneratorTransformer.kt` —
  the IR plugin doing JNI symbol mangling on the JVM target (becomes
  unnecessary for JVM after Phase 2)
- `benchmarks/build.gradle.kts`,
  `benchmarks/src/jmh/kotlin/JniVsFfmBenchmark.kt`,
  `benchmarks/src/jmh/cpp/skiko_bench_shim.cc`,
  `benchmarks/README.md` — the JMH harness backing this document's perf
  claims
