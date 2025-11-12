#include <jni.h>

#ifndef NATIVE_LIB_H
	#define NATIVE_LIB_H

	#ifdef __cplusplus
	extern "C" {
	#endif

	JNIEXPORT jboolean JNICALL
	Java_local_test_camtest_protocol_NativeConnection_start(JNIEnv *env, jobject thiz,jstring ip, jint width, jint height);

	JNIEXPORT void JNICALL
	Java_local_test_camtest_protocol_NativeConnection_stop(JNIEnv *env, jobject thiz);


	#ifdef __cplusplus
	}
	#endif

#endif