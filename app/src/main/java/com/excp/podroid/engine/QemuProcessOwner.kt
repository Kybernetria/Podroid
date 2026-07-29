/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Owns the cancellation boundary between child creation, atomic publication,
 * forced destruction, and waitFor-style reaping.
 */
internal class QemuProcessOwner<P : Any, R>(
    private val commit: (P) -> Boolean,
    private val forceDestroy: (P) -> Unit,
    private val reap: suspend (P) -> R,
) {
    private var committed: P? = null

    /** A created child is committed or forcibly destroyed and reaped before this returns. */
    suspend fun createAndCommit(create: suspend () -> P?): P? = withContext(NonCancellable) {
        val child = create() ?: return@withContext null
        val accepted = try {
            commit(child)
        } catch (failure: Throwable) {
            try {
                forceDestroyAndReap(child)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        if (!accepted) {
            forceDestroyAndReap(child)
            return@withContext null
        }
        synchronized(this@QemuProcessOwner) {
            check(committed == null) { "A QEMU child is already committed" }
            committed = child
        }
        child
    }

    /** Await normal child exit and forget ownership only after reap completes. */
    suspend fun awaitCommittedReap(child: P): R {
        synchronized(this) {
            check(committed === child) { "Cannot reap an unowned QEMU child" }
        }
        // Keep the lifetime wait cancellable so cancellation can enter the
        // force-destroy path. If cancellation discards a completed reap result,
        // ownership deliberately remains and that path safely reaps again.
        val result = reap(child)
        return withContext(NonCancellable) {
            synchronized(this@QemuProcessOwner) {
                if (committed === child) committed = null
            }
            result
        }
    }

    /** Force and reap a published child during cancellation or exceptional exit. */
    suspend fun forceDestroyAndReapCommitted(): R? = withContext(NonCancellable) {
        val child = synchronized(this@QemuProcessOwner) { committed } ?: return@withContext null
        val result = forceDestroyAndReap(child)
        synchronized(this@QemuProcessOwner) {
            if (committed === child) committed = null
        }
        result
    }

    private suspend fun forceDestroyAndReap(child: P): R {
        var destroyFailure: Throwable? = null
        try {
            forceDestroy(child)
        } catch (failure: Throwable) {
            destroyFailure = failure
        }

        val result = try {
            reap(child)
        } catch (reapFailure: Throwable) {
            destroyFailure?.let(reapFailure::addSuppressed)
            throw reapFailure
        }
        destroyFailure?.let { throw it }
        return result
    }
}
