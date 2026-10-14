/* JNI glue for org.opencell.openvox.OpenVoxNative: one oc_vox encoder or
 * decoder per handle, allocated here (oc_vox itself never allocates). The
 * Kotlin side checks every array length before calling, so these only guard
 * against a null handle. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "oc_vox.h"
#include "oc_vox_vendor.h"

static oc_vox_enc_t *enc(jlong h) { return (oc_vox_enc_t *)(intptr_t)h; }
static oc_vox_dec_t *dec(jlong h) { return (oc_vox_dec_t *)(intptr_t)h; }

JNIEXPORT jint JNICALL
Java_org_opencell_openvox_OpenVoxNative_version(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)OC_VOX_VERSION;
}

JNIEXPORT jstring JNICALL
Java_org_opencell_openvox_OpenVoxNative_commit(JNIEnv *env, jclass cls)
{
    (void)cls;
    return (*env)->NewStringUTF(env, OC_VOX_VENDOR_COMMIT);
}

JNIEXPORT jint JNICALL
Java_org_opencell_openvox_OpenVoxNative_blockBytes(JNIEnv *env, jclass cls, jint mode)
{
    (void)env; (void)cls;
    return (jint)oc_vox_block_bytes((oc_vox_mode_t)mode);
}

JNIEXPORT jlong JNICALL
Java_org_opencell_openvox_OpenVoxNative_encoderCreate(JNIEnv *env, jclass cls, jint mode)
{
    (void)env; (void)cls;
    oc_vox_enc_t *e = malloc(sizeof *e);
    if (e != NULL && oc_vox_enc_init(e, (oc_vox_mode_t)mode) != 0) {
        free(e);
        e = NULL;
    }
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_org_opencell_openvox_OpenVoxNative_encoderDestroy(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    free(enc(h));
}

JNIEXPORT jlong JNICALL
Java_org_opencell_openvox_OpenVoxNative_decoderCreate(JNIEnv *env, jclass cls, jint mode)
{
    (void)env; (void)cls;
    oc_vox_dec_t *d = malloc(sizeof *d);
    if (d != NULL && oc_vox_dec_init(d, (oc_vox_mode_t)mode) != 0) {
        free(d);
        d = NULL;
    }
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL
Java_org_opencell_openvox_OpenVoxNative_decoderDestroy(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    free(dec(h));
}

JNIEXPORT void JNICALL
Java_org_opencell_openvox_OpenVoxNative_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jbyteArray block)
{
    (void)cls;
    if (h == 0) return;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *out = in != NULL ? (*env)->GetByteArrayElements(env, block, NULL) : NULL;
    if (out != NULL) { /* NULL: out of memory, with OutOfMemoryError pending for the caller */
        oc_vox_encode(enc(h), (const int16_t *)in, (uint8_t *)out);
        (*env)->ReleaseByteArrayElements(env, block, out, 0);
    }
    if (in != NULL) (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
}

/* block NULL: the block was lost, and oc_vox conceals it. */
JNIEXPORT void JNICALL
Java_org_opencell_openvox_OpenVoxNative_decode(JNIEnv *env, jclass cls, jlong h, jbyteArray block, jshortArray pcm)
{
    (void)cls;
    if (h == 0) return;
    jbyte *in = NULL;
    if (block != NULL) {
        in = (*env)->GetByteArrayElements(env, block, NULL);
        if (in == NULL) return; /* out of memory, with OutOfMemoryError pending for the caller */
    }
    jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (out != NULL) {
        oc_vox_decode(dec(h), (const uint8_t *)in, (int16_t *)out);
        (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
    }
    if (in != NULL) (*env)->ReleaseByteArrayElements(env, block, in, JNI_ABORT);
}

/* The decoder's counters (oc_vox_dec_stats_t) into out[8], in the struct's order. */
JNIEXPORT void JNICALL
Java_org_opencell_openvox_OpenVoxNative_decoderStats(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    if (h == 0) return;
    const oc_vox_dec_stats_t *s = oc_vox_dec_stats(dec(h));
    jint v[8] = {
        (jint)s->blocks, (jint)s->lost_blocks, (jint)s->concealed_frames, (jint)s->toggle_gaps,
        (jint)s->golay_fixed, (jint)s->golay_failed, (jint)s->hamming_fixed, (jint)s->crc_failed,
    };
    (*env)->SetIntArrayRegion(env, out, 0, 8, v);
}
