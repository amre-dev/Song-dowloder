/* جسر JNI بسيط لمشفّر LAME: create / encode / flush / close */
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define JF(name) Java_com_spiderstudio_songs_Lame_##name

JNIEXPORT jlong JNICALL JF(create)(JNIEnv *env, jclass cls, jint sampleRate, jint channels, jint kbps) {
    lame_t g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(g, kbps);
    lame_set_VBR(g, vbr_off);          /* CBR: المدة بتنحسب صح بدون ترويسة Xing */
    lame_set_quality(g, 2);            /* 2 = جودة عالية */
    lame_set_bWriteVbrTag(g, 0);       /* ما منرجع نكتب بأول الملف */
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong)(intptr_t)g;
}

JNIEXPORT jint JNICALL JF(encode)(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jint frames, jbyteArray out) {
    lame_t g = (lame_t)(intptr_t)handle;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *ob = (*env)->GetByteArrayElements(env, out, NULL);
    jint outLen = (*env)->GetArrayLength(env, out);
    int n;
    if (lame_get_num_channels(g) == 1) {
        n = lame_encode_buffer(g, in, in, frames, (unsigned char *)ob, outLen);
    } else {
        n = lame_encode_buffer_interleaved(g, in, frames, (unsigned char *)ob, outLen);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL JF(flush)(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    lame_t g = (lame_t)(intptr_t)handle;
    jbyte *ob = (*env)->GetByteArrayElements(env, out, NULL);
    jint outLen = (*env)->GetArrayLength(env, out);
    int n = lame_encode_flush(g, (unsigned char *)ob, outLen);
    (*env)->ReleaseByteArrayElements(env, out, ob, 0);
    return n;
}

JNIEXPORT void JNICALL JF(close)(JNIEnv *env, jclass cls, jlong handle) {
    lame_t g = (lame_t)(intptr_t)handle;
    if (g) lame_close(g);
}
