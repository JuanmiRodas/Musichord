import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// BitChord Audio: the Mac app that tidies the music library on pCloud (see LIBRARY_PLAN.md).
// Built only with -Pbitchord.mac=true on JDK 21 (the Mac workflow), never by the phone's build.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libVersion: String = providers.gradleProperty("bitchord.libraryVersion").orNull ?: "1.0.0"
val javacpp = "1.5.12"
val ffmpeg = "7.1.1-$javacpp"
val arch = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "macosx-arm64" else "macosx-x86_64"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // FFmpeg's own ffprobe / ffmpeg programs, carried inside the app (no Homebrew needed).
    implementation("org.bytedeco:ffmpeg:$ffmpeg")
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpeg:$arch")
    runtimeOnly("org.bytedeco:javacpp:$javacpp:$arch")
}

kotlin { jvmToolchain(21) }

compose.desktop {
    application {
        mainClass = "bitchord.library.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "BitChord Library"
            packageVersion = libVersion
            description = "Tidies the BitChord Audio music library on pCloud"
            vendor = "BitChord Audio"
            modules("jdk.unsupported", "java.naming")
            macOS {
                bundleID = "com.music.bitchord.library"
                appCategory = "public.app-category.music"
            }
        }
    }
}
