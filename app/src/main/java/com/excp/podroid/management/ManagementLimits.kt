/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

/** Closed, non-configurable v1 resource budget. Raising a value requires protocol review. */
object ManagementLimits {
    const val PROTOCOL_VERSION = 1
    const val MAX_REQUEST_BYTES = 4_096
    const val MAX_RESPONSE_BYTES = 16_384
    const val FRAME_HEADER_BYTES = 4
    const val MAX_SESSIONS = 4
    const val MAX_CHANNELS_PER_CONNECTION = 2
    const val MAX_REQUESTS_PER_EXEC = 1
    const val REQUEST_DEADLINE_MILLIS = 5_000L
    const val SESSION_DEADLINE_MILLIS = 15_000L
    const val MAX_CERT_VALIDITY_SECONDS = 24L * 60L * 60L
    const val MAX_KEY_ID_CHARS = 128
    const val MAX_TRANSPORT_IDENTITY_CHARS = 256
    const val MAX_LEDGER_ENTRIES = 1_024
    const val MAX_AUDIT_RECORDS = 4_096
    const val MAX_AUDIT_FIELD_CHARS = 128

    const val SSH_USERNAME = "podroid-management"
    const val EXEC_COMMAND = "podroid-management-v1"
    const val GUEST_VIRTUAL_HOST = "vm/default/ssh"
    const val GUEST_VIRTUAL_PORT = 22
    const val GUEST_LOOPBACK_HOST = "127.0.0.1"
    const val GUEST_LOOPBACK_PORT = 9_922
}
