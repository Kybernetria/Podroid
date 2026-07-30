/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

sealed class ManagementProtocolException(
    message: String,
    val errorCode: ManagementErrorCode,
) : IllegalArgumentException(message) {
    class InvalidFrame(message: String) :
        ManagementProtocolException(message, ManagementErrorCode.INVALID_REQUEST)
    class InvalidRequest(message: String) :
        ManagementProtocolException(message, ManagementErrorCode.INVALID_REQUEST)
    class UnsupportedVersion(message: String) :
        ManagementProtocolException(message, ManagementErrorCode.UNSUPPORTED_VERSION)
    class UnknownOperation(message: String) :
        ManagementProtocolException(message, ManagementErrorCode.UNKNOWN_OPERATION)
}

enum class ManagementOperation(val wireName: String, val mutation: Boolean) {
    PROTOCOL_DESCRIBE("protocol.describe", false),
    VM_DEFAULT_STATUS("vm.default.status", false),
    VM_DEFAULT_START("vm.default.start", true),
    VM_DEFAULT_STOP("vm.default.stop", true);

    companion object {
        fun fromWire(value: String): ManagementOperation? = entries.singleOrNull { it.wireName == value }
    }
}

data class ManagementRequestId(val value: String) {
    init { require(value.matches(CANONICAL_UUID_V4)) { "request_id must be a canonical lowercase UUIDv4" } }
    private companion object {
        val CANONICAL_UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

data class ManagementRequest(
    val requestId: ManagementRequestId,
    val operation: ManagementOperation,
    val ifGeneration: Long?,
    /** SHA-256 over the exact admitted JSON payload, used by the durable ledger. */
    val payloadSha256: String,
) {
    init {
        require((ifGeneration != null) == operation.mutation) {
            "if_generation is mandatory only for mutations"
        }
        require(ifGeneration == null || ifGeneration >= 0) { "if_generation must be non-negative" }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class ManagementErrorCode(val retryable: Boolean) {
    INVALID_REQUEST(false),
    UNSUPPORTED_VERSION(false),
    UNKNOWN_OPERATION(false),
    UNAUTHENTICATED(false),
    FORBIDDEN(false),
    GENERATION_MISMATCH(false),
    CONFLICT(false),
    BUSY(true),
    TIMEOUT(true),
    AUDIT_UNAVAILABLE(true),
    CAPACITY_EXCEEDED(true),
    PROVIDER_UNAVAILABLE(true),
    INTERRUPTED(true),
    INDETERMINATE(false),
    INTERNAL_ERROR(true),
}

enum class ManagementExecExitCode(val code: Int) {
    RESPONSE_WRITTEN(0),
    PROTOCOL_VIOLATION(64),
    SERVICE_UNAVAILABLE(69),
    INTERNAL_WITHOUT_RESPONSE(70),
    TEMPORARY_FAILURE(75),
}

/** uint32-BE, one-frame-only codec. It never allocates from an unvalidated length. */
object ManagementFrameCodec {
    fun decodeRequest(frame: ByteArray): ByteArray {
        if (frame.size < ManagementLimits.FRAME_HEADER_BYTES) {
            throw ManagementProtocolException.InvalidFrame("truncated frame header")
        }
        val length = ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        if (length !in 1L..ManagementLimits.MAX_REQUEST_BYTES.toLong()) {
            throw ManagementProtocolException.InvalidFrame("request frame length is outside the v1 bound")
        }
        if (frame.size.toLong() != ManagementLimits.FRAME_HEADER_BYTES + length) {
            throw ManagementProtocolException.InvalidFrame("request frame must contain exactly one complete payload")
        }
        return frame.copyOfRange(4, frame.size)
    }

    fun encodeResponse(payload: ByteArray): ByteArray {
        require(payload.size in 1..ManagementLimits.MAX_RESPONSE_BYTES) { "response exceeds v1 bound" }
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size).put(payload).array()
    }

    fun requireValidResponseFrame(frame: ByteArray) {
        require(frame.size >= ManagementLimits.FRAME_HEADER_BYTES) { "truncated response frame" }
        val length = ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        require(length in 1L..ManagementLimits.MAX_RESPONSE_BYTES.toLong()) {
            "response frame length is outside the v1 bound"
        }
        require(frame.size.toLong() == ManagementLimits.FRAME_HEADER_BYTES + length) {
            "response frame length does not match its payload"
        }
    }
}

object ManagementRequestParser {
    fun parseFramed(frame: ByteArray): ManagementRequest = parsePayload(ManagementFrameCodec.decodeRequest(frame))

    fun parsePayload(payload: ByteArray): ManagementRequest {
        require(payload.size in 1..ManagementLimits.MAX_REQUEST_BYTES) { "request payload size is invalid" }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: Exception) {
            throw ManagementProtocolException.InvalidRequest("request is not valid UTF-8")
        }
        val root = try {
            StrictJson.parseObject(text)
        } catch (failure: IllegalArgumentException) {
            throw ManagementProtocolException.InvalidRequest(failure.message ?: "invalid JSON")
        }
        val required = setOf("version", "request_id", "operation", "parameters")
        if (root.keys != required) throw ManagementProtocolException.InvalidRequest("request fields are not the exact v1 schema")
        val version = (root["version"] as? JsonValue.Number)?.value
            ?: throw ManagementProtocolException.InvalidRequest("version must be an integer")
        if (version != ManagementLimits.PROTOCOL_VERSION.toLong()) {
            throw ManagementProtocolException.UnsupportedVersion("unsupported protocol version")
        }
        val requestId = (root["request_id"] as? JsonValue.StringValue)?.value
            ?: throw ManagementProtocolException.InvalidRequest("request_id must be a string")
        val operationName = (root["operation"] as? JsonValue.StringValue)?.value
            ?: throw ManagementProtocolException.InvalidRequest("operation must be a string")
        val operation = ManagementOperation.fromWire(operationName)
            ?: throw ManagementProtocolException.UnknownOperation("unknown operation")
        val parameters = (root["parameters"] as? JsonValue.ObjectValue)?.value
            ?: throw ManagementProtocolException.InvalidRequest("parameters must be an object")
        val ifGeneration = if (operation.mutation) {
            if (parameters.keys != setOf("if_generation")) {
                throw ManagementProtocolException.InvalidRequest("mutation parameters require exactly if_generation")
            }
            (parameters["if_generation"] as? JsonValue.Number)?.value
                ?.takeIf { it >= 0 }
                ?: throw ManagementProtocolException.InvalidRequest("if_generation must be a non-negative integer")
        } else {
            if (parameters.isNotEmpty()) {
                throw ManagementProtocolException.InvalidRequest("read parameters must be empty")
            }
            null
        }
        return try {
            ManagementRequest(
                requestId = ManagementRequestId(requestId),
                operation = operation,
                ifGeneration = ifGeneration,
                payloadSha256 = sha256(payload),
            )
        } catch (failure: IllegalArgumentException) {
            throw ManagementProtocolException.InvalidRequest(failure.message ?: "invalid request")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private sealed interface JsonValue {
    data class ObjectValue(val value: Map<String, JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class Number(val value: Long) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

/** Minimal strict JSON reader: bounded by the frame, duplicate-key rejecting, and depth-limited. */
private class StrictJson private constructor(private val source: String) {
    private var index = 0

    private fun parseRoot(): Map<String, JsonValue> {
        skipWhitespace()
        val result = parseObject(0).value
        skipWhitespace()
        require(index == source.length) { "trailing JSON content" }
        return result
    }

    private fun parseObject(depth: Int): JsonValue.ObjectValue {
        require(depth <= MAX_DEPTH) { "JSON nesting exceeds v1 bound" }
        expect('{')
        skipWhitespace()
        val fields = linkedMapOf<String, JsonValue>()
        if (take('}')) return JsonValue.ObjectValue(fields)
        while (true) {
            skipWhitespace()
            val key = parseString()
            require(fields[key] == null) { "duplicate JSON field" }
            skipWhitespace(); expect(':'); skipWhitespace()
            fields[key] = parseValue(depth + 1)
            skipWhitespace()
            if (take('}')) return JsonValue.ObjectValue(fields)
            expect(',')
        }
    }

    private fun parseValue(depth: Int): JsonValue = when (peek()) {
        '{' -> parseObject(depth)
        '"' -> JsonValue.StringValue(parseString())
        't' -> { literal("true"); JsonValue.BooleanValue(true) }
        'f' -> { literal("false"); JsonValue.BooleanValue(false) }
        'n' -> { literal("null"); JsonValue.NullValue }
        '-', in '0'..'9' -> JsonValue.Number(parseInteger())
        else -> error("unsupported JSON value")
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    require(index < source.length) { "truncated JSON escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "truncated unicode escape" }
                            val code = source.substring(index, index + 4).toIntOrNull(16)
                                ?: error("invalid unicode escape")
                            require(code !in 0xD800..0xDFFF) { "surrogate escapes are not accepted" }
                            out.append(code.toChar()); index += 4
                        }
                        else -> error("invalid JSON escape")
                    }
                }
                char.code < 0x20 -> error("unescaped JSON control character")
                char.isSurrogate() -> error("surrogate characters are not accepted")
                else -> out.append(char)
            }
        }
        error("unterminated JSON string")
    }

    private fun parseInteger(): Long {
        val start = index
        if (take('-')) require(peek() in '0'..'9') { "invalid JSON number" }
        if (take('0')) {
            require(peek() !in '0'..'9') { "leading-zero JSON number" }
        } else {
            require(peek() in '1'..'9') { "invalid JSON number" }
            while (peek() in '0'..'9') index++
        }
        require(peek() != '.' && peek() != 'e' && peek() != 'E') { "v1 accepts integers only" }
        return source.substring(start, index).toLongOrNull() ?: error("integer is outside int64")
    }

    private fun literal(value: String) {
        require(source.regionMatches(index, value, 0, value.length)) { "invalid JSON literal" }
        index += value.length
    }

    private fun skipWhitespace() { while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++ }
    private fun peek(): Char = source.getOrNull(index) ?: '\u0000'
    private fun take(char: Char): Boolean = if (peek() == char) { index++; true } else false
    private fun expect(char: Char) { require(take(char)) { "expected '$char'" } }

    companion object {
        private const val MAX_DEPTH = 3
        fun parseObject(source: String): Map<String, JsonValue> = StrictJson(source).parseRoot()
    }
}
