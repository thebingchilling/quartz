// JNI glue for app.quartz.engine.mihomo.MihomoNative. Go exports are in
// engine.go; this file only converts strings and calls back into Kotlin for
// VpnService.protect().
#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

static JavaVM *vm;
static jobject protector;   // global ref to a SocketProtector
static jmethodID protect_id;

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    vm = jvm;
    return JNI_VERSION_1_6;
}

// Called from Go threads: attach if needed, call protector.protect(fd).
int quartz_protect(int fd) {
    if (protector == NULL) return 1;
    JNIEnv *env;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return 0;
#else  // desktop JDK headers (host smoke tests) declare void **
        if ((*vm)->AttachCurrentThread(vm, (void **) &env, NULL) != JNI_OK) return 0;
#endif
        attached = 1;
    }
    jboolean ok = (*env)->CallBooleanMethod(env, protector, protect_id, fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
    return ok == JNI_TRUE;
}

static jstring to_jstring(JNIEnv *env, char *s) {
    if (s == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_quartz_engine_mihomo_MihomoNative_start(JNIEnv *env, jclass cls, jstring home, jstring config,
                                                 jint fd, jstring controller, jstring secret, jobject p) {
    if (protector != NULL) (*env)->DeleteGlobalRef(env, protector);
    protector = (*env)->NewGlobalRef(env, p);
    jclass pc = (*env)->GetObjectClass(env, p);
    protect_id = (*env)->GetMethodID(env, pc, "protect", "(I)Z");
    const char *h = (*env)->GetStringUTFChars(env, home, NULL);
    const char *c = (*env)->GetStringUTFChars(env, config, NULL);
    const char *ctl = (*env)->GetStringUTFChars(env, controller, NULL);
    const char *sec = (*env)->GetStringUTFChars(env, secret, NULL);
    char *err = quartzMihomoStart((char *) h, (char *) c, fd, (char *) ctl, (char *) sec);
    (*env)->ReleaseStringUTFChars(env, home, h);
    (*env)->ReleaseStringUTFChars(env, config, c);
    (*env)->ReleaseStringUTFChars(env, controller, ctl);
    (*env)->ReleaseStringUTFChars(env, secret, sec);
    return to_jstring(env, err);
}

JNIEXPORT void JNICALL Java_app_quartz_engine_mihomo_MihomoNative_stop(JNIEnv *env, jclass cls) {
    quartzMihomoStop();
}

JNIEXPORT jstring JNICALL
Java_app_quartz_engine_mihomo_MihomoNative_validate(JNIEnv *env, jclass cls, jstring home, jstring config) {
    const char *h = (*env)->GetStringUTFChars(env, home, NULL);
    const char *c = (*env)->GetStringUTFChars(env, config, NULL);
    char *err = quartzMihomoValidate((char *) h, (char *) c);
    (*env)->ReleaseStringUTFChars(env, home, h);
    (*env)->ReleaseStringUTFChars(env, config, c);
    return to_jstring(env, err);
}

JNIEXPORT jstring JNICALL Java_app_quartz_engine_mihomo_MihomoNative_version(JNIEnv *env, jclass cls) {
    return to_jstring(env, quartzMihomoVersion());
}
