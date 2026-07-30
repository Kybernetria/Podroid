// Podroid debug-only JNI linkage probe for the official libtailscale C API.
// This file is project-owned; third_party/libtailscale remains unmodified.

#include <jni.h>
#include <limits.h>

#include "tailscale.h"

/*
 * This branch is unreachable for a valid server handle, but keeps every
 * reviewed transport symbol in the link graph. The Android linker build then
 * fails if the pinned official C surface drops or renames one of them.
 */
static int link_required_transport_surface(tailscale server) {
    if (server != INT_MIN) {
        return 0;
    }
    tailscale_listener listener = -1;
    tailscale_conn connection = -1;
    char buffer[128] = {0};
    char proxy_credential[33] = {0};
    char local_api_credential[33] = {0};
    int result = 0;
    result |= tailscale_set_dir(server, "/unreachable");
    result |= tailscale_set_hostname(server, "unreachable");
    result |= tailscale_set_authkey(server, "unreachable");
    result |= tailscale_set_control_url(server, "https://unreachable.invalid");
    result |= tailscale_set_ephemeral(server, 1);
    result |= tailscale_set_logfd(server, -1);
    result |= tailscale_start(server);
    result |= tailscale_up(server);
    result |= tailscale_getips(server, buffer, sizeof(buffer));
    result |= tailscale_listen(server, "tcp", ":1", &listener);
    result |= tailscale_accept(listener, &connection);
    result |= tailscale_getremoteaddr(listener, connection, buffer, sizeof(buffer));
    result |= tailscale_dial(server, "tcp", "127.0.0.1:1", &connection);
    result |= tailscale_loopback(
        server,
        buffer,
        sizeof(buffer),
        proxy_credential,
        local_api_credential);
    result |= tailscale_errmsg(server, buffer, sizeof(buffer));
    return result;
}

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
    int surface_result = link_required_transport_surface(server);
    if (surface_result != 0) {
        (void)tailscale_close(server);
        return surface_result;
    }
    return tailscale_close(server);
}
