# libopencell_openvox.so: OpenVoxNB (oc_vox, Apache-2.0, vendored by
# tools/openvox/vendor.sh) and the JNI glue for org.opencell.openvox.OpenVoxNative,
# in one library (OpenVoxNB spec V8: unlike LGPL Codec2 it needn't stay replaceable).
# Built by the NDK for the APK (externalNativeBuild) and by the host compiler
# for the JVM unit tests (the hostOpenVox task in ../../../build.gradle.kts).
cmake_minimum_required(VERSION 3.18)
project(opencell_openvox C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(OVX ${CMAKE_CURRENT_SOURCE_DIR}/oc_vox)
add_library(opencell_openvox SHARED
    ${OVX}/oc_vox_modes.c ${OVX}/oc_vox_layout.c ${OVX}/oc_vox_bits.c ${OVX}/oc_vox_fec.c
    ${OVX}/oc_vox_quant.c ${OVX}/oc_vox_cb.c ${OVX}/oc_vox_conceal.c ${OVX}/oc_vox_pipe.c
    ${OVX}/fx/oc_vox_fx_tables.c ${OVX}/fx/oc_vox_fx_dsp.c ${OVX}/fx/oc_vox_fx_analysis.c
    ${OVX}/fx/oc_vox_fx_synth.c ${OVX}/oc_vox.c
    opencell_openvox_jni.c)
target_include_directories(opencell_openvox PRIVATE ${OVX}/include ${OVX} ${OVX}/fx)
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(opencell_openvox PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(ANDROID)
    # 16 KB pages (Android 15+ devices may use them; NDK r27 still defaults to 4 KB).
    target_link_options(opencell_openvox PRIVATE "-Wl,-z,max-page-size=16384")
endif()
