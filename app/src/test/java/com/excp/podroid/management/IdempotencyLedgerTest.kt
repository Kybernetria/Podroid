package com.excp.podroid.management

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyLedgerTest {
    @Test
    fun `strict reservation execution completion and completed replay preserve response`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore())
        val request = request("1".repeat(64))
        val key = key(request)
        assertEquals(ReservationDecision.Reserved, ledger.reserve(key, request))
        assertEquals(ReservationDecision.NoReplay(LedgerState.RESERVED), ledger.reserve(key, request))
        ledger.markExecuting(key, request.payloadSha256)
        assertEquals(ReservationDecision.NoReplay(LedgerState.EXECUTING), ledger.reserve(key, request))
        val response = byteArrayOf(0, 0, 0, 2, 123, 125)
        ledger.complete(key, request.payloadSha256, response)
        response[4] = 0
        val replay = ledger.reserve(key, request) as ReservationDecision.Completed
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 123, 125), replay.responseFrame)
    }

    @Test
    fun `same UUID with different payload or operation is a permanent hash conflict`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore())
        val first = request("1".repeat(64))
        assertEquals(ReservationDecision.Reserved, ledger.reserve(key(first), first))
        assertEquals(
            ReservationDecision.HashConflict(LedgerState.RESERVED),
            ledger.reserve(key(first), request("2".repeat(64))),
        )
        val changedOperation = request("1".repeat(64), ManagementOperation.VM_DEFAULT_STOP)
        assertEquals(
            ReservationDecision.HashConflict(LedgerState.RESERVED),
            ledger.reserve(key(first), changedOperation),
        )
    }

    @Test
    fun `restart rejects pre-effect reservations and makes possible effects indeterminate without replay`() {
        val store = InMemoryAtomicIdempotencyStore()
        val ledger = ManagementIdempotencyLedger(store)
        val reserved = request("1".repeat(64), id = "550e8400-e29b-41d4-a716-446655440000")
        val executing = request("2".repeat(64), id = "550e8400-e29b-41d4-a716-446655440001")
        ledger.reserve(key(reserved), reserved)
        ledger.reserve(key(executing), executing)
        ledger.markExecuting(key(executing), executing.payloadSha256)

        assertEquals(2, ledger.recoverAfterRestart())
        assertEquals(
            ReservationDecision.Rejected(ManagementErrorCode.INTERRUPTED),
            ledger.reserve(key(reserved), reserved),
        )
        assertEquals(ReservationDecision.Indeterminate, ledger.reserve(key(executing), executing))
        assertEquals(0, ledger.recoverAfterRestart())
        assertEquals(setOf(LedgerState.REJECTED, LedgerState.INDETERMINATE), store.snapshot().map { it.state }.toSet())
    }

    @Test
    fun `rejected result is stable and illegal transitions fail explicitly`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore())
        val request = request("1".repeat(64))
        ledger.reserve(key(request), request)
        ledger.reject(key(request), request.payloadSha256, ManagementErrorCode.GENERATION_MISMATCH)
        assertEquals(
            ReservationDecision.Rejected(ManagementErrorCode.GENERATION_MISMATCH),
            ledger.reserve(key(request), request),
        )
        assertTrue(runCatching { ledger.markExecuting(key(request), request.payloadSha256) }.isFailure)
        assertTrue(runCatching { ledger.complete(key(request), request.payloadSha256, byteArrayOf(1)) }.isFailure)
    }

    @Test
    fun `executing cannot become rejected and malformed completed frames are never persisted`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore())
        val request = request("1".repeat(64))
        ledger.reserve(key(request), request)
        ledger.markExecuting(key(request), request.payloadSha256)
        assertTrue(runCatching {
            ledger.reject(key(request), request.payloadSha256, ManagementErrorCode.INTERNAL_ERROR)
        }.isFailure)
        listOf(
            byteArrayOf(1),
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, 0, 0, 2, 1),
            byteArrayOf(-1, -1, -1, -1),
        ).forEach { malformed ->
            assertTrue(runCatching { ledger.complete(key(request), request.payloadSha256, malformed) }.isFailure)
        }
        ledger.markIndeterminate(key(request), request.payloadSha256)
        assertEquals(ReservationDecision.Indeterminate, ledger.reserve(key(request), request))
    }

    @Test
    fun `capacity is hard bounded without unsafe eviction`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore(), capacity = 1)
        val first = request("1".repeat(64), id = "550e8400-e29b-41d4-a716-446655440000")
        val second = request("2".repeat(64), id = "550e8400-e29b-41d4-a716-446655440001")
        assertEquals(ReservationDecision.Reserved, ledger.reserve(key(first), first))
        assertEquals(ReservationDecision.CapacityExceeded, ledger.reserve(key(second), second))
        assertEquals(ReservationDecision.NoReplay(LedgerState.RESERVED), ledger.reserve(key(first), first))
    }

    @Test
    fun `concurrent duplicates have exactly one reservation winner`() {
        val ledger = ManagementIdempotencyLedger(InMemoryAtomicIdempotencyStore())
        val request = request("1".repeat(64))
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ReservationDecision>())
        val pool = Executors.newFixedThreadPool(8)
        repeat(64) {
            pool.submit {
                start.await()
                results += ledger.reserve(key(request), request)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, results.count { it == ReservationDecision.Reserved })
        assertEquals(63, results.count { it == ReservationDecision.NoReplay(LedgerState.RESERVED) })
    }

    private fun request(
        hash: String,
        operation: ManagementOperation = ManagementOperation.VM_DEFAULT_START,
        id: String = "550e8400-e29b-41d4-a716-446655440000",
    ) = ManagementRequest(ManagementRequestId(id), operation, 0, hash)

    private fun key(request: ManagementRequest) = LedgerKey(
        ManagementClientIdentity("a".repeat(64)),
        request.requestId,
    )
}
