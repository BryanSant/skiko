plugins {
    kotlin("jvm") version "2.3.20"
    id("me.champeau.jmh") version "0.7.2"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}

val osName: String = System.getProperty("os.name")
val targetOs: String = when {
    osName == "Mac OS X" -> "macos"
    osName.startsWith("Win") -> "windows"
    osName.startsWith("Linux") -> "linux"
    else -> error("Unsupported OS: $osName")
}

val osArch: String = System.getProperty("os.arch")
val targetArch: String = when (osArch) {
    "x86_64", "amd64" -> "x64"
    "aarch64" -> "arm64"
    else -> error("Unsupported arch: $osArch")
}

val target = "$targetOs-$targetArch"

val skikoVersion: String =
    (project.findProperty("skiko.version") as String?) ?: "0.0.0-SNAPSHOT"

dependencies {
    jmh("org.jetbrains.kotlin:kotlin-stdlib")
    jmh("org.jetbrains.skiko:skiko-awt-runtime-$target:$skikoVersion")
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

kotlin {
    jvmToolchain(25)
}

jmh {
    jvmArgs.set(
        listOf(
            // FFM enables foreign linker without warnings on JDK 22+; harmless on 25.
            "--enable-native-access=ALL-UNNAMED",
        )
    )
    fork.set(3)
    warmupIterations.set(5)
    warmup.set("2s")
    iterations.set(10)
    timeOnIteration.set("2s")
    timeUnit.set("ns")
    benchmarkMode.set(listOf("avgt"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
