plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.jetbrains.skiko.spike.gtk4.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
    )
}

tasks.named<JavaExec>("run") {
    // Force GTK to use the Wayland backend only. Without this, GTK4's GDK
    // auto-probes both backends and pulls in libX11 even on a Wayland session.
    environment("GDK_BACKEND", "wayland")
}
