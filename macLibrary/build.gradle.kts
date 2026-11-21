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

// The app's own version, readable at run time (for its updater and for Claude).
val versionDir = layout.buildDirectory.dir("generated/version")
val writeVersion by tasks.registering {
    val v = libVersion
    val out = versionDir
    outputs.dir(out)
    doLast { out.get().file("bitchord-library-version.txt").asFile.apply { parentFile.mkdirs(); writeText(v) } }
}
sourceSets.main { resources.srcDir(versionDir) }
tasks.named("processResources") { dependsOn(writeVersion) }

kotlin {
    jvmToolchain(21)
    // Bytecode for Java 11: the jar also runs on older Javas (Claude's shell has 11).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
java { targetCompatibility = JavaVersion.VERSION_11 }

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

// BitChord Audio: the same engine as a plain jar, for the command line (and for Claude, who
// runs it on the library from its own Linux shell with the system's ffprobe/ffmpeg).
val cli by configurations.creating
dependencies {
    cli("org.json:json:20240303")
    cli("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    cli(kotlin("stdlib"))
}
tasks.register<Jar>("cliJar") {
    archiveFileName.set("bitchord-library-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("cli"))
    manifest { attributes("Main-Class" to "bitchord.library.terminal.CliMainKt") }
    from(sourceSets.main.get().output) { exclude("bitchord/library/MainKt*", "bitchord/library/Tab*", "bitchord/library/LibraryState*") }
    from(cli.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**/module-info.class")
}
