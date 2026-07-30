/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.tailscale

import com.excp.podroid.transport.api.TransportEndpoint
import com.excp.podroid.transport.api.TransportProtocol
import java.net.URI
import java.nio.file.Path
import java.util.Arrays

/** Mutable one-use secret. Ownership transfers only for the duration of a binding call. */
class OneUseAuthKey private constructor(private val utf8: ByteArray) : AutoCloseable {
    private var closed = false

    fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!closed) { "auth key is closed" }
        val operationCopy = utf8.copyOf()
        return try {
            block(operationCopy)
        } finally {
            Arrays.fill(operationCopy, 0)
        }
    }

    override fun close() {
        if (!closed) {
            Arrays.fill(utf8, 0)
            closed = true
        }
    }

    companion object {
        const val MAX_KEY_BYTES = 512
        fun copyOf(utf8: ByteArray): OneUseAuthKey {
            require(utf8.size in 8..MAX_KEY_BYTES && utf8.none { it == 0.toByte() })
            return OneUseAuthKey(utf8.copyOf())
        }
    }
}

data class RawServerConfiguration(
    val stateDirectory: Path,
    val hostname: String,
    val controlUrl: URI,
    val ephemeral: Boolean = false,
) {
    init {
        require(stateDirectory.isAbsolute) { "state directory must be absolute" }
        require(hostname.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")))
        require(controlUrl.scheme == "https" && controlUrl.host != null)
    }
}

class RawLoopbackCredentials private constructor(
    val address: TransportEndpoint,
    private val proxyCredential: ByteArray,
    private val localApiCredential: ByteArray,
) : AutoCloseable {
    private var closed = false

    fun <T> useProxyCredential(block: (ByteArray) -> T): T = useCredential(proxyCredential, block)

    fun <T> useLocalApiCredential(block: (ByteArray) -> T): T =
        useCredential(localApiCredential, block)

    private fun <T> useCredential(credential: ByteArray, block: (ByteArray) -> T): T {
        check(!closed) { "loopback credentials are closed" }
        val operationCopy = credential.copyOf()
        return try {
            block(operationCopy)
        } finally {
            Arrays.fill(operationCopy, 0)
        }
    }

    override fun close() {
        if (!closed) {
            Arrays.fill(proxyCredential, 0)
            Arrays.fill(localApiCredential, 0)
            closed = true
        }
    }

    companion object {
        const val CREDENTIAL_BYTES = 32

        fun copyOf(
            address: TransportEndpoint,
            proxyCredential: ByteArray,
            localApiCredential: ByteArray,
        ): RawLoopbackCredentials {
            require(proxyCredential.size == CREDENTIAL_BYTES)
            require(localApiCredential.size == CREDENTIAL_BYTES)
            return RawLoopbackCredentials(
                address,
                proxyCredential.copyOf(),
                localApiCredential.copyOf(),
            )
        }
    }
}

/**
 * Project-owned mirror of the reviewed public C surface. Every returned handle
 * is exclusively owned by its caller and must be closed exactly once. There is
 * intentionally no JNI/JNA implementation or production composition binding.
 */
interface RawLibtailscaleBindings {
    fun newServer(): OwnedRawServer
}

interface OwnedRawServer : AutoCloseable {
    fun configure(configuration: RawServerConfiguration)
    fun setAuthKey(authKey: OneUseAuthKey)
    fun start()
    fun listen(endpoint: TransportEndpoint): OwnedRawListener
    fun dial(endpoint: TransportEndpoint): OwnedRawConnection
    fun loopback(): RawLoopbackCredentials
    override fun close()
}

interface OwnedRawListener : AutoCloseable {
    val localEndpoint: TransportEndpoint
    fun accept(): OwnedRawConnection
    override fun close()
}

interface OwnedRawConnection : AutoCloseable {
    fun remoteAddress(): TransportEndpoint
    fun read(destination: ByteArray, offset: Int, length: Int): Int
    fun write(source: ByteArray, offset: Int, length: Int)
    override fun close()
}

internal fun requireRawNetwork(protocol: TransportProtocol) {
    require(protocol == TransportProtocol.TCP) { "current raw binding contract supports TCP only" }
}
