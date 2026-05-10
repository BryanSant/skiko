package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Redrawer

// Phase 1B: real impl returns a GtkRedrawer that renders into a
// GtkGLArea via Skia's GL backend. Stubbed for now.
internal actual fun makeDefaultRenderFactory(): RenderFactory =
    RenderFactory { _, _, _, _ -> TODO("Phase 1B: GtkRedrawer not yet implemented") }
