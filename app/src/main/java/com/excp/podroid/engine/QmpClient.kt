/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Typed QMP capability exposed to engine consumers. Raw command execution stays private. */
interface QmpController {
    suspend fun systemPowerdown(): Result<Unit>
    suspend fun queryStatus(): Result<String>
    suspend fun queryVersion(): Result<Triple<Int, Int, Int>>
    suspend fun addPortForward(hostPort: Int, guestPort: Int, protocol: String, loopbackOnly: Boolean): Result<Unit>
    suspend fun removePortForward(hostPort: Int, protocol: String, loopbackOnly: Boolean): Result<Unit>
    suspend fun attachUsb(fd: FileDescriptor, qemuId: String): Result<Int>
    suspend fun detachUsb(fdSetId: Int, qemuId: String): Result<Unit>
}

/**
 * Budget shared by every phase of one QMP transaction. The clock is monotonic;
 * greeting, capability negotiation, events, and the command reply all consume
 * the same absolute deadline and byte/event ceilings.
 */
internal class QmpTransactionBudget(
    timeoutMs: Long,
    private val maxLineBytes: Int,
    private val maxTotalBytes: Int,
    private val maxEvents: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadlineNanos = nanoTime() + timeoutMs * 1_000_000L
    private var totalBytes = 0
    private var events = 0

    init {
        require(timeoutMs > 0 && maxLineBytes > 0 && maxTotalBytes >= maxLineBytes && maxEvents >= 0)
    }

    fun remainingTimeoutMs(): Int {
        val remaining = deadlineNanos - nanoTime()
        if (remaining <= 0) throw IOException("QMP transaction deadline exceeded")
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun consumeByte(lineBytes: Int) {
        if (lineBytes > maxLineBytes) throw IOException("QMP response line exceeds $maxLineBytes bytes")
        totalBytes++
        if (totalBytes > maxTotalBytes) throw IOException("QMP transaction exceeds $maxTotalBytes response bytes")
    }

    fun consumeEvent() {
        events++
        if (events > maxEvents) throw IOException("QMP transaction exceeds $maxEvents asynchronous events")
    }
}

internal class QmpClient(
    private val socketPath: String,
    private val timeoutMs: Long = SOCKET_TIMEOUT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) : QmpController {

    companion object {
        private const val TAG = "QmpClient"
        internal const val SOCKET_TIMEOUT_MS = 5_000L
        internal const val MAX_QMP_LINE_BYTES = 256 * 1024
        internal const val MAX_QMP_TOTAL_BYTES = 512 * 1024
        internal const val MAX_QMP_EVENTS = 128
        private const val MAX_COMMAND_BYTES = 16 * 1024

        sealed class QmpVerdict {
            object Success : QmpVerdict()
            data class Failure(val reason: String) : QmpVerdict()
            object SkipEvent : QmpVerdict()
        }

        fun classifyQmpFields(hasError: Boolean, hasEvent: Boolean, returnValue: Any?): QmpVerdict {
            if (hasError) return QmpVerdict.Failure("QMP error")
            if (hasEvent) return QmpVerdict.SkipEvent
            if (returnValue is String && isHostfwdError(returnValue)) {
                return QmpVerdict.Failure("QMP human-monitor error: ${returnValue.trim()}")
            }
            return QmpVerdict.Success
        }

        internal fun classifyQmpResponse(json: JSONObject): Result<JSONObject>? =
            when (val verdict = classifyQmpFields(json.has("error"), json.has("event"), json.opt("return"))) {
                is QmpVerdict.Success -> Result.success(json)
                is QmpVerdict.Failure -> Result.failure(IOException(verdict.reason))
                is QmpVerdict.SkipEvent -> null
            }

        private fun isHostfwdError(returnText: String): Boolean =
            returnText.trim().contains("could not", ignoreCase = true)

        internal fun closeOnCancellation(job: Job?, close: () -> Unit): DisposableHandle? =
            job?.invokeOnCompletion { cause -> if (cause is CancellationException) close() }

        internal fun readBoundedLine(input: InputStream, budget: QmpTransactionBudget): String? {
            val bytes = java.io.ByteArrayOutputStream()
            var lineBytes = 0
            while (true) {
                budget.remainingTimeoutMs()
                val next = input.read()
                if (next < 0) return if (bytes.size() == 0) null else bytes.toByteArray().toString(Charsets.UTF_8)
                lineBytes++
                budget.consumeByte(lineBytes)
                if (next == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8)
                if (next != '\r'.code) bytes.write(next)
            }
        }
    }

    private suspend fun exec(
        command: String,
        arguments: JSONObject?,
        sendFd: FileDescriptor? = null,
    ): Result<JSONObject> = try {
        // Covers connect, bounded writes, handshake, events, and reply as one
        // operation. Cancellation closes the active socket in executeIo.
        withTimeout(timeoutMs) { executeIo(command, arguments, sendFd) }
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(IOException("QMP transaction deadline exceeded", timeout))
    }

    private suspend fun executeIo(
        command: String,
        arguments: JSONObject?,
        sendFd: FileDescriptor?,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        val cancellationHandle = closeOnCancellation(coroutineContext[Job]) {
            runCatching { socket.close() }
        }
        try {
            val budget = QmpTransactionBudget(
                timeoutMs, MAX_QMP_LINE_BYTES, MAX_QMP_TOTAL_BYTES, MAX_QMP_EVENTS, nanoTime,
            )
            socket.soTimeout = budget.remainingTimeoutMs()
            socket.connect(
                LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM),
                budget.remainingTimeoutMs(),
            )
            val input = socket.inputStream
            val output = socket.outputStream

            socket.soTimeout = budget.remainingTimeoutMs()
            val greeting = readBoundedLine(input, budget)
                ?: throw IOException("QMP connection closed before greeting")
            Log.v(TAG, "QMP greeting received (${greeting.length} chars)")

            writeCommand(socket, output, "qmp_capabilities", null, null, budget)
            readReply(input, socket, budget, "qmp_capabilities")

            writeCommand(socket, output, command, arguments, sendFd, budget)
            Result.success(readReply(input, socket, budget, command))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.e(TAG, "QMP command failed: $command", e)
            Result.failure(e)
        } finally {
            cancellationHandle?.dispose()
            runCatching { socket.close() }
        }
    }

    private fun writeCommand(
        socket: LocalSocket,
        output: java.io.OutputStream,
        command: String,
        arguments: JSONObject?,
        sendFd: FileDescriptor?,
        budget: QmpTransactionBudget,
    ) {
        budget.remainingTimeoutMs()
        val json = JSONObject().apply {
            put("execute", command)
            if (arguments != null) put("arguments", arguments)
        }.toString().toByteArray(Charsets.UTF_8)
        if (json.size > MAX_COMMAND_BYTES) throw IOException("QMP command exceeds $MAX_COMMAND_BYTES bytes")
        if (sendFd != null) socket.setFileDescriptorsForSend(arrayOf(sendFd))
        output.write(json)
        output.write('\n'.code)
        output.flush()
        budget.remainingTimeoutMs()
    }

    private fun readReply(
        input: InputStream,
        socket: LocalSocket,
        budget: QmpTransactionBudget,
        command: String,
    ): JSONObject {
        while (true) {
            socket.soTimeout = budget.remainingTimeoutMs()
            val line = readBoundedLine(input, budget)
                ?: throw IOException("QMP connection closed before a reply to $command")
            val json = JSONObject(line)
            val result = classifyQmpResponse(json)
            if (result == null) {
                budget.consumeEvent()
                continue
            }
            return result.getOrThrow()
        }
    }

    override suspend fun systemPowerdown(): Result<Unit> = exec("system_powerdown", null).map { Unit }

    /** Fixed typed orphan-cleanup command; never exposed through Binder. */
    internal suspend fun quit(): Result<Unit> = exec("quit", null).fold(
        onSuccess = { Result.success(Unit) },
        // QEMU commonly closes QMP immediately after accepting quit, before the
        // reply is read. The caller still requires endpoint disappearance.
        onFailure = { failure ->
            if (!java.io.File(socketPath).exists()) Result.success(Unit)
            else Result.failure(failure)
        },
    )

    override suspend fun queryStatus(): Result<String> = exec("query-status", null).mapCatching {
        it.getJSONObject("return").getString("status")
    }

    override suspend fun queryVersion(): Result<Triple<Int, Int, Int>> = exec("query-version", null).mapCatching {
        val qemu = it.getJSONObject("return").getJSONObject("qemu")
        Triple(qemu.getInt("major"), qemu.getInt("minor"), qemu.getInt("micro"))
    }

    override suspend fun addPortForward(
        hostPort: Int,
        guestPort: Int,
        protocol: String,
        loopbackOnly: Boolean,
    ): Result<Unit> {
        val hostAddr = if (loopbackOnly) "127.0.0.1" else ""
        val monitor = "hostfwd_add net0 ${protocol}:${hostAddr}:${hostPort}-:${guestPort}"
        return exec("human-monitor-command", JSONObject().put("command-line", monitor)).map { Unit }
    }

    override suspend fun removePortForward(
        hostPort: Int,
        protocol: String,
        loopbackOnly: Boolean,
    ): Result<Unit> {
        val hostAddr = if (loopbackOnly) "127.0.0.1" else ""
        val monitor = "hostfwd_remove net0 ${protocol}:${hostAddr}:${hostPort}"
        return exec("human-monitor-command", JSONObject().put("command-line", monitor)).map { Unit }
    }

    override suspend fun attachUsb(fd: FileDescriptor, qemuId: String): Result<Int> {
        require(qemuId.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid QEMU USB id" }
        val fdSetId = exec("add-fd", null, fd).mapCatching {
            it.getJSONObject("return").getInt("fdset-id")
        }.getOrElse { return Result.failure(it) }
        val args = JSONObject()
            .put("driver", "usb-host")
            .put("id", qemuId)
            .put("hostdevice", "/dev/fdset/$fdSetId")
        return try {
            exec("device_add", args).fold(
                onSuccess = { Result.success(fdSetId) },
                onFailure = { failure ->
                    removeFdAfterPartialAttach(fdSetId)
                    Result.failure(failure)
                },
            )
        } catch (cancelled: CancellationException) {
            // add-fd completed before this cancellable device_add. Release the
            // acquired fdset under its own bounded transaction before unwinding.
            withContext(NonCancellable) { removeFdAfterPartialAttach(fdSetId) }
            throw cancelled
        }
    }

    override suspend fun detachUsb(fdSetId: Int, qemuId: String): Result<Unit> {
        require(fdSetId >= 0) { "Invalid QMP fdset id" }
        require(qemuId.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid QEMU USB id" }
        return try {
            val deviceResult = exec("device_del", JSONObject().put("id", qemuId)).map { Unit }
            val fdResult = exec("remove-fd", JSONObject().put("fdset-id", fdSetId)).map { Unit }
            deviceResult.fold(onSuccess = { fdResult }, onFailure = { Result.failure(it) })
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { removeFdAfterPartialAttach(fdSetId) }
            throw cancelled
        }
    }

    private suspend fun removeFdAfterPartialAttach(fdSetId: Int) {
        exec("remove-fd", JSONObject().put("fdset-id", fdSetId))
            .onFailure { Log.w(TAG, "QMP remove-fd cleanup failed for fdset $fdSetId", it) }
    }

}
