// Codec2 for Android: the vendored libcodec2 (LGPL-2.1) and its JNI glue, built
// by the NDK, behind :core's VoiceCodec. The JVM unit tests load a host build of
// the same C sources (hostCodec2), so the JNI round trip is tested without a phone.
plugins {
    alias(libs.plugins.android.library)
}

val cmakeVersion = "3.22.1"

android {
    namespace = "org.opencell.codec2"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 31
        ndk {
            // The phones (arm64) and the emulator (x86_64).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = cmakeVersion
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}

// The host build of src/main/cpp for the unit tests, with the SDK's CMake and Ninja.
val hostDir = layout.buildDirectory.dir("host-codec2")
val cmakeBin = androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/$cmakeVersion/bin") }
val hostConfigure = tasks.register<Exec>("hostCodec2Configure") {
    val src = layout.projectDirectory.dir("src/main/cpp")
    inputs.dir(src)
    outputs.dir(hostDir)
    executable = cmakeBin.get().file("cmake").asFile.path
    args(
        "-S", src.asFile.path, "-B", hostDir.get().asFile.path, "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=" + cmakeBin.get().file("ninja").asFile.path,
        "-DCMAKE_BUILD_TYPE=Release",
    )
}
val hostCodec2 = tasks.register<Exec>("hostCodec2") {
    dependsOn(hostConfigure)
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
    outputs.dir(hostDir)
    executable = cmakeBin.get().file("cmake").asFile.path
    args("--build", hostDir.get().asFile.path)
}
tasks.withType<Test>().configureEach {
    dependsOn(hostCodec2)
    systemProperty("java.library.path", hostDir.get().asFile.path)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
