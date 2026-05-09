// skiko_bench_shim.cc
//
// Variant 3 of the JNI-vs-FFM benchmark: a clean C ABI entry point that
// performs the same SkCanvas::drawRect work as Skiko's existing JNI thunk
// (Java_org_jetbrains_skia_CanvasKt__1nDrawRect), but without the JNIEnv*
// and jclass parameters. The (variant 3) - (variant 2) delta isolates the
// cost of FFM proper from the cost of the JNI calling convention prologue.
//
// BUILD INSTRUCTIONS
// ------------------
// This file is intentionally not wired into the Gradle build by default,
// because building it requires linking against Skia (libskia.a), which in
// turn requires Skiko's full Skia toolchain setup. To build:
//
//   1. Build/publish Skiko with local Skia per skiko/build-with-local-skia.sh,
//      so $SKIA_REPO_DIR points to your Skia checkout and Skiko's build has
//      already produced its linked artifacts.
//   2. Compile this file against the Skia headers from $SKIA_REPO_DIR:
//
//        clang++ -std=c++17 -fPIC -shared \
//          -I$SKIA_REPO_DIR -I$SKIA_REPO_DIR/include \
//          benchmarks/src/jmh/cpp/skiko_bench_shim.cc \
//          $SKIA_REPO_DIR/out/Release-x64/libskia.a \
//          -o benchmarks/build/skiko_bench_shim/libskiko_bench_shim.so
//
//   3. Add the output directory to java.library.path when running the
//      benchmark, e.g.:
//
//        ./gradlew :benchmarks:jmh \
//          -Pjmh.jvmArgsAppend="-Djava.library.path=benchmarks/build/skiko_bench_shim"
//
// If the shim is unavailable at runtime, the JniVsFfmBenchmark::ffmViaCleanShim
// benchmark throws UnsupportedOperationException and the harness reports
// only variants 1 and 2.
//
// FUTURE WORK
// -----------
// Wiring this into Gradle would require replicating Skiko's CompileSkikoCppTask
// for a single source file. Tractable but out of scope for the harness MVP.
// Track in docs/java25-ffm.md "Open Questions" if pursuing the full FFM port.

#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkRect.h"

extern "C" void skiko_drawRect(
    void* canvasPtr,
    float left, float top, float right, float bottom,
    void* paintPtr
) {
    auto* canvas = reinterpret_cast<SkCanvas*>(canvasPtr);
    auto* paint = reinterpret_cast<SkPaint*>(paintPtr);
    canvas->drawRect({left, top, right, bottom}, *paint);
}
