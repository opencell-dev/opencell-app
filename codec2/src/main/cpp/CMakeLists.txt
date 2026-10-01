# libcodec2.so: the vendored Codec2 vocoder (LGPL-2.1), its own shared library.
# libopencell_codec2.so: the JNI glue for org.opencell.codec2.Codec2Native.
# Built by the NDK for the APK (externalNativeBuild) and by the host compiler
# for the JVM unit tests (the hostCodec2 task in ../../../build.gradle.kts).
cmake_minimum_required(VERSION 3.18)
project(opencell_codec2 C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(C2 ${CMAKE_CURRENT_SOURCE_DIR}/codec2)
add_library(codec2 SHARED
    ${C2}/src/codec2.c ${C2}/src/codec2_fft.c ${C2}/src/kiss_fft.c ${C2}/src/kiss_fftr.c
    ${C2}/src/dump.c ${C2}/src/lpc.c ${C2}/src/nlp.c ${C2}/src/postfilter.c ${C2}/src/sine.c
    ${C2}/src/interp.c ${C2}/src/lsp.c ${C2}/src/mbest.c ${C2}/src/newamp1.c ${C2}/src/phase.c
    ${C2}/src/quantise.c ${C2}/src/pack.c
    ${C2}/src/codebook.c ${C2}/src/codebookd.c ${C2}/src/codebookjmv.c ${C2}/src/codebookge.c
    ${C2}/src/codebooknewamp1.c ${C2}/src/codebooknewamp1_energy.c)
target_include_directories(codec2 PUBLIC ${C2}/src ${C2}/include)
target_link_libraries(codec2 PRIVATE m)

add_library(opencell_codec2 SHARED opencell_codec2_jni.c)
target_link_libraries(opencell_codec2 PRIVATE codec2)
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(opencell_codec2 PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(ANDROID)
    # 16 KB pages (Android 15+ devices may use them; NDK r27 still defaults to 4 KB).
    target_link_options(codec2 PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_options(opencell_codec2 PRIVATE "-Wl,-z,max-page-size=16384")
endif()
