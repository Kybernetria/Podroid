// Podroid debug-only JNI linkage probe for the official libtailscale C API.
// This file is project-owned; third_party/libtailscale remains unmodified.

#include <jni.h>

#include "tailscale.h"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_excp_podroid_transport_tailscale_NativeDebugProbe_createAndClose(
        JNIEnv *env,
        jclass clazz) {
    (void)env;
    (void)clazz;

    tailscale server = tailscale_new();
    if (server < 0) {
        return server;
    }
    return tailscale_close(server);
}
