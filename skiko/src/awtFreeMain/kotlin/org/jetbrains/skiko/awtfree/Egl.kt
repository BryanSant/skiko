package org.jetbrains.skiko.awtfree

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import org.jetbrains.skia.GLAssembledInterface

// libEGL bindings used to assemble a Skia GL interface that targets
// the Wayland-native GdkGLContext that GtkGLArea uses.
//
// Skia's default GrDirectContexts::MakeGL() goes through GLX on
// Linux; on a Wayland session with no current GLX context that
// returns null. The "with interface" path lets us hand Skia an
// explicit GL function table assembled via GrGLMakeAssembledInterface,
// which only needs a (ctx, name) -> proc-address resolver. We provide
// that resolver as an FFM upcall over libEGL's eglGetProcAddress.

private val egl: SymbolLookup =
    SymbolLookup.libraryLookup("libEGL.so.1", ffmArena)

private val eglGetProcAddressHandle: MethodHandle = downcall(
    egl, "eglGetProcAddress",
    FunctionDescriptor.of(ADDRESS, ADDRESS), // (const char* name) -> proc fn ptr
)

// Skia's GLAssembledInterface expects a (void* ctx, const char* name)
// resolver. EGL only takes the name, so the trampoline drops ctx.
fun skikoAwtFreeGlGetProc(
    @Suppress("UNUSED_PARAMETER") ctx: MemorySegment,
    name: MemorySegment,
): MemorySegment = eglGetProcAddressHandle.invoke(name) as MemorySegment

private val getProcUpcallStub: MemorySegment by lazy {
    upcall(
        cls = Class.forName("org.jetbrains.skiko.awtfree.EglKt"),
        name = "skikoAwtFreeGlGetProc",
        methodType = MethodType.methodType(
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
        ),
        descriptor = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
    )
}

// Builds a Skia GLInterface backed by libEGL. The current
// GdkGLContext (set by GtkGLArea before the render signal fires) is
// implicit — Skia uses whatever EGL context is current at call time.
internal fun makeEglAssembledInterface(): GLAssembledInterface =
    GLAssembledInterface.createFromNativePointers(
        ctxPtr = 0L,
        fPtr = getProcUpcallStub.address(),
    )
