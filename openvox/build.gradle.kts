// OpenVoxNB for Android: the vendored oc_vox (Apache-2.0, OpenCell's own codec) and
// its JNI glue in one library, libopencell_openvox.so, built by the NDK, behind
// :core's VoiceCodec. The JVM unit tests load a host build of the same C sources
// (hostOpenVox), so the JNI round trip is tested without a phone. As :codec2.
plugins {
    alias(libs.plugins.android.library)
}

val cmakeVersion = "3.22.1"

android {
    namespace = "org.opencell.openvox"
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
val hostDir = layout.buildDirectory.dir("host-openvox")
val cmakeBin = androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/$cmakeVersion/bin") }
val hostConfigure = tasks.register<Exec>("hostOpenVoxConfigure") {
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
val hostOpenVox = tasks.register<Exec>("hostOpenVox") {
    dependsOn(hostConfigure)
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
    outputs.dir(hostDir)
    executable = cmakeBin.get().file("cmake").asFile.path
    args("--build", hostDir.get().asFile.path)
}
tasks.withType<Test>().configureEach {
    dependsOn(hostOpenVox)
    systemProperty("java.library.path", hostDir.get().asFile.path)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
