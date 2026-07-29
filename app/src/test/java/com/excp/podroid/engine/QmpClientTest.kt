/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.engine.QmpClient.Companion.QmpVerdict
import com.excp.podroid.engine.QmpClient.Companion.classifyQmpFields
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins QMP response classification. Port-forward commands run via
 * `human-monitor-command`, whose failures arrive as a {"return":"<error text>"}
 * envelope (no QMP-level error, no exception), so a naive
 * Result.success(JSONObject(line)) reports a failed forward as applied.
 *
 * Tests target the pure [classifyQmpFields] (no org.json) so they run as plain
 * JVM unit tests; the thin classifyQmpResponse(JSONObject) adapter just maps its
 * verdict onto Result/null.
 */
class QmpClientTest {

    @Test
    fun `top-level error envelope is a failure`() {
        // {"error":{"class":"GenericError","desc":"bad command"}}
        val verdict = classifyQmpFields(hasError = true, hasEvent = false, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `successful return object is a success`() {
        // {"return":{}}
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = emptyMap<String, Any>())
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `async event line is skipped, not treated as a response`() {
        // {"event":"SHUTDOWN",...} — keep reading for the real reply.
        val verdict = classifyQmpFields(hasError = false, hasEvent = true, returnValue = null)
        assertEquals(QmpVerdict.SkipEvent, verdict)
    }

    @Test
    fun `human-monitor return carrying a hostfwd error is a failure`() {
        // hostfwd_add on a busy port: {"return":"could not set up host forwarding rule ..."}
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "could not set up host forwarding rule 'tcp::8080-:80'\r\n",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor return with capitalized Could not is a failure`() {
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "Could not set up host forwarding rule 'tcp::8080-:80'",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor return with lowercase could not is a failure`() {
        // Casing is not guaranteed across QEMU/SLIRP versions; a lowercase
        // "could not ..." that isn't the "set up" phrasing must still classify
        // as a failure rather than silently reporting the forward as applied.
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "could not find a free port for host forwarding rule",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor empty return string is a success`() {
        // A successful hostfwd_add returns an empty string.
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = "")
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `absolute monotonic deadline is shared across phases`() {
        var now = 1_000_000L
        val budget = QmpTransactionBudget(10, 32, 64, 2) { now }
        assertEquals(10, budget.remainingTimeoutMs())
        now += 9_500_000L
        assertEquals(1, budget.remainingTimeoutMs())
        now += 500_000L
        expectIOException { budget.remainingTimeoutMs() }
    }

    @Test
    fun `line and transaction byte budgets are independent and total bounded`() {
        val lineBudget = QmpTransactionBudget(100, 4, 16, 1)
        expectIOException {
            QmpClient.readBoundedLine(ByteArrayInputStream("12345\n".toByteArray()), lineBudget)
        }

        val totalBudget = QmpTransactionBudget(100, 4, 7, 1)
        assertEquals("abc", QmpClient.readBoundedLine(ByteArrayInputStream("abc\n".toByteArray()), totalBudget))
        expectIOException {
            QmpClient.readBoundedLine(ByteArrayInputStream("def\n".toByteArray()), totalBudget)
        }
    }

    @Test
    fun `event budget rejects unbounded asynchronous event streams`() {
        val budget = QmpTransactionBudget(100, 16, 64, 2)
        budget.consumeEvent()
        budget.consumeEvent()
        expectIOException { budget.consumeEvent() }
    }

    @Test
    fun `cancellation model closes the active socket`() {
        val job = Job()
        val closed = AtomicBoolean(false)
        val handle = QmpClient.closeOnCancellation(job) { closed.set(true) }
        job.cancel()
        assertTrue(closed.get())
        handle?.dispose()
    }

    @Test
    fun `peer authentication precedes every QMP read or command write`() {
        val source = File("src/main/java/com/excp/podroid/engine/QmpClient.kt").readText()
        val executeIo = source.substringAfter("private suspend fun executeIo(")
            .substringBefore("private fun writeCommand(")
        val verification = executeIo.indexOf("peerVerifier?.verify(socket)")
        assertTrue(verification >= 0)
        assertTrue(verification < executeIo.indexOf("socket.inputStream"))
        assertTrue(verification < executeIo.indexOf("writeCommand("))
    }

    @Test
    fun `event takes precedence is not consulted when error present`() {
        // Defensive: an error envelope is terminal even if an event key co-occurs.
        val verdict = classifyQmpFields(hasError = true, hasEvent = true, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }
}
