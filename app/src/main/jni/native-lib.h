#pragma once
#include <jni.h>

#ifndef NATIVE_LIB_H
#define NATIVE_LIB_H

#ifdef __cplusplus
extern "C" {
#endif

extern JavaVM* gJavaVM;

JNIEXPORT jboolean JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_start(JNIEnv *env, jobject thiz, jint destPort);

JNIEXPORT void JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_stop(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_setSurface(JNIEnv *env, jobject thiz, jobject surface);

JNIEXPORT jbyteArray JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_getNextFrame(JNIEnv *env, jobject thiz);


#ifdef __cplusplus
}
#endif

#endif