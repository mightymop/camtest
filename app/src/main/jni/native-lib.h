#include <jni.h>

#ifndef NATIVE_LIB_H
#define NATIVE_LIB_H

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_start(JNIEnv *env, jobject thiz, jstring destIp,
                                                       jint destPort, jboolean juseRfc2435,
                                                       jint jfps);

JNIEXPORT void JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_stop(JNIEnv *env, jobject thiz);


#ifdef __cplusplus
}
#endif

#endif