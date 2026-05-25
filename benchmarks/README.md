# Skiko JNI vs. FFM Benchmark

JMH harness backing the perf claims in `docs/java25-ffm.md`. Compares Skiko's
existing JNI binding for `Canvas.drawRect` against equivalent FFM downcalls on
JDK 22+.

## What it measures

Three variants over the same workload (`SkCanvas::drawRect` against a 256x256
software surface):

1. **`jniBaseline`** — today's Skiko binding via Kotlin's `external fun` →
   `Java_org_jetbrains_skia_CanvasKt__1nDrawRect`.
2. **`ffmViaJniSymbol`** — FFM `Linker.downcallHandle` resolves the existing
   JNI symbol and calls it directly with `MemorySegment.NULL` for the
   `JNIEnv*` and `jclass` arguments. The C++ implementation is unchanged;
   this measures FFM's call overhead against the JNI prologue.
3. **`ffmViaCleanShim`** — FFM into a separate `extern "C" skiko_drawRect`
   thunk with no JNI prologue. Closest to a fully migrated Skiko. **Optional**:
   requires building `libskiko_bench_shim` against Skia (see
   `src/jmh/cpp/skiko_bench_shim.cc` for instructions). If the shim is not
   present, the benchmark throws and you skip it with `-e ffmViaCleanShim`.

The `(variant 3) - (variant 2)` delta isolates the cost of the JNI prologue
from FFM proper.

## Prerequisites

- JDK 22 or later. JDK 25 LTS recommended. The Gradle toolchain is set to 25.
- Skiko published to your local Maven repository:

  ```sh
  cd ../skiko
  ./gradlew publishToMavenLocal
  cd ../benchmarks
  ```

  Pass `-Pskiko.version=...` to override the default `0.0.0-SNAPSHOT` if you
  published a tagged build.

## Running

```sh
./gradlew :jmh
```

To skip variant 3 if you haven't built the shim:

```sh
./gradlew :jmh -Pjmh.includes="JniVsFfmBenchmark.(jniBaseline|ffmViaJniSymbol)"
```

Results land in `build/reports/jmh/results.json`.

## Interpreting

This harness measures **call overhead** on a single hot path. The numbers do
**not** generalize to:

- Workloads dominated by Skia's own work (real rendering, GPU submission).
- Calls with array/string marshaling.
- Upcalls (Skia/C++ → Kotlin callbacks like `Drawable.onDraw`,
  `OutputWStream.write`, shaper `RunHandler`). FFM upcalls have different
  perf characteristics and are **not** measured here. Treat upcall-heavy
  workloads (text shaping especially) as an unmeasured risk area.

The doc reports the measured numbers with the host CPU and JVM build inline.
