package com.excp.podroid.management

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolV1Test {
    @Test
    fun `exact read grammar and uint32 framing are accepted`() {
        ManagementOperation.entries.filterNot { it.mutation }.forEach { operation ->
            val payload = request(operation.wireName, "{}")
            val parsed = ManagementRequestParser.parseFramed(frame(payload))
            assertEquals(operation, parsed.operation)
            assertEquals(null, parsed.ifGeneration)
            assertTrue(parsed.payloadSha256.matches(Regex("[0-9a-f]{64}")))
            val withWhitespace = ManagementRequestParser.parseFramed(frame(" \t$payload\r\n"))
            assertEquals(operation, withWhitespace.operation)
            assertTrue(withWhitespace.payloadSha256 != parsed.payloadSha256)
        }
        val encoded = ManagementFrameCodec.encodeResponse("{}".toByteArray())
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 123, 125), encoded)
    }

    @Test
    fun `mutations require one non-negative generation`() {
        ManagementOperation.entries.filter { it.mutation }.forEach { operation ->
            val parsed = ManagementRequestParser.parseFramed(
                frame(request(operation.wireName, "{\"if_generation\":0}")),
            )
            assertEquals(0L, parsed.ifGeneration)
            assertFails(frame(request(operation.wireName, "{}")))
            assertFails(frame(request(operation.wireName, "{\"if_generation\":-1}")))
            assertFails(frame(request(operation.wireName, "{\"if_generation\":1,\"extra\":0}")))
        }
        assertFails(frame(request("vm.default.status", "{\"if_generation\":0}")))
    }

    @Test
    fun `request id version operation and object schema fail closed`() {
        assertEquals(
            ManagementErrorCode.UNKNOWN_OPERATION,
            parseFailure(frame(request("future.operation", "{}"))).errorCode,
        )
        assertEquals(
            ManagementErrorCode.UNSUPPORTED_VERSION,
            parseFailure(frame(request("protocol.describe", "{}", version = 2))).errorCode,
        )
        assertFails(frame(request("protocol.describe", "{}", id = "550E8400-e29b-41d4-a716-446655440000")))
        assertFails(frame(request("protocol.describe", "{}", id = "550e8400-e29b-11d4-a716-446655440000")))
        assertFails(frame("{\"version\":1,\"version\":1,\"request_id\":\"550e8400-e29b-41d4-a716-446655440000\",\"operation\":\"protocol.describe\",\"parameters\":{}}"))
        assertFails(frame(request("protocol.describe", "{}").dropLast(1) + ",\"extra\":true}"))
        assertFails(frame(request("protocol.describe", "[]")))
        assertFails(frame(request("protocol.describe", "{\"x\":1.0}")))
    }

    @Test
    fun `framing UTF8 and response bounds reject before allocation or effects`() {
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(byteArrayOf(0, 0, 0)) }.isFailure)
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(byteArrayOf(-1, -1, -1, -1)) }.isFailure)
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(byteArrayOf(0, 0, 0, 0)) }.isFailure)
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(byteArrayOf(0, 0, 0, 2, 1)) }.isFailure)
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(byteArrayOf(0, 0, 0, 1, 1, 2)) }.isFailure)
        assertTrue(runCatching { ManagementRequestParser.parsePayload(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching {
            ManagementFrameCodec.encodeResponse(ByteArray(ManagementLimits.MAX_RESPONSE_BYTES + 1))
        }.isFailure)
        val oversized = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(ManagementLimits.MAX_REQUEST_BYTES + 1).array()
        assertTrue(runCatching { ManagementFrameCodec.decodeRequest(oversized) }.isFailure)
    }

    @Test
    fun `error retry and exit mappings are fixed and complete`() {
        assertTrue(ManagementErrorCode.GENERATION_MISMATCH.retryable.not())
        assertTrue(ManagementErrorCode.INDETERMINATE.retryable.not())
        assertTrue(ManagementErrorCode.BUSY.retryable)
        assertTrue(ManagementErrorCode.AUDIT_UNAVAILABLE.retryable)
        assertEquals("generation_mismatch", ManagementErrorCode.GENERATION_MISMATCH.wireName)
        assertEquals((0..14).toList(), ManagementErrorCode.entries.map { it.ordinal })
        assertEquals(listOf(0, 64, 69, 70, 75), ManagementExecExitCode.entries.map { it.code })
        assertEquals(4, ManagementOperation.entries.size)
    }

    private fun request(
        operation: String,
        parameters: String,
        version: Int = 1,
        id: String = "550e8400-e29b-41d4-a716-446655440000",
    ) = "{\"version\":$version,\"request_id\":\"$id\",\"operation\":\"$operation\",\"parameters\":$parameters}"

    private fun frame(payload: String): ByteArray = frame(payload.toByteArray())
    private fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(4 + payload.size)
        .order(ByteOrder.BIG_ENDIAN).putInt(payload.size).put(payload).array()
    private fun assertFails(frame: ByteArray) = assertTrue(runCatching { ManagementRequestParser.parseFramed(frame) }.isFailure)
    private fun parseFailure(frame: ByteArray): ManagementProtocolException = try {
        ManagementRequestParser.parseFramed(frame)
        error("expected parser failure")
    } catch (failure: ManagementProtocolException) {
        failure
    }
}
