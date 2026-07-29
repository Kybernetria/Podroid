/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

/**
 * Constrained identifier for a Podroid VM instance.
 *
 * Ticket #6 deliberately supports one instance only. Keeping construction behind
 * [parse] prevents path separators, traversal tokens, and future unreviewed IDs
 * from entering filesystem or engine APIs.
 */
@JvmInline
value class VmId private constructor(val serialized: String) {
    override fun toString(): String = serialized

    companion object {
        val DEFAULT = VmId("default")

        fun parse(raw: String): VmId {
            require(raw == DEFAULT.serialized) {
                "Unsupported VM id: only '${DEFAULT.serialized}' is accepted"
            }
            return DEFAULT
        }
    }
}
