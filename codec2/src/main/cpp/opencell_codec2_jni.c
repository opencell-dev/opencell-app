/* JNI glue for org.opencell.codec2.Codec2Native: one struct CODEC2 per handle.
 * The Kotlin side checks every array length before calling, so these only
 * guard against a null handle. */
#include <jni.h>
#include <stdint.h>

#include "codec2.h"

static struct CODEC2 *c2(jlong h) { return (struct CODEC2 *)(intptr_t)h; }

JNIEXPORT jlong JNICALL
Java_org_opencell_codec2_Codec2Native_create(JNIEnv *env, jclass cls, jint mode)
{
    (void)env; (void)cls;
    return (jlong)(intptr_t)codec2_create(mode);
}

JNIEXPORT void JNICALL
Java_org_opencell_codec2_Codec2Native_destroy(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    if (h != 0) codec2_destroy(c2(h));
}

JNIEXPORT jint JNICALL
Java_org_opencell_codec2_Codec2Native_samplesPerFrame(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    return h != 0 ? codec2_samples_per_frame(c2(h)) : 0;
}

JNIEXPORT jint JNICALL
Java_org_opencell_codec2_Codec2Native_bytesPerFrame(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    return h != 0 ? codec2_bytes_per_frame(c2(h)) : 0;
}

JNIEXPORT void JNICALL
Java_org_opencell_codec2_Codec2Native_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jbyteArray bits)
{
    (void)cls;
    if (h == 0) return;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *out = (*env)->GetByteArrayElements(env, bits, NULL);
    codec2_encode(c2(h), (unsigned char *)out, (short *)in);
    (*env)->ReleaseByteArrayElements(env, bits, out, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_org_opencell_codec2_Codec2Native_decode(JNIEnv *env, jclass cls, jlong h, jbyteArray bits, jshortArray pcm)
{
    (void)cls;
    if (h == 0) return;
    jbyte *in = (*env)->GetByteArrayElements(env, bits, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
    codec2_decode(c2(h), (short *)out, (const unsigned char *)in);
    (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
    (*env)->ReleaseByteArrayElements(env, bits, in, JNI_ABORT);
}
