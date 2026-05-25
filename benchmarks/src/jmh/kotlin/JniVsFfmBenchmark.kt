package org.jetbrains.skiko.bench

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
open class JniVsFfmBenchmark {

    private lateinit var surface: Surface
    private lateinit var canvas: Canvas
    private lateinit var paint: Paint
    private var canvasPtr: Long = 0
    private var paintPtr: Long = 0

    private var ffmJniSymbolHandle: MethodHandle? = null
    private var ffmCleanShimHandle: MethodHandle? = null

    @Setup
    fun setup() {
        surface = Surface.makeRasterN32Premul(256, 256)
        canvas = surface.canvas
        paint = Paint().apply { color = 0xFF000000.toInt() }

        canvasPtr = readPtrField(canvas)
        paintPtr = readPtrField(paint)

        val linker = Linker.nativeLinker()
        val processLookup = SymbolLookup.loaderLookup().or(linker.defaultLookup())

        ffmJniSymbolHandle = processLookup
            .find("Java_org_jetbrains_skia_CanvasKt__1nDrawRect")
            .map { sym ->
                linker.downcallHandle(
                    sym,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,    // JNIEnv*
                        ValueLayout.ADDRESS,    // jclass
                        ValueLayout.JAVA_LONG,  // canvasPtr
                        ValueLayout.JAVA_FLOAT, // l
                        ValueLayout.JAVA_FLOAT, // t
                        ValueLayout.JAVA_FLOAT, // r
                        ValueLayout.JAVA_FLOAT, // b
                        ValueLayout.JAVA_LONG   // paintPtr
                    )
                )
            }
            .orElse(null)

        ffmCleanShimHandle = try {
            val shimName = System.mapLibraryName("skiko_bench_shim")
            val shimLookup = SymbolLookup.libraryLookup(shimName, Arena.global())
            shimLookup.find("skiko_drawRect").map { sym ->
                linker.downcallHandle(
                    sym,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_LONG
                    )
                )
            }.orElse(null)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsatisfiedLinkError) {
            null
        }

        require(ffmJniSymbolHandle != null) {
            "Could not resolve JNI symbol Java_org_jetbrains_skia_CanvasKt__1nDrawRect — " +
                "ensure libskiko-jvm is loaded (calling Skiko once during @Setup forces this)."
        }
    }

    @TearDown
    fun teardown() {
        paint.close()
        surface.close()
    }

    @Benchmark
    fun jniBaseline() {
        canvas.drawRect(Rect.makeLTRB(10f, 10f, 50f, 50f), paint)
    }

    @Benchmark
    fun ffmViaJniSymbol() {
        ffmJniSymbolHandle!!.invokeExact(
            MemorySegment.NULL,
            MemorySegment.NULL,
            canvasPtr,
            10f, 10f, 50f, 50f,
            paintPtr
        )
    }

    @Benchmark
    fun ffmViaCleanShim() {
        val handle = ffmCleanShimHandle
            ?: throw UnsupportedOperationException(
                "Clean-shim variant requires libskiko_bench_shim — see benchmarks/src/jmh/cpp/skiko_bench_shim.cc " +
                    "for build instructions. Skip this benchmark with -e ffmViaCleanShim."
            )
        handle.invokeExact(
            canvasPtr,
            10f, 10f, 50f, 50f,
            paintPtr
        )
    }

    private fun readPtrField(obj: Any): Long {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("_ptr")
                f.isAccessible = true
                return f.getLong(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("_ptr field not found on ${obj.javaClass.name}")
    }
}
